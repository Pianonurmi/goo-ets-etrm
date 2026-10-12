package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.ObligationValue
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.PlanValue
import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import java.math.BigDecimal
import java.math.RoundingMode

/** A plan's lines added up in EUR (ADR 0035). */
object PlanValuation {
    private const val MONEY = 2
    private const val PRICE = 4

    /** Null when any line was scored before ADR 0035 (unit `SCORE`): those figures are not money. */
    fun of(lines: List<AllocationLineView>, obligations: List<ObligationView>): PlanValue? {
        if (lines.any { it.breakdown.unit != ScoreBreakdown.EUR_PER_MWH }) return null
        val byId = obligations.associateBy { it.id }
        val perObligation = lines.groupBy { it.obligationId }.map { (obligationId, mine) ->
            val mwh = mine.sumOf { it.quantityMwh }
            val delivered = mine.sumOf { it.breakdown.opportunityCost * BigDecimal(it.quantityMwh) }
            val saleMark = mine.firstNotNullOfOrNull { it.breakdown.saleMarkEur }
            ObligationValue(
                obligationId = obligationId,
                saleBucketCode = byId[obligationId]?.bucketCode,
                saleMarkEur = saleMark?.setScale(PRICE, RoundingMode.HALF_UP),
                mwh = mwh,
                deliveredValueEur = delivered.setScale(MONEY, RoundingMode.HALF_UP),
                averageDeliveredMarkEur = if (mwh > 0) delivered.divide(BigDecimal(mwh), PRICE, RoundingMode.HALF_UP) else null,
                giveAwayEur = if (saleMark == null) {
                    null
                } else {
                    mine.sumOf { (it.breakdown.giveAwayPerMwh ?: BigDecimal.ZERO) * BigDecimal(it.quantityMwh) }.setScale(MONEY, RoundingMode.HALF_UP)
                },
            )
        }.sortedWith(compareByDescending<ObligationValue> { it.giveAwayEur ?: BigDecimal.ZERO }.thenBy { it.obligationId })

        fun total(of: (ScoreBreakdown) -> BigDecimal) = lines.sumOf { of(it.breakdown) * BigDecimal(it.quantityMwh) }.setScale(MONEY, RoundingMode.HALF_UP)
        return PlanValue(
            currency = "EUR",
            totalCostEur = total { it.total },
            deliveredValueEur = total { it.opportunityCost },
            expiryHaircutEur = total { it.urgencyBonus },
            transferFeesEur = total { it.transferCost },
            giveAwayEur = total { it.giveAwayPerMwh ?: BigDecimal.ZERO },
            unpricedMwh = lines.filterNot { it.breakdown.priced }.sumOf { it.quantityMwh },
            byObligation = perObligation,
        )
    }
}
