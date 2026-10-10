package com.pianonurmi.etrm.valuation.application

import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.PnlExplainView
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.RunKind
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationCommand
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunRejected
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import com.pianonurmi.etrm.valuation.api.ValuationRunSubmitted
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import com.pianonurmi.etrm.valuation.domain.Mtm
import com.pianonurmi.etrm.valuation.domain.PnlExplainer
import com.pianonurmi.etrm.valuation.domain.ValuationRepository
import com.pianonurmi.etrm.valuation.domain.VintageYears
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Values the remaining quantity of every open trade per delivery schedule against official curves, undiscounted:
 * MtM = q × (market − contract), q signed by side, the market price converted into the contract currency and the
 * result into the entity's base currency at the valuation date's rates. Index legs not yet fixed are worth their
 * spread; formula legs are left out.
 */
@Service
@Transactional
class ValuationService(
    private val repository: ValuationRepository,
    private val trades: TradeQuery,
    private val curves: CurveQuery,
    private val fx: FxRates,
    private val fixings: IndexFixings,
    private val criteria: CriteriaService,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : ValuationQuery, ValuationCommand {
    override fun run(legalEntityId: UUID, date: LocalDate, kind: RunKind, note: String?): ValuationRunView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.SYSTEM)
        actor.requireEntity(legalEntityId)
        val entity = referenceData.requireLegalEntity(legalEntityId)
        val open = trades.trades(TradeFilter(legalEntityId = legalEntityId, statuses = VALUED, limit = MAX_TRADES))
        val runId = Ids.newId()
        val books = mutableMapOf<UUID, BookType>()
        val results = open.flatMap { trade ->
            value(runId, trade, date, entity.baseCurrency.currencyCode) { books.getOrPut(it) { referenceData.requireBook(it).bookType } }
        }
        val run = ValuationRunView(
            id = runId,
            legalEntityId = legalEntityId,
            valuationDate = date,
            kind = kind,
            status = RunStatus.DRAFT,
            baseCurrency = entity.baseCurrency.currencyCode,
            totalMtmBase = results.mapNotNull { it.mtmBase }.fold(BigDecimal.ZERO, BigDecimal::add).setScale(2),
            exceptions = results.count { it.status != ResultStatus.OK && it.status != ResultStatus.EXCLUDED },
            inputs = results.filter { it.curveCode != null }.associate { it.curveCode!! to it.snapshotId.toString() },
            note = note,
            createdBy = actor.userId,
            createdAt = clock.instant(),
            signedOffBy = null,
            signedOffAt = null,
            signOffNote = null,
            supersededAt = null,
        )
        repository.insert(run, results)
        return run
    }

    override fun submit(runId: UUID): ValuationRunView {
        actorProvider.current().requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.SYSTEM)
        val run = repository.run(runId, lock = true) ?: throw NotFoundException("ValuationRun", runId)
        if (run.status != RunStatus.DRAFT) throw ValidationException("Run ${run.id} is ${run.status}")
        repository.setStatus(runId, RunStatus.PENDING_SIGN_OFF)
        val submitted = repository.run(runId)!!
        events.publishEvent(ValuationRunSubmitted(submitted))
        return submitted
    }

    /** From the sign-off task: the run becomes official for its date and its P&L explain against the previous one is kept. */
    fun signOff(runId: UUID, by: String, note: String?): ValuationRunView {
        val run = repository.run(runId, lock = true) ?: throw NotFoundException("ValuationRun", runId)
        if (run.status != RunStatus.PENDING_SIGN_OFF) throw ValidationException("Run ${run.id} is ${run.status}")
        if (run.createdBy == by) throw SegregationOfDutiesException("A valuation is signed off by someone other than who ran it")
        if (run.exceptions > 0 && note.isNullOrBlank()) throw ValidationException("Signing off a run with ${run.exceptions} exceptions needs a reason")
        val now = clock.instant()
        val previous = repository.official(run.legalEntityId, run.valuationDate, orEarlier = false)
        previous?.let { repository.supersede(it.id, now) }
        repository.setStatus(runId, RunStatus.OFFICIAL, by, now, note)
        val prior = repository.official(run.legalEntityId, run.valuationDate, orEarlier = true, before = true)
        if (prior != null) {
            repository.insertExplain(runId, prior.id, PnlExplainer.explain(repository.results(prior.id, null), repository.results(runId, null), ::cancelled))
        }
        val official = repository.run(runId)!!
        events.publishEvent(ValuationRunSignedOff(official, previous?.id))
        return official
    }

    fun reject(runId: UUID, reason: String?): ValuationRunView {
        val run = repository.run(runId, lock = true) ?: throw NotFoundException("ValuationRun", runId)
        if (run.status != RunStatus.PENDING_SIGN_OFF) throw ValidationException("Run ${run.id} is ${run.status}")
        repository.setStatus(runId, RunStatus.REJECTED)
        val rejected = repository.run(runId)!!
        events.publishEvent(ValuationRunRejected(rejected, reason))
        return rejected
    }

    private fun cancelled(tradeId: UUID): Boolean = trades.trade(tradeId)?.status == TradeStatus.CANCELLED

    // ----------------------------------------------------------------------------------------------------------
    // Pricing
    // ----------------------------------------------------------------------------------------------------------

    private fun value(runId: UUID, trade: TradeView, date: LocalDate, base: String, bookType: (UUID) -> BookType): List<ValuationResultView> =
        trade.legs.flatMap { leg ->
            leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.map { schedule ->
                val sign = if (leg.side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
                val draft = ValuationResultView(
                    runId = runId,
                    scheduleId = schedule.id,
                    tradeId = trade.id,
                    tradeNumber = trade.tradeNumber,
                    tradeVersion = trade.version,
                    legNo = leg.legNo,
                    bookId = trade.bookId,
                    bookType = bookType(trade.bookId),
                    counterpartyId = trade.counterpartyId,
                    productFamily = trade.productFamily,
                    productKey = leg.allowanceProductCode ?: leg.bucketCode,
                    productionYear = null,
                    deliveryDate = schedule.deliveryDate,
                    quantity = schedule.remainingQuantity * sign,
                    contractPrice = null,
                    contractCurrency = leg.priceCurrency.currencyCode,
                    marketPrice = null,
                    marketCurrency = null,
                    priceOrigin = null,
                    curveCode = null,
                    snapshotId = null,
                    curveDate = null,
                    marketPriceContract = null,
                    fxToBase = null,
                    mtm = null,
                    mtmBase = null,
                    status = ResultStatus.OK,
                    note = null,
                )
                price(draft, trade, leg, date, base)
            }
        }

    /** A schedule that cannot be valued, with the reason. */
    private class Unpriced(val status: ResultStatus, val year: Int?, reason: String) : RuntimeException(reason)

    private fun price(draft: ValuationResultView, trade: TradeView, leg: TradeLegView, date: LocalDate, base: String): ValuationResultView = try {
        priced(draft, trade, leg, date, base)
    } catch (e: Unpriced) {
        draft.copy(productionYear = e.year, status = e.status, note = e.message)
    }

    private fun priced(draft: ValuationResultView, trade: TradeView, leg: TradeLegView, date: LocalDate, base: String): ValuationResultView {
        if (leg.priceType == PriceType.FORMULA) throw Unpriced(ResultStatus.EXCLUDED, null, "Formula-priced legs are not valued")
        val year = if (trade.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN) {
            leg.criterion?.let { VintageYears.of(criteria.bind(it, criteria.contextFor(draft.deliveryDate)), draft.deliveryDate) }
        } else {
            null
        }
        val market: CurvePrice = when (trade.productFamily) {
            ProductFamily.EMISSION_ALLOWANCE -> curves.forwardPrice(leg.allowanceProductCode!!, draft.deliveryDate, date)
            ProductFamily.GUARANTEE_OF_ORIGIN -> curves.vintagePrice(
                leg.bucketCode ?: throw Unpriced(ResultStatus.MISSING_BUCKET, year, "No product bucket contains the delivery criteria"),
                year ?: draft.deliveryDate.year,
                date,
            )
        } ?: throw Unpriced(ResultStatus.MISSING_PRICE, year, "No official curve for ${draft.productKey} on or before $date")
        val toContract = fx.rate(date, market.currency, draft.contractCurrency)?.rate
            ?: throw Unpriced(ResultStatus.MISSING_FX, year, "No ${market.currency}/${draft.contractCurrency} rate on $date")
        val toBase = fx.rate(date, draft.contractCurrency, base)?.rate
            ?: throw Unpriced(ResultStatus.MISSING_FX, year, "No ${draft.contractCurrency}/$base rate on $date")
        val marketContract = (market.value * toContract).setScale(6, RoundingMode.HALF_EVEN)
        val contract = when (leg.priceType) {
            PriceType.FIXED -> leg.price
            // An index leg pays its fixing plus the spread; until fixed, the forward price stands in for the fixing.
            else -> (fixedIndex(leg, draft.deliveryDate, date) ?: marketContract) + leg.price
        }
        val value = Mtm.value(draft.quantity, marketContract, contract)
        return draft.copy(
            productionYear = year,
            contractPrice = contract,
            marketPrice = market.value,
            marketCurrency = market.currency,
            priceOrigin = market.origin.name,
            curveCode = market.curveCode,
            snapshotId = market.snapshotId,
            curveDate = market.curveDate,
            marketPriceContract = marketContract,
            fxToBase = toBase,
            mtm = Mtm.money(value),
            mtmBase = Mtm.money(value * toBase),
            note = if (market.curveDate != date) "Curve of ${market.curveDate}" else null,
        )
    }

    private fun fixedIndex(leg: TradeLegView, deliveryDate: LocalDate, date: LocalDate): BigDecimal? =
        leg.priceIndex?.takeIf { !deliveryDate.isAfter(date) }?.let { fixings.fixing(it, deliveryDate)?.value }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    private fun readable(legalEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.AUDITOR, Role.TRADER, Role.SYSTEM)
        actor.requireEntity(legalEntityId)
    }

    @Transactional(readOnly = true)
    override fun run(id: UUID): ValuationRunView? = repository.run(id)?.also { readable(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun runs(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<ValuationRunView> {
        readable(legalEntityId)
        return repository.runs(legalEntityId, from, to)
    }

    @Transactional(readOnly = true)
    override fun results(runId: UUID, bookId: UUID?): List<ValuationResultView> {
        val run = repository.run(runId) ?: throw NotFoundException("ValuationRun", runId)
        readable(run.legalEntityId)
        return repository.results(runId, bookId)
    }

    @Transactional(readOnly = true)
    override fun official(legalEntityId: UUID, date: LocalDate, orEarlier: Boolean): ValuationRunView? {
        readable(legalEntityId)
        return repository.official(legalEntityId, date, orEarlier)
    }

    @Transactional(readOnly = true)
    override fun explain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PnlExplainView? {
        readable(legalEntityId)
        val r0 = repository.official(legalEntityId, from, orEarlier = true) ?: return null
        val r1 = repository.official(legalEntityId, to, orEarlier = true) ?: return null
        val rows = PnlExplainer.explain(repository.results(r0.id, null), repository.results(r1.id, null), ::cancelled)
        val components = ExplainComponent.entries.associateWith { c -> rows.filter { it.component == c }.fold(BigDecimal.ZERO) { s, r -> s + r.amountBase } }
            .filterValues { it.signum() != 0 }
        return PnlExplainView(
            legalEntityId,
            r0.id,
            r1.id,
            r0.valuationDate,
            r1.valuationDate,
            r1.baseCurrency,
            r0.totalMtmBase,
            r1.totalMtmBase,
            components,
            rows,
        )
    }

    @Transactional(readOnly = true)
    override fun tradeMtm(tradeId: UUID, date: LocalDate): List<ValuationResultView> {
        val trade = trades.trade(tradeId) ?: throw NotFoundException("Trade", tradeId)
        readable(trade.legalEntityId)
        val base = referenceData.requireLegalEntity(trade.legalEntityId).baseCurrency.currencyCode
        val bookType = referenceData.requireBook(trade.bookId).bookType
        return value(UUID(0, 0), trade, date, base) { bookType }
    }

    private companion object {
        val VALUED = setOf(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)
        const val MAX_TRADES = 100_000
    }
}
