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
 * Values the remaining quantity of a trade per delivery schedule against official curves, discounted to the payment
 * date (ADR 0044): MtM = q × (market − contract) × DF, q signed by side, the market price converted into the contract
 * currency and the result into the entity's base currency at the valuation date's rates. DF comes from the contract
 * currency's official discount curve (1 without one); futures are margined daily and not discounted. Index legs not
 * yet fixed are worth their spread; formula legs are left out. Options are valued by Black-76 until they expire (ADR
 * 0047).
 */
@Component
class TradeValuer(
    private val curves: CurveQuery,
    private val fx: FxRates,
    private val fixings: IndexFixings,
    private val discounting: DiscountFactors,
    private val criteria: CriteriaService,
    /** Underlying futures of options. */
    private val futures: FuturesContractQuery,
) {
    /** Discount factors of one valuation date, read once per currency and payment date. */
    fun discounter(date: LocalDate): (String, LocalDate) -> DiscountFactor {
        val memo = HashMap<Pair<String, LocalDate>, DiscountFactor>()
        return { currency, payDate -> memo.getOrPut(currency to payDate) { discounting.discountFactor(currency, payDate, date) } }
    }

    /** The earlier result's discount curve seen from [date], for the later result's payment: the explain's TIME step. */
    fun roller(date: LocalDate): (ValuationResultView, ValuationResultView) -> BigDecimal {
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

    fun value(
        runId: UUID,
        trade: TradeView,
        date: LocalDate,
        base: String,
        discount: (String, LocalDate) -> DiscountFactor,
        bookType: (UUID) -> BookType,
    ): List<ValuationResultView> =
        trade.legs.flatMap { leg ->
            val sign = if (leg.side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
            val option = leg.option
            if (option != null) {
                // An option is valued until it expires, on its expiry date at its payoff, and no longer once decided.
                return@flatMap if (option.expiryDate.isBefore(date) || leg.expiry != null) {
                    emptyList()
                } else {
                    val draft = draft(runId, trade, leg, leg.id, option.expiryDate, leg.quantity * sign, option.expiryDate, bookType)
                    listOf(price(draft, trade, leg, date, base, discount))
                }
            }
            leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.map { schedule ->
                val payDate = schedule.paymentDate ?: schedule.deliveryDate
                val draft = draft(runId, trade, leg, schedule.id, schedule.deliveryDate, schedule.remainingQuantity * sign, payDate, bookType)
                price(draft, trade, leg, date, base, discount)
            }
        }

    @Suppress("LongParameterList")
    private fun draft(
        runId: UUID,
        trade: TradeView,
        leg: TradeLegView,
        scheduleId: UUID,
        deliveryDate: LocalDate,
        quantity: BigDecimal,
        payDate: LocalDate,
        bookType: (UUID) -> BookType,
    ) = ValuationResultView(
        runId = runId,
        scheduleId = scheduleId,
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
        deliveryDate = deliveryDate,
        quantity = quantity,
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
        payDate = payDate,
    )

    /** A schedule that cannot be valued, with the reason. */
    class Unpriced(val status: ResultStatus, val year: Int?, reason: String) : RuntimeException(reason)

    private fun price(
        draft: ValuationResultView,
        trade: TradeView,
        leg: TradeLegView,
        date: LocalDate,
        base: String,
        discount: (String, LocalDate) -> DiscountFactor,
    ): ValuationResultView = try {
        leg.option?.let { pricedOption(draft, leg, it, date, base, discount) } ?: priced(draft, trade, leg, date, base, discount)
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
            // An index leg pays its fixing plus the spread; the forward price stands in for a fixing still to come.
            else -> indexPrice(leg, draft.deliveryDate, date, year, marketContract) + leg.price
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

    /**
     * An option leg by Black-76 (ADR 0047): the underlying future's forward price from the allowance curve, the
     * volatility for the expiry, the years to expiry (ACT/365) and the discount factor to expiry. Its market price is
     * the option's value per tonne, its contract price the premium paid: MtM = q × (value − premium).
     */
    fun pricedOption(
        draft: ValuationResultView,
        leg: TradeLegView,
        terms: OptionTerms,
        date: LocalDate,
        base: String,
        discount: (String, LocalDate) -> DiscountFactor,
    ): ValuationResultView {
        val product = leg.allowanceProductCode!!
        val underlying = futures.futuresContract(terms.underlyingContractCode)
            ?: throw Unpriced(ResultStatus.MISSING_PRICE, null, "Underlying contract ${terms.underlyingContractCode} not found")
        val forward = curves.forwardPrice(product, underlying.deliveryDate, date)
            ?: throw Unpriced(ResultStatus.MISSING_PRICE, null, "No official curve for $product on or before $date")
        val vol = curves.volatility(product, terms.expiryDate, date)
            ?: throw Unpriced(ResultStatus.MISSING_PRICE, null, "No official volatility curve for $product on or before $date")
        val toContract = fx.rate(date, forward.currency, draft.contractCurrency)?.rate
            ?: throw Unpriced(ResultStatus.MISSING_FX, null, "No ${forward.currency}/${draft.contractCurrency} rate on $date")
        val toBase = fx.rate(date, draft.contractCurrency, base)?.rate
            ?: throw Unpriced(ResultStatus.MISSING_FX, null, "No ${draft.contractCurrency}/$base rate on $date")
        val underlyingPrice = (forward.value * toContract).setScale(6, RoundingMode.HALF_EVEN)
        val years = BigDecimal(ChronoUnit.DAYS.between(date, terms.expiryDate)).divide(DAYS_A_YEAR, YEAR_SCALE, RoundingMode.HALF_EVEN)
        val df = discount(draft.contractCurrency, terms.expiryDate)
        val right = OptionRight.valueOf(terms.optionType.name)
        val value = Black76.value(right, underlyingPrice, terms.strike, vol.value.movePointLeft(2), years, df.value)
        val undiscounted = Black76.value(right, underlyingPrice, terms.strike, vol.value.movePointLeft(2), years, BigDecimal.ONE)
        val mtm = Mtm.value(draft.quantity, value.price, leg.price)
        val curveNote = listOfNotNull(
            forward.curveDate.takeIf { it != date }?.let { "Curve of $it" },
            vol.curveDate.takeIf { it != date }?.let { "Volatility of $it" },
        ).joinToString("; ").ifEmpty { null }
        return draft.copy(
            contractPrice = leg.price,
            marketPrice = value.price,
            marketCurrency = draft.contractCurrency,
            priceOrigin = forward.origin.name,
            curveCode = forward.curveCode,
            snapshotId = forward.snapshotId,
            curveDate = forward.curveDate,
            marketPriceContract = value.price,
            fxToBase = toBase,
            mtm = Mtm.money(mtm),
            mtmBase = Mtm.money(mtm * toBase),
            note = curveNote,
            discountFactor = df.value,
            discountCurveCode = df.curveCode,
            discountSnapshotId = df.snapshotId,
            mtmUndiscounted = Mtm.money(Mtm.value(draft.quantity, undiscounted.price, leg.price)),
            option = OptionValuation(
                optionType = terms.optionType.name,
                strike = terms.strike,
                underlyingContractCode = underlying.code,
                underlyingPrice = underlyingPrice,
                volatility = vol.value,
                timeToExpiry = years,
                delta = value.delta,
                gamma = value.gamma,
                vega = value.vega,
                volCurveCode = vol.curveCode,
                volSnapshotId = vol.snapshotId,
            ),
        )
    }

    /**
     * The fixing of an index leg's delivery. Until the day after delivery the forward price stands in for a fixing not
     * yet published; after that the fixing is overdue, and without it the leg is not valued at the forward.
     */
    private fun indexPrice(leg: TradeLegView, deliveryDate: LocalDate, date: LocalDate, year: Int?, forward: BigDecimal): BigDecimal =
        fixedIndex(leg, deliveryDate, date)
            ?: if (deliveryDate.isBefore(date)) {
                throw Unpriced(ResultStatus.MISSING_PRICE, year, "No fixing of ${leg.priceIndex ?: "an index"} for $deliveryDate")
            } else {
                forward
            }

    private fun fixedIndex(leg: TradeLegView, deliveryDate: LocalDate, date: LocalDate): BigDecimal? =
        leg.priceIndex?.takeIf { !deliveryDate.isAfter(date) }?.let { fixings.fixing(it, deliveryDate)?.value }

    fun forwardValue(base: String, date: LocalDate, terms: ForwardTerms): ForwardValue? {
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

    fun optionValuation(date: LocalDate, leg: TradeLegView): OptionValuation? {
        val terms = leg.option?.takeIf { !it.expiryDate.isBefore(date) && leg.expiry == null } ?: return null
        val currency = leg.priceCurrency.currencyCode
        val draft = ValuationResultView(
            runId = UUID(0, 0),
            scheduleId = leg.id,
            tradeId = UUID(0, 0),
            tradeNumber = "",
            tradeVersion = 0,
            legNo = leg.legNo,
            bookId = UUID(0, 0),
            bookType = BookType.TRADING,
            counterpartyId = UUID(0, 0),
            productFamily = ProductFamily.EMISSION_ALLOWANCE,
            productKey = leg.allowanceProductCode,
            productionYear = null,
            deliveryDate = terms.expiryDate,
            quantity = leg.quantity,
            contractPrice = null,
            contractCurrency = currency,
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
        return try {
            pricedOption(draft, leg, terms, date, currency, discounter(date)).option
        } catch (_: Unpriced) {
            null
        }
    }

    private companion object {
        val DAYS_A_YEAR = BigDecimal(365)
        const val YEAR_SCALE = 8
    }
}
