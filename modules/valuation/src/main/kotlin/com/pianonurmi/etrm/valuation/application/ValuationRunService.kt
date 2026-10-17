package com.pianonurmi.etrm.valuation.application

import com.pianonurmi.etrm.allocation.api.PortfolioValueQuery
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.DiscountFactors
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.paging.Cursor
import com.pianonurmi.etrm.platform.paging.page
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.VintageYears
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.OptionTerms
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.ForwardTerms
import com.pianonurmi.etrm.valuation.api.ForwardValue
import com.pianonurmi.etrm.valuation.api.OptionValuation
import com.pianonurmi.etrm.valuation.api.PnlExplainView
import com.pianonurmi.etrm.valuation.api.PortfolioBridgeView
import com.pianonurmi.etrm.valuation.api.PortfolioBucketRow
import com.pianonurmi.etrm.valuation.api.PortfolioCause
import com.pianonurmi.etrm.valuation.api.PortfolioExplainView
import com.pianonurmi.etrm.valuation.api.PortfolioObligationRow
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.RunKind
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationCommand
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultPage
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunRejected
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import com.pianonurmi.etrm.valuation.api.ValuationRunSubmitted
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import com.pianonurmi.etrm.valuation.domain.Black76
import com.pianonurmi.etrm.valuation.domain.Mtm
import com.pianonurmi.etrm.valuation.domain.OptionRight
import com.pianonurmi.etrm.valuation.domain.PnlExplainer
import com.pianonurmi.etrm.valuation.domain.PortfolioExplainer
import com.pianonurmi.etrm.valuation.domain.ResultKey
import com.pianonurmi.etrm.valuation.domain.ValuationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Valuation runs (ADR 0022): a run values every open trade of an entity for a date; it becomes official only when a
 * second person signs it off in the inbox, and its P&L explain against the previous official run is kept then.
 */
@Service
@Transactional
class ValuationRunService(
    private val repository: ValuationRepository,
    private val trades: TradeQuery,
    private val valuer: TradeValuer,
    private val bridges: PortfolioBridges,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : ValuationCommand {
    override fun run(legalEntityId: UUID, date: LocalDate, kind: RunKind, note: String?): ValuationRunView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.SYSTEM)
        actor.requireEntity(legalEntityId)
        val entity = referenceData.requireLegalEntity(legalEntityId)
        val open = trades.trades(TradeFilter(legalEntityId = legalEntityId, statuses = VALUED, limit = MAX_TRADES))
        val runId = Ids.newId()
        val books = mutableMapOf<UUID, BookType>()
        val discount = valuer.discounter(date)
        val valued = open.flatMap { trade ->
            valuer.value(runId, trade, date, entity.baseCurrency.currencyCode, discount) { books.getOrPut(it) { referenceData.requireBook(it).bookType } }
        }
        val portfolio = bridges.portfolioOf(runId, legalEntityId, date, entity.baseCurrency.currencyCode, valued)
        val results = portfolio?.let { bridges.withCheapestToDeliver(valued, it, date) } ?: valued
        val run = ValuationRunView(
            id = runId,
            legalEntityId = legalEntityId,
            valuationDate = date,
            kind = kind,
            status = RunStatus.DRAFT,
            baseCurrency = entity.baseCurrency.currencyCode,
            totalMtmBase = results.mapNotNull { it.mtmBase }.fold(BigDecimal.ZERO, BigDecimal::add).setScale(2),
            exceptions = results.count { it.status != ResultStatus.OK && it.status != ResultStatus.EXCLUDED },
            inputs = results.filter { it.curveCode != null }.associate { it.curveCode!! to it.snapshotId.toString() } +
                results.filter { it.discountCurveCode != null }.associate { it.discountCurveCode!! to it.discountSnapshotId.toString() } +
                results.mapNotNull { it.option }.associate { it.volCurveCode to it.volSnapshotId.toString() },
            note = note,
            createdBy = actor.userId,
            createdAt = clock.instant(),
            signedOffBy = null,
            signedOffAt = null,
            signOffNote = null,
            supersededAt = null,
        )
        repository.insert(run, results)
        portfolio?.let(repository::insertPortfolio)
        return run
    }

    override fun submit(runId: UUID): ValuationRunView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.SYSTEM)
        val run = repository.run(runId, lock = true) ?: throw NotFoundException("ValuationRun", runId)
        actor.requireEntity(run.legalEntityId)
        if (run.status != RunStatus.DRAFT) throw ValidationException("Run ${run.id} is ${run.status}")
        repository.setStatus(runId, RunStatus.PENDING_SIGN_OFF)
        val submitted = checkNotNull(repository.run(runId)) { "Valuation run $runId is gone" }
        events.publishEvent(ValuationRunSubmitted(submitted))
        return submitted
    }

    /** From the sign-off task: the run becomes official for its date and its P&L explain against the previous one is kept. */
    fun signOff(runId: UUID, by: String, note: String?): ValuationRunView {
        val run = repository.run(runId, lock = true) ?: throw NotFoundException("ValuationRun", runId)
        if (run.status != RunStatus.PENDING_SIGN_OFF) throw ValidationException("Run ${run.id} is ${run.status}")
        SecondPerson.require(by, run.createdBy) { "A valuation is signed off by someone other than who ran it" }
        if (run.exceptions > 0 && note.isNullOrBlank()) throw ValidationException("Signing off a run with ${run.exceptions} exceptions needs a reason")
        val now = clock.instant()
        val previous = repository.official(run.legalEntityId, run.valuationDate, orEarlier = false)
        previous?.let { repository.supersede(it.id, now) }
        repository.setStatus(runId, RunStatus.OFFICIAL, by, now, note)
        val prior = repository.official(run.legalEntityId, run.valuationDate, orEarlier = true, before = true)
        if (prior != null) {
            val rows = PnlExplainer.explain(
                repository.results(prior.id, null),
                repository.results(runId, null),
                valuer.roller(run.valuationDate),
                trades::isCancelled,
            )
            repository.insertExplain(runId, prior.id, rows)
            val before = repository.portfolio(prior.id)
            val now = repository.portfolio(runId)
            if (before != null && now != null) repository.insertPortfolioExplain(runId, prior.id, PortfolioExplainer.explain(before, now))
        }
        val official = checkNotNull(repository.run(runId)) { "Valuation run $runId is gone" }
        events.publishEvent(ValuationRunSignedOff(official, previous?.id))
        return official
    }

    fun reject(runId: UUID, reason: String?): ValuationRunView {
        val run = repository.run(runId, lock = true) ?: throw NotFoundException("ValuationRun", runId)
        if (run.status != RunStatus.PENDING_SIGN_OFF) throw ValidationException("Run ${run.id} is ${run.status}")
        repository.setStatus(runId, RunStatus.REJECTED)
        val rejected = checkNotNull(repository.run(runId)) { "Valuation run $runId is gone" }
        events.publishEvent(ValuationRunRejected(rejected, reason))
        return rejected
    }

    private companion object {
        val VALUED = setOf(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)
        const val MAX_TRADES = 100_000
    }
}
