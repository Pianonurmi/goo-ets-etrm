package com.pianonurmi.etrm.valuation.domain

import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.ExplainRow
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * MtM of a remaining quantity: q × (market − contract) × DF, in contract currency and in base currency, where DF is
 * the discount factor to the payment date (ADR 0044; 1 when not discounted).
 */
object Mtm {
    fun value(quantity: BigDecimal, market: BigDecimal, contract: BigDecimal, discountFactor: BigDecimal = BigDecimal.ONE): BigDecimal =
        quantity * (market - contract) * discountFactor

    fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_EVEN)
}

/**
 * Explains the change in MtM between two runs, schedule by schedule, so that the components add up exactly:
 * - NEW and CANCELLED: schedules only in the later or only in the earlier run (a fully delivered schedule is DELIVERED);
 * - DELIVERED (same terms) or AMENDED (new trade version): the change in quantity and terms at the earlier market;
 * - TIME: the earlier discount curve seen from the later date, a step closer to payment (ADR 0044);
 * - PRICE: the move of the market against the contract at the earlier exchange rate and the rolled discount factor;
 * - RATES: the move from the rolled earlier discount factor to the later one;
 * - FX: the later value's change from the exchange rate to base;
 * - UNEXPLAINED: rounding, and schedules that could not be valued in one of the runs.
 *
 * An option is not linear in its inputs, so it is revalued by Black-76 one input at a time, in the order terms, time
 * (years to expiry and the rolled discount factor), price (the underlying), VOL, rates and FX (ADR 0047); each step is
 * the change of value from the previous one, so the steps add up by construction.
 *
 * [rolled] gives the earlier run's discount factor for the later result's payment, seen from the later date (1 when
 * the earlier result was not discounted).
 */
object PnlExplainer {
    fun explain(
        prior: List<ValuationResultView>,
        current: List<ValuationResultView>,
        rolled: (prior: ValuationResultView, current: ValuationResultView) -> BigDecimal = { _, _ -> BigDecimal.ONE },
        cancelled: (tradeId: UUID) -> Boolean,
    ): List<ExplainRow> {
        val before = prior.associateBy { it.scheduleId }
        val after = current.associateBy { it.scheduleId }
        val rows = mutableListOf<ExplainRow>()
        (before.keys + after.keys).forEach { id ->
            val r0 = before[id]
            val r1 = after[id]
            val v0 = r0?.mtmBase.orZero()
            val v1 = r1?.mtmBase.orZero()
            val ref = r1 ?: r0!!

            fun row(component: ExplainComponent, amount: BigDecimal) = ExplainRow(id, ref.tradeId, ref.bookId, component, amount)
            val parts: List<Pair<ExplainComponent, BigDecimal>> = when {
                r0 == null -> listOf(ExplainComponent.NEW to v1)
                r1 == null -> listOf((if (cancelled(r0.tradeId)) ExplainComponent.CANCELLED else ExplainComponent.DELIVERED) to v0.negate())
                !valued(r0) || !valued(r1) -> emptyList()
                r0.option != null && r1.option != null -> decomposeOption(r0, r1, rolled(r0, r1))
                else -> decompose(r0, r1, rolled(r0, r1))
            }
            val rounded = parts.map { (c, a) -> c to a.setScale(2, RoundingMode.HALF_EVEN) }
            rounded.filter { it.second.signum() != 0 }.forEach { (c, a) -> rows += row(c, a) }
            val rest = v1 - v0 - rounded.fold(BigDecimal.ZERO) { s, p -> s + p.second }
            if (rest.signum() != 0) rows += row(ExplainComponent.UNEXPLAINED, rest)
        }
        return rows
    }

    /** The prices a valued result carries ([valued] has checked them). */
    private data class Priced(val contract: BigDecimal, val market: BigDecimal, val fx: BigDecimal)

    private fun priced(r: ValuationResultView) = Priced(
        checkNotNull(r.contractPrice) { "Result of schedule ${r.scheduleId} has no contract price" },
        checkNotNull(r.marketPriceContract) { "Result of schedule ${r.scheduleId} has no market price" },
        checkNotNull(r.fxToBase) { "Result of schedule ${r.scheduleId} has no FX rate" },
    )

    private fun decompose(r0: ValuationResultView, r1: ValuationResultView, d0r: BigDecimal): List<Pair<ExplainComponent, BigDecimal>> {
        val q0 = r0.quantity
        val q1 = r1.quantity
        val (k0, f0, x0) = priced(r0)
        val (k1, f1, x1) = priced(r1)
        val d0 = r0.discountFactor ?: BigDecimal.ONE
        val d1 = r1.discountFactor ?: BigDecimal.ONE
        val amended = r0.tradeVersion != r1.tradeVersion
        val v0 = q0 * (f0 - k0) * x0 * d0
        val oldMarket = q1 * (f0 - (if (amended) k1 else k0)) * x0
        val newMarket = q1 * (f1 - k1) * x0
        return listOf(
            (if (amended) ExplainComponent.AMENDED else ExplainComponent.DELIVERED) to oldMarket * d0 - v0,
            ExplainComponent.TIME to oldMarket * (d0r - d0),
            ExplainComponent.PRICE to (newMarket - oldMarket) * d0r,
            ExplainComponent.RATES to newMarket * (d1 - d0r),
            ExplainComponent.FX to q1 * (f1 - k1) * (x1 - x0) * d1,
        )
    }

    private fun decomposeOption(r0: ValuationResultView, r1: ValuationResultView, d0r: BigDecimal): List<Pair<ExplainComponent, BigDecimal>> {
        val o0 = checkNotNull(r0.option) { "Result of schedule ${r0.scheduleId} is not an option" }
        val o1 = checkNotNull(r1.option) { "Result of schedule ${r1.scheduleId} is not an option" }
        val p0 = priced(r0)
        val p1 = priced(r1)
        val right = OptionRight.valueOf(o1.optionType)

        // The later terms valued at the given market, less the later premium, in the earlier exchange rate.
        fun at(forward: BigDecimal, vol: BigDecimal, years: BigDecimal, df: BigDecimal): BigDecimal =
            r1.quantity * (Black76.value(right, forward, o1.strike, vol.movePointLeft(2), years, df).price - p1.contract) * p0.fx
        val d0 = r0.discountFactor ?: BigDecimal.ONE
        val v0 = r0.quantity * (p0.market - p0.contract) * p0.fx
        val terms = at(o0.underlyingPrice, o0.volatility, o0.timeToExpiry, d0)
        val time = at(o0.underlyingPrice, o0.volatility, o1.timeToExpiry, d0r)
        val price = at(o1.underlyingPrice, o0.volatility, o1.timeToExpiry, d0r)
        val vol = at(o1.underlyingPrice, o1.volatility, o1.timeToExpiry, d0r)
        val rates = r1.quantity * (p1.market - p1.contract) * p0.fx
        val fx = r1.quantity * (p1.market - p1.contract) * p1.fx
        return listOf(
            (if (r0.tradeVersion != r1.tradeVersion) ExplainComponent.AMENDED else ExplainComponent.DELIVERED) to terms - v0,
            ExplainComponent.TIME to time - terms,
            ExplainComponent.PRICE to price - time,
            ExplainComponent.VOL to vol - price,
            ExplainComponent.RATES to rates - vol,
            ExplainComponent.FX to fx - rates,
        )
    }

    private fun valued(r: ValuationResultView) = r.status == ResultStatus.OK

    private fun BigDecimal?.orZero(): BigDecimal = this ?: BigDecimal.ZERO
}
