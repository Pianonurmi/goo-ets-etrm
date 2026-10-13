package com.pianonurmi.etrm.valuation.application

import com.pianonurmi.etrm.allocation.api.PortfolioValueQuery
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.DiscountFactors
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.VintageYears
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
import com.pianonurmi.etrm.valuation.api.ForwardTerms
import com.pianonurmi.etrm.valuation.api.ForwardValue
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
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunRejected
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import com.pianonurmi.etrm.valuation.api.ValuationRunSubmitted
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import com.pianonurmi.etrm.valuation.domain.Mtm
import com.pianonurmi.etrm.valuation.domain.PnlExplainer
import com.pianonurmi.etrm.valuation.domain.PortfolioExplainer
import com.pianonurmi.etrm.valuation.domain.ValuationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Values the remaining quantity of every open trade per delivery schedule against official curves, discounted to the
 * payment date (ADR 0044): MtM = q × (market − contract) × DF, q signed by side, the market price converted into the
 * contract currency and the result into the entity's base currency at the valuation date's rates. DF comes from the
 * contract currency's official discount curve (1 without one); futures are margined daily and not discounted. Index
 * legs not yet fixed are worth their spread; formula legs are left out.
 */
@Service
@Transactional
class ValuationService(
    private val repository: ValuationRepository,
    private val trades: TradeQuery,
    private val curves: CurveQuery,
    private val fx: FxRates,
    private val fixings: IndexFixings,
    private val discounting: DiscountFactors,
    private val criteria: CriteriaService,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val portfolioValues: ObjectProvider<PortfolioValueQuery>? = null,
    private val savepoints: Savepoints = Savepoints.NONE,
) : ValuationQuery, ValuationCommand {
    private val log = LoggerFactory.getLogger(ValuationService::class.java)

    override fun run(legalEntityId: UUID, date: LocalDate, kind: RunKind, note: String?): ValuationRunView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.SYSTEM)
        actor.requireEntity(legalEntityId)
        val entity = referenceData.requireLegalEntity(legalEntityId)
        val open = trades.trades(TradeFilter(legalEntityId = legalEntityId, statuses = VALUED, limit = MAX_TRADES))
        val runId = Ids.newId()
        val books = mutableMapOf<UUID, BookType>()
        val discount = discounter(date)
        val valued = open.flatMap { trade ->
            value(runId, trade, date, entity.baseCurrency.currencyCode, discount) { books.getOrPut(it) { referenceData.requireBook(it).bookType } }
        }
        val portfolio = portfolioOf(runId, legalEntityId, date, entity.baseCurrency.currencyCode, valued)
        val results = portfolio?.let { withCheapestToDeliver(valued, it, date) } ?: valued
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
                results.filter { it.discountCurveCode != null }.associate { it.discountCurveCode!! to it.discountSnapshotId.toString() },
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

    /**
     * The run's portfolio value (ADR 0039, management view): allocation's value of the held book at the run date's
     * prices, plus the GoO trades' MtM in EUR. None when allocation is not available or cannot value the book (never
     * failing the run), or when the base currency has no rate to EUR.
     */
    private fun portfolioOf(runId: UUID, legalEntityId: UUID, date: LocalDate, base: String, results: List<ValuationResultView>): PortfolioBridgeView? {
        val query = portfolioValues?.ifAvailable ?: return null
        val value = try {
            savepoints.attempt { query.value(legalEntityId, date) }
        } catch (e: RuntimeException) {
            log.warn("Portfolio value of {} on {} could not be computed", legalEntityId, date, e)
            null
        } ?: return null
        val toEur = if (base == EUR) BigDecimal.ONE else fx.rate(date, base, EUR)?.rate ?: return null
        val tradeMtm = (
            results.filter { it.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN }
                .mapNotNull { it.mtmBase }.fold(BigDecimal.ZERO, BigDecimal::add) * toEur
        ).setScale(2, RoundingMode.HALF_UP)
        return PortfolioBridgeView(
            runId = runId,
            legalEntityId = legalEntityId,
            valuationDate = date,
            currency = value.currency,
            heldMwh = value.heldMwh,
            inventoryValue = value.inventoryValueEur,
            tradeMtm = tradeMtm,
            giveAway = value.giveAwayEur,
            transferFees = value.transferFeesEur,
            expiryHaircut = value.expiryHaircutEur,
            portfolioValue = value.netEur + tradeMtm,
            allocatedMwh = value.allocatedMwh,
            shortfallMwh = value.shortfallMwh,
            unpricedMwh = value.unpricedMwh,
            stamp = value.stamp,
            computedAt = value.computedAt,
            buckets = value.byBucket.map { PortfolioBucketRow(it.bucketCode, it.productionYear, it.heldMwh, it.valueEur, it.allocatedMwh) },
            obligations = value.byObligation.map {
                PortfolioObligationRow(
                    obligationId = it.obligationId,
                    scheduleId = it.sourceRef,
                    tradeRef = it.tradeRef,
                    bucketCode = it.bucketCode,
                    vintageYear = it.vintageYear,
                    demandMwh = it.demandMwh,
                    allocatedMwh = it.allocatedMwh,
                    cheapestToDeliverEur = it.cheapestToDeliverEur,
                    saleMarkEur = it.saleMarkEur,
                    giveAwayEur = it.giveAwayEur,
                    transferFeesEur = it.transferFeesEur,
                )
            },
            priorRunId = null,
            explain = emptyMap(),
        )
    }

    /**
     * A GoO sale valued against the certificates that deliver it instead of its bucket curve: the cheapest-to-deliver
     * price (EUR per MWh) in the contract currency, and the MtM on it. Shown only; the official MtM is unchanged.
     */
    private fun withCheapestToDeliver(results: List<ValuationResultView>, portfolio: PortfolioBridgeView, date: LocalDate): List<ValuationResultView> {
        val ctd = portfolio.obligations.filter {
            it.scheduleId != null && it.cheapestToDeliverEur != null
        }.associate { it.scheduleId!! to it.cheapestToDeliverEur!! }
        return results.map { result ->
            val price = ctd[result.scheduleId]
            if (price == null || result.contractPrice == null || result.fxToBase == null) return@map result
            val inContract = if (result.contractCurrency == EUR) BigDecimal.ONE else fx.rate(date, EUR, result.contractCurrency)?.rate ?: return@map result
            val mtm = result.quantity * (price * inContract - result.contractPrice) * (result.discountFactor ?: BigDecimal.ONE)
            result.copy(ctdPriceEur = price, ctdMtmBase = (mtm * result.fxToBase).setScale(2, RoundingMode.HALF_UP))
        }
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
            val rows = PnlExplainer.explain(repository.results(prior.id, null), repository.results(runId, null), roller(run.valuationDate), ::cancelled)
            repository.insertExplain(runId, prior.id, rows)
            val before = repository.portfolio(prior.id)
            val now = repository.portfolio(runId)
            if (before != null && now != null) repository.insertPortfolioExplain(runId, prior.id, PortfolioExplainer.explain(before, now))
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

    /** Discount factors of one valuation date, read once per currency and payment date. */
    private fun discounter(date: LocalDate): (String, LocalDate) -> DiscountFactor {
        val memo = HashMap<Pair<String, LocalDate>, DiscountFactor>()
        return { currency, payDate -> memo.getOrPut(currency to payDate) { discounting.discountFactor(currency, payDate, date) } }
    }

    /** The earlier result's discount curve seen from [date], for the later result's payment: the explain's TIME step. */
    private fun roller(date: LocalDate): (ValuationResultView, ValuationResultView) -> BigDecimal {
        val memo = HashMap<Pair<UUID, LocalDate>, BigDecimal>()
        return { r0, r1 ->
            val snapshot = r0.discountSnapshotId
            val payDate = r1.payDate
            if (snapshot == null || payDate == null) {
                BigDecimal.ONE
            } else {
                memo.getOrPut(snapshot to payDate) { discounting.discountFactor(snapshot, date, payDate).value }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Pricing
    // ----------------------------------------------------------------------------------------------------------

    private fun value(
        runId: UUID,
        trade: TradeView,
        date: LocalDate,
        base: String,
        discount: (String, LocalDate) -> DiscountFactor,
        bookType: (UUID) -> BookType,
    ): List<ValuationResultView> =
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
                    payDate = schedule.paymentDate ?: schedule.deliveryDate,
                )
                price(draft, trade, leg, date, base, discount)
            }
        }

    /** A schedule that cannot be valued, with the reason. */
    private class Unpriced(val status: ResultStatus, val year: Int?, reason: String) : RuntimeException(reason)

    private fun price(
        draft: ValuationResultView,
        trade: TradeView,
        leg: TradeLegView,
        date: LocalDate,
        base: String,
        discount: (String, LocalDate) -> DiscountFactor,
    ): ValuationResultView = try {
        priced(draft, trade, leg, date, base, discount)
    } catch (e: Unpriced) {
        draft.copy(productionYear = e.year, status = e.status, note = e.message)
    }

    private fun priced(
        draft: ValuationResultView,
        trade: TradeView,
        leg: TradeLegView,
        date: LocalDate,
        base: String,
        discount: (String, LocalDate) -> DiscountFactor,
    ): ValuationResultView {
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
        // Futures are margined daily: their value is paid out now, not on delivery.
        val df = if (leg.futuresContractCode != null) DiscountFactor.NONE else discount(draft.contractCurrency, draft.payDate!!)
        val undiscounted = Mtm.value(draft.quantity, marketContract, contract)
        val value = Mtm.value(draft.quantity, marketContract, contract, df.value)
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
            discountFactor = df.value,
            discountCurveCode = df.curveCode,
            discountSnapshotId = df.snapshotId,
            mtmUndiscounted = Mtm.money(undiscounted),
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
    override fun portfolio(runId: UUID): PortfolioBridgeView? {
        val run = repository.run(runId) ?: throw NotFoundException("ValuationRun", runId)
        readable(run.legalEntityId)
        return repository.portfolio(runId)
    }

    override fun portfolioExplain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PortfolioExplainView {
        readable(legalEntityId)
        if (to.isBefore(from)) throw ValidationException("The period ends before it starts")
        val steps = repository.portfolioExplains(legalEntityId, from, to)
        val totals = PortfolioCause.entries.associateWith { cause -> steps.mapNotNull { it.causes[cause] }.fold(BigDecimal.ZERO, BigDecimal::add) }
        val realised = portfolioValues?.ifAvailable?.let { query ->
            // Plans approved after the first day up to and including the last, like the runs.
            runCatching { savepoints.attempt { query.realisedGiveAway(legalEntityId, from.plusDays(1), to.plusDays(1)).totalEur } }.getOrNull()
        }
        return PortfolioExplainView(legalEntityId, from, to, EUR, steps, totals, realised)
    }

    override fun explain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PnlExplainView? {
        readable(legalEntityId)
        val r0 = repository.official(legalEntityId, from, orEarlier = true) ?: return null
        val r1 = repository.official(legalEntityId, to, orEarlier = true) ?: return null
        val rows = PnlExplainer.explain(repository.results(r0.id, null), repository.results(r1.id, null), roller(r1.valuationDate), ::cancelled)
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
    override fun forwardValue(legalEntityId: UUID, date: LocalDate, terms: ForwardTerms): ForwardValue? {
        readable(legalEntityId)
        val base = referenceData.requireLegalEntity(legalEntityId).baseCurrency.currencyCode
        val market = when (terms.productFamily) {
            ProductFamily.EMISSION_ALLOWANCE -> terms.allowanceProductCode?.let { curves.forwardPrice(it, terms.deliveryDate, date) }
            ProductFamily.GUARANTEE_OF_ORIGIN -> terms.bucketCode?.let { curves.vintagePrice(it, terms.productionYear ?: terms.deliveryDate.year, date) }
        } ?: return null
        val toContract = fx.rate(date, market.currency, terms.currency)?.rate ?: return null
        val toBase = fx.rate(date, terms.currency, base)?.rate ?: return null
        val marketContract = (market.value * toContract).setScale(6, RoundingMode.HALF_EVEN)
        val df = discounting.discountFactor(terms.currency, terms.payDate, date)
        val value = Mtm.value(terms.quantity, marketContract, terms.price, df.value)
        return ForwardValue(marketContract, df.value, Mtm.money(value), Mtm.money(value * toBase), base)
    }

    @Transactional(readOnly = true)
    override fun tradeMtm(tradeId: UUID, date: LocalDate): List<ValuationResultView> {
        val trade = trades.trade(tradeId) ?: throw NotFoundException("Trade", tradeId)
        readable(trade.legalEntityId)
        val base = referenceData.requireLegalEntity(trade.legalEntityId).baseCurrency.currencyCode
        val bookType = referenceData.requireBook(trade.bookId).bookType
        return value(UUID(0, 0), trade, date, base, discounter(date)) { bookType }
    }

    private companion object {
        const val EUR = "EUR"
        val VALUED = setOf(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)
        const val MAX_TRADES = 100_000
    }
}
