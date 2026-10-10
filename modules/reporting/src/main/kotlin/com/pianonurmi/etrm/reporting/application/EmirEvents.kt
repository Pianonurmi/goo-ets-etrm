package com.pianonurmi.etrm.reporting.application

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.reporting.api.EmirAction
import com.pianonurmi.etrm.reporting.api.EmirEventView
import com.pianonurmi.etrm.reporting.domain.EmirActions
import com.pianonurmi.etrm.reporting.domain.EmirFields
import com.pianonurmi.etrm.reporting.domain.EmirParties
import com.pianonurmi.etrm.reporting.domain.ReportingRepository
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Records EMIR lifecycle events of reportable trades as they happen: a confirmation is a new trade (NEWT) or a
 * modification (MODI), a cancellation of a reported trade a termination (TERM), and each signed-off valuation a
 * valuation (VALU) for FC and NFC+ entities. Recording never fails the transaction that published the event.
 */
@Component
class EmirEvents(
    private val repository: ReportingRepository,
    private val referenceData: ReferenceDataQuery,
    private val trades: TradeQuery,
    private val valuations: ValuationQuery,
    private val savepoints: Savepoints,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(EmirEvents::class.java)

    @EventListener
    fun on(event: TradeConfirmed) = safely(event.trade, "confirmation:v${event.trade.version}") { previous ->
        EmirActions.onConfirmation(previous)
    }

    @EventListener
    fun on(event: TradeCancelled) = safely(event.trade, "cancellation:v${event.trade.version}") { previous ->
        EmirActions.onCancellation(previous)
    }

    @EventListener
    fun on(event: ValuationRunSignedOff) {
        val run = event.run
        val entity = referenceData.legalEntity(run.legalEntityId) ?: return
        if (!EmirActions.reportsValuations(entity.emirClassification)) return
        runCatching {
            savepoints.attempt {
                valuations.results(run.id).filter { it.status == ResultStatus.OK && it.mtmBase != null }.groupBy { it.tradeId }.forEach { (tradeId, rows) ->
                    val trade = trades.trade(tradeId)?.takeIf { it.emirReportable } ?: return@forEach
                    val fields = EmirFields.valuation(trade, parties(trade), run.valuationDate, rows.sumOf { it.mtmBase!! }, run.baseCurrency, clock.instant())
                    repository.insertEmirEvent(event(trade, EmirAction.VALU, run.valuationDate, "valuation:${run.id}", fields))
                }
            }
        }.onFailure { log.warn("EMIR valuations of run {} not recorded: {}", run.id, it.message) }
    }

    /** A reported trade that should not have been reported. */
    fun error(trade: TradeView, reason: String): EmirEventView? {
        if (actions(trade.id).none { it == EmirAction.NEWT }) return null
        if (actions(trade.id).contains(EmirAction.EROR)) return null
        val event =
            event(
                trade,
                EmirAction.EROR,
                LocalDate.now(clock),
                "error:${Ids.newId()}",
                EmirFields.trade(trade, parties(trade), EmirAction.EROR, LocalDate.now(clock)) + mapOf("REASON" to reason),
            )
        repository.insertEmirEvent(event)
        return event
    }

    /** Records the action [decide] picks for a reportable trade, once per source (a replayed event records nothing). */
    private fun safely(trade: TradeView, source: String, decide: (List<EmirAction>) -> EmirAction?) {
        if (!trade.emirReportable) return
        runCatching {
            savepoints.attempt {
                val previous = repository.emirEvents(trade.id)
                if (previous.none { it.sourceRef == source }) decide(previous.map { it.action })?.let { record(trade, it, source) }
            }
        }.onFailure { log.warn("EMIR event of trade {} not recorded: {}", trade.tradeNumber, it.message) }
    }

    private fun actions(tradeId: UUID): List<EmirAction> = repository.emirEvents(tradeId).map { it.action }

    private fun record(trade: TradeView, action: EmirAction, source: String) {
        val today = LocalDate.now(clock)
        repository.insertEmirEvent(event(trade, action, today, source, EmirFields.trade(trade, parties(trade), action, today)))
    }

    private fun parties(trade: TradeView): EmirParties {
        val entity = referenceData.requireLegalEntity(trade.legalEntityId)
        val counterparty = referenceData.counterparty(trade.counterpartyId)
        return EmirParties(
            reportingLei = entity.lei,
            classification = entity.emirClassification,
            otherCounterpartyLei = counterparty?.lei,
            intragroup = counterparty?.counterpartyType == CounterpartyType.INTERCOMPANY,
            ccpLei = trade.regulatory.ccpCounterpartyId?.let { referenceData.counterparty(it)?.lei },
        )
    }

    private fun event(trade: TradeView, action: EmirAction, date: LocalDate, source: String, fields: Map<String, String?>) = EmirEventView(
        Ids.newId(),
        trade.legalEntityId,
        trade.id,
        trade.tradeNumber,
        trade.version,
        trade.regulatory.uti,
        action,
        date,
        source,
        fields,
        actorProvider.current().userId,
        clock.instant(),
    )
}
