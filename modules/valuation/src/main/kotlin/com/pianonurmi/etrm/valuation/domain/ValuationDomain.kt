package com.pianonurmi.etrm.valuation.domain

import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.ExplainRow
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** MtM of a remaining quantity: q × (market − contract), in contract currency and in base currency. */
object Mtm {
    fun value(quantity: BigDecimal, market: BigDecimal, contract: BigDecimal): BigDecimal = quantity * (market - contract)

    fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_EVEN)
}

/**
 * Explains the change in MtM between two runs, schedule by schedule, so that the components add up exactly:
 * - NEW and CANCELLED: schedules only in the later or only in the earlier run (a fully delivered schedule is DELIVERED);
 * - DELIVERED (same terms) or AMENDED (new trade version): the change in quantity and terms at the earlier market;
 * - PRICE: the move of the market against the contract at the earlier exchange rate;
 * - FX: the later value's change from the exchange rate to base;
 * - UNEXPLAINED: rounding, and schedules that could not be valued in one of the runs.
 */
object PnlExplainer {
    fun explain(
        prior: List<ValuationResultView>,
        current: List<ValuationResultView>,
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
                else -> decompose(r0, r1)
            }
            val rounded = parts.map { (c, a) -> c to a.setScale(2, RoundingMode.HALF_EVEN) }
            rounded.filter { it.second.signum() != 0 }.forEach { (c, a) -> rows += row(c, a) }
            val rest = v1 - v0 - rounded.fold(BigDecimal.ZERO) { s, p -> s + p.second }
            if (rest.signum() != 0) rows += row(ExplainComponent.UNEXPLAINED, rest)
        }
        return rows
    }

    private fun decompose(r0: ValuationResultView, r1: ValuationResultView): List<Pair<ExplainComponent, BigDecimal>> {
        val q0 = r0.quantity
        val q1 = r1.quantity
        val k0 = r0.contractPrice!!
        val k1 = r1.contractPrice!!
        val f0 = r0.marketPriceContract!!
        val f1 = r1.marketPriceContract!!
        val x0 = r0.fxToBase!!
        val x1 = r1.fxToBase!!
        val amended = r0.tradeVersion != r1.tradeVersion
        val v0 = q0 * (f0 - k0) * x0
        val atOldMarket = q1 * (f0 - (if (amended) k1 else k0)) * x0
        return listOf(
            (if (amended) ExplainComponent.AMENDED else ExplainComponent.DELIVERED) to atOldMarket - v0,
            ExplainComponent.PRICE to q1 * (f1 - k1) * x0 - atOldMarket,
            ExplainComponent.FX to q1 * (f1 - k1) * (x1 - x0),
        )
    }

    private fun valued(r: ValuationResultView) = r.status == ResultStatus.OK

    private fun BigDecimal?.orZero(): BigDecimal = this ?: BigDecimal.ZERO
}
