package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import java.math.BigDecimal
import java.util.BitSet

/** A lot with the quantity still available to a new plan (soft reservations of other plans deducted). */
data class AvailableLot(val lot: LotView, val availableMwh: Long)

/**
 * Lots that are interchangeable for every criterion and for scoring. Solvers work on classes (thousands) rather
 * than lots (hundreds of thousands) and disaggregate to concrete ranges only when a plan is written.
 */
class LotClass(val index: Int, val key: String, val lots: List<AvailableLot>) {
    val representative: LotView = lots.first().lot
    val quantity: Long = lots.sumOf { it.availableMwh }
    val registryCode: String get() = representative.registryCode
    val deviceGsrn: String get() = representative.deviceGsrn
    val expiryDate get() = representative.expiryDate
    val productionYear: Int get() = representative.productionPeriod.start.year
}

/** An obligation with its criteria bound for its own delivery date. */
class ObligationModel(
    val index: Int,
    val obligation: ObligationView,
    val bound: BoundCriterion,
    val minShares: List<Pair<BoundCriterion, Int>>,
    val maxSharePerDevice: Int?,
    val specificity: BigDecimal,
) {
    val id get() = obligation.id
    val demand: Long get() = obligation.remainingMwh
    val priority: Int get() = obligation.priorityClass
}

/** Everything a solver needs: classes, obligations, eligibility bitmaps and pairwise scores. */
class AllocationProblem(
    val classes: List<LotClass>,
    val obligations: List<ObligationModel>,
    /** Per obligation: bit `c` set when class `c` is eligible. */
    val eligible: List<BitSet>,
    private val scoring: (LotClass, ObligationModel) -> ScoreBreakdown,
) {
    /** Scores are not cached here: classes x obligations can reach tens of millions of pairs. Callers score per obligation. */
    fun score(cls: LotClass, obl: ObligationModel): ScoreBreakdown = scoring(cls, obl)

    fun eligibleClasses(obl: ObligationModel): List<LotClass> = eligible[obl.index].stream().mapToObj { classes[it] }.toList()

    fun eligibleSupply(obl: ObligationModel): Long = eligibleClasses(obl).sumOf { it.quantity }

    fun isEligible(cls: LotClass, obl: ObligationModel): Boolean = eligible[obl.index].get(cls.index)

    companion object {
        fun constraintsOf(obligation: ObligationView): Pair<List<AllocationConstraint.MinShare>, Int?> {
            val minShares = obligation.constraints.filterIsInstance<AllocationConstraint.MinShare>()
            val maxShare = obligation.constraints.filterIsInstance<AllocationConstraint.MaxSharePerDevice>().minOfOrNull { it.percent }
            return minShares to maxShare
        }
    }
}

/** Solver output: quantity of a class assigned to an obligation. */
data class Assignment(val classIndex: Int, val obligationIndex: Int, val quantityMwh: Long, val score: ScoreBreakdown)

interface Solver {
    val name: String

    fun solve(problem: AllocationProblem): List<Assignment>
}
