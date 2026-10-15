package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.MarginCallQuery
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.CounterpartyExposureView
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.domain.CreditExposure
import com.pianonurmi.etrm.risk.domain.MeasurementGaps
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementReports
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Each counterparty's current credit exposure for one entity (ADR 0022): positive MtM from the official valuation,
 * settlement balances, potential future exposure of open trades and credit support, stored for limits and reports.
 */
@Service
@Transactional
class CreditExposureService(
    private val repository: RiskRepository,
    private val referenceData: ReferenceDataQuery,
    private val valuations: ValuationQuery,
    private val settlement: SettlementReports,
    /** Cleared futures and the margin held for them (ADR 0046). */
    private val clearing: ClearingQuery,
    private val margin: MarginCallQuery,
    private val credit: CreditFigures,
    private val fx: RiskFx,
    private val clock: Clock,
) {
    fun measureExposures(entity: LegalEntityView, today: LocalDate) {
        val base = entity.baseCurrency.currencyCode
        // Margined futures are settled every day: no MtM or PFE to the clearing house, which holds the margin instead.
        val margined = clearing.marginedTradeIds(entity.id)
        val posted = margin.marginPosted(entity.id).groupBy { it.clearingHouseId }
        val counterparties = referenceData.counterparties().associateBy { it.id }
        val run = valuations.official(entity.id, today, orEarlier = true)
        val results = run?.let { valuations.results(it.id) }.orEmpty().filter { it.tradeId !in margined }
        val mtmByCounterparty = results
            .filter { it.status == ResultStatus.OK && it.mtmBase != null }
            .groupBy { it.counterpartyId }
            .mapValues { (_, rows) -> rows.groupBy { it.tradeId }.mapValues { (_, r) -> r.sumOf { it.mtmBase!! } } }
        val balances = settlement.counterpartyBalances(entity.id).groupBy { it.counterpartyId }
        val addOns = repository.pfeAddOns()
        val gapsByCounterparty = mutableMapOf<UUID, MeasurementGaps>()
        // A trade the valuation could not price counts as no MtM: the exposure says which one and why.
        results.filter { it.status != ResultStatus.OK || it.mtmBase == null }.forEach {
            gapsByCounterparty.getOrPut(it.counterpartyId) { MeasurementGaps() }.omitted("MtM of ${it.tradeNumber} not valued: ${it.status}")
        }
        val pfeByCounterparty = credit.positions.openTrades(entity.id, LimitScope(), null).filter { it.id !in margined }.groupBy { it.counterpartyId }
            .mapValues { (counterpartyId, list) ->
                val gaps = gapsByCounterparty.getOrPut(counterpartyId) { MeasurementGaps() }
                list.sumOf { credit.pfe(it, addOns, base, today, gaps) }
            }
        val support = repository.creditSupportFor(null).filter { it.validOn(today) && (it.legalEntityId == null || it.legalEntityId == entity.id) }
            .groupBy { it.counterpartyId }
        val known = repository.exposures(entity.id).map { it.counterpartyId }
        val measured = mtmByCounterparty.keys + gapsByCounterparty.keys + balances.keys + pfeByCounterparty.keys + support.keys + posted.keys + known
        measured.toSet().forEach { counterpartyId ->
            val netting = counterparties[counterpartyId]?.nettingAgreed == true
            val gaps = gapsByCounterparty.getOrPut(counterpartyId) { MeasurementGaps() }
            val mtm = CreditExposure.positiveMtm(mtmByCounterparty[counterpartyId].orEmpty().values, netting)
            val rows = balances[counterpartyId].orEmpty()
            val receivables = rows.filter { it.direction == SettlementDirection.RECEIVABLE }.sumOf { fx.convert(it.total, it.currency, base, today, gaps) }
            val payables = rows.filter { it.direction == SettlementDirection.PAYABLE }.sumOf { fx.convert(it.total, it.currency, base, today, gaps) }
            val collateral = support[counterpartyId].orEmpty().sumOf {
                fx.convert(it.amount, it.currency, base, today, gaps).let { amount -> if (it.kind.received) amount else amount.negate() }
            } - posted[counterpartyId].orEmpty().sumOf {
                fx.convert(it.initialMargin + (it.cashBalance ?: BigDecimal.ZERO).max(BigDecimal.ZERO), it.currency, base, today, gaps)
            }
            val pfe = pfeByCounterparty[counterpartyId] ?: BigDecimal.ZERO
            repository.upsertExposure(
                CounterpartyExposureView(
                    entity.id,
                    counterpartyId,
                    base,
                    money(mtm),
                    money(receivables),
                    money(if (netting) payables else BigDecimal.ZERO),
                    money(pfe),
                    money(collateral),
                    CreditExposure.exposure(mtm, receivables, payables, netting, pfe, collateral),
                    run?.id,
                    clock.instant(),
                    !gaps.isComplete,
                    gaps.currencies,
                    gaps.reasons,
                ),
            )
        }
    }
}
