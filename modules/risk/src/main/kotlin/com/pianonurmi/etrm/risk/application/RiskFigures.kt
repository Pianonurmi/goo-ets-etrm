package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.PfeAddOnView
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.domain.CounterpartyGroups
import com.pianonurmi.etrm.risk.domain.MeasurementGaps
import com.pianonurmi.etrm.risk.domain.PotentialFutureExposure
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.risk.domain.Tenor
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.OptionValuation
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Trade statuses that count as open positions and exposures. */
private val OPEN = setOf(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)

internal const val PERCENT = 100
internal const val EUR = "EUR"
internal val GOO_LIMITS = setOf(LimitType.GOO_CONCENTRATION, LimitType.GOO_EXPIRY, LimitType.GOO_BASIS)

internal fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_EVEN)

/** The entity's active limits on [today], with those across all entities. */
internal fun RiskRepository.activeLimits(legalEntityId: UUID?, today: LocalDate): List<RiskLimitView> =
    limits(legalEntityId, includeGroupWide = true, statuses = setOf(LimitStatus.ACTIVE)).filter { it.validOn(today) }

/** Currency conversion for risk figures. */
@Component
class RiskFx(private val fxRates: FxRates) {
    private val log = LoggerFactory.getLogger(RiskFx::class.java)

    /** Converts at the rate of [date]. With no rate the amount is left out (zero) and [gaps] records the currency, so the figure is marked incomplete. */
    fun convert(amount: BigDecimal, from: String, to: String, date: LocalDate, gaps: MeasurementGaps): BigDecimal {
        if (from == to || amount.signum() == 0) return amount
        val rate = fxRates.rate(date, from, to)?.rate
        if (rate == null) {
            log.warn("No {}/{} rate on {}; {} {} left out of risk measurement", from, to, date, amount, from)
            gaps.missingRate(from, to, date)
            return BigDecimal.ZERO
        }
        return amount * rate
    }
}

/** Open trades, their net deliveries and tenor, and the inventory a position limit counts. */
@Component
class PositionFigures(
    private val trades: TradeQuery,
    private val valuations: ValuationQuery,
    private val referenceData: ReferenceDataQuery,
    private val holdings: AllowanceHoldingQuery,
    private val lots: LotQuery,
    private val savepoints: Savepoints,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PositionFigures::class.java)

    fun openTrades(legalEntityId: UUID, scope: LimitScope, exclude: UUID?): List<TradeView> =
        trades.trades(
            TradeFilter(legalEntityId = legalEntityId, bookId = scope.bookId, statuses = OPEN, productFamily = scope.productFamily, limit = Int.MAX_VALUE),
        )
            .filter { it.id != exclude }

    private fun legsInScope(trade: TradeView, scope: LimitScope): List<TradeLegView> =
        trade.legs.filter { scope.allowanceProductCode == null || it.allowanceProductCode == scope.allowanceProductCode }

    /**
     * Signed quantity still to deliver: purchases positive, sales negative. An option leg counts its delta-equivalent
     * (ADR 0047): quantity × delta, the full quantity when it cannot be valued, nothing once decided at expiry.
     */
    fun netDeliveries(trade: TradeView, scope: LimitScope, gaps: MeasurementGaps): BigDecimal = legsInScope(trade, scope).sumOf { leg ->
        val quantity = when {
            leg.option == null -> leg.deliveries.sumOf { it.remainingQuantity }
            leg.expiry != null -> BigDecimal.ZERO
            else -> deltaEquivalent(trade, leg, gaps)
        }
        if (leg.side == Side.BUY) quantity else quantity.negate()
    }

    /**
     * A call's tonnes times its delta (a put's delta is negative). Without a value the full quantity counts, the
     * conservative figure, and the measurement says that it is incomplete.
     */
    private fun deltaEquivalent(trade: TradeView, leg: TradeLegView, gaps: MeasurementGaps): BigDecimal {
        val delta = optionValuation(trade, leg, LocalDate.now(clock))?.delta
            ?: (if (checkNotNull(leg.option) { "Leg ${leg.id} is not an option" }.optionType == OptionType.CALL) BigDecimal.ONE else BigDecimal.ONE.negate())
                .also {
                    gaps.assumed("option of trade ${trade.tradeNumber} has no value: a delta of $it is assumed")
                }
        return leg.quantity * delta
    }

    /** Valued in a savepoint: a missing price or a role the valuation refuses must not fail the measuring transaction. */
    fun optionValuation(trade: TradeView, leg: TradeLegView, today: LocalDate): OptionValuation? =
        try {
            savepoints.attempt { valuations.optionValuation(trade.legalEntityId, today, leg) }
        } catch (e: RuntimeException) {
            log.warn("Option leg {} of trade {} could not be valued", leg.id, trade.tradeNumber, e)
            null
        }

    /** Net position: open trades plus inventory on accounts that belong to the book (or to the entity, without a book). */
    fun positionNet(legalEntityId: UUID, scope: LimitScope, exclude: UUID?, gaps: MeasurementGaps): BigDecimal {
        val open = openTrades(legalEntityId, scope, exclude).sumOf { netDeliveries(it, scope, gaps) }
        val inBook = { accountId: UUID -> scope.bookId == null || referenceData.registryAccount(accountId)?.defaultBookId == scope.bookId }
        val inventory = when (scope.productFamily) {
            ProductFamily.EMISSION_ALLOWANCE -> holdings.holdings(legalEntityId)
                .filter { (scope.allowanceProductCode == null || it.productCode == scope.allowanceProductCode) && inBook(it.registryAccountId) }
                .sumOf { it.quantity }.let(::BigDecimal)
            ProductFamily.GUARANTEE_OF_ORIGIN -> lots.lots(LotFilter(holderEntityId = legalEntityId, statuses = LotStatus.LIVE, limit = Int.MAX_VALUE))
                .filter { inBook(it.registryAccountId) }
                .sumOf { it.quantityMwh }.let(::BigDecimal)
            null -> BigDecimal.ZERO
        }
        return open + inventory
    }

    fun maxTenor(trades: List<TradeView>, scope: LimitScope, today: LocalDate): Long = trades.flatMap { legsInScope(it, scope) }
        .flatMap { leg ->
            // An option runs to its expiry; exercise creates the future, which has its own delivery.
            leg.option?.takeIf { leg.expiry == null }?.let { listOf(it.expiryDate) }
                ?: leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.map { it.deliveryDate }
        }
        .maxOfOrNull { Tenor.months(today, it) } ?: 0
}

/** Potential future exposure and the credit usage of a counterparty group. */
@Component
class CreditFigures(
    val positions: PositionFigures,
    private val curves: CurveQuery,
    private val fx: RiskFx,
    private val repository: RiskRepository,
    private val referenceData: ReferenceDataQuery,
) {
    /** Exposure to the limit's counterparty and its group, over the limit's entity or all entities, in the limit's currency. */
    fun creditUsage(limit: RiskLimitView, today: LocalDate, gaps: MeasurementGaps): Pair<BigDecimal, String?> {
        val members = CounterpartyGroups.members(checkNotNull(limit.scope.counterpartyId) { "Limit ${limit.id} has no counterparty" }, parentMap())
        val exposures = repository.exposures(limit.legalEntityId).filter { it.counterpartyId in members }
        // An exposure that left something out passes that on: the limit is only as complete as the figures under it.
        exposures.filter { it.incomplete }.forEach { gaps.addAll(it.leftOutCurrencies, it.incompleteReasons) }
        val total = exposures.sumOf { fx.convert(it.exposure, it.currency, limit.currencyCode(), today, gaps) }
        return total to "${exposures.size} exposures over ${members.size} counterparties"
    }

    fun parentMap(): Map<UUID, UUID?> = referenceData.counterparties().associate { it.id to it.parentCounterpartyId }

    /** Potential future exposure of a trade's remaining deliveries (an option's delta-equivalent), in [currency]. */
    fun pfe(trade: TradeView, addOns: List<PfeAddOnView>, currency: String, today: LocalDate, gaps: MeasurementGaps): BigDecimal =
        trade.legs.sumOf { leg ->
            leg.option?.let { terms ->
                val greeks = positions.optionValuation(trade, leg, today) ?: run {
                    gaps.omitted("option of trade ${trade.tradeNumber} has no value: its PFE is left out")
                    return@sumOf BigDecimal.ZERO
                }
                val notional = fx.convert(
                    (leg.quantity * greeks.delta * greeks.underlyingPrice).abs(),
                    leg.priceCurrency.currencyCode,
                    currency,
                    today,
                    gaps,
                )
                return@sumOf PotentialFutureExposure.addOn(
                    notional,
                    PotentialFutureExposure.pct(addOns, trade.productFamily, Tenor.months(today, terms.expiryDate)),
                )
            }
            leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.sumOf { schedule ->
                val price = unitPrice(trade, leg, schedule, today, gaps) ?: run {
                    gaps.omitted("no price for trade ${trade.tradeNumber} on ${schedule.deliveryDate}: its PFE is left out")
                    return@sumOf BigDecimal.ZERO
                }
                val notional = fx.convert(schedule.remainingQuantity * price, leg.priceCurrency.currencyCode, currency, today, gaps)
                PotentialFutureExposure.addOn(notional, PotentialFutureExposure.pct(addOns, trade.productFamily, Tenor.months(today, schedule.deliveryDate)))
            }
        }

    /** The leg's price per unit: fixed, or for index legs today's forward price plus the spread. */
    private fun unitPrice(trade: TradeView, leg: TradeLegView, schedule: DeliveryScheduleView, today: LocalDate, gaps: MeasurementGaps): BigDecimal? =
        when (leg.priceType) {
            PriceType.FIXED -> leg.price
            else -> when (trade.productFamily) {
                ProductFamily.EMISSION_ALLOWANCE -> leg.allowanceProductCode?.let { curves.forwardPrice(it, schedule.deliveryDate, today) }
                ProductFamily.GUARANTEE_OF_ORIGIN -> leg.bucketCode?.let { curves.vintagePrice(it, schedule.deliveryDate.year, today) }
            }?.let { fx.convert(it.value, it.currency, leg.priceCurrency.currencyCode, today, gaps) + leg.price }
        }
}
