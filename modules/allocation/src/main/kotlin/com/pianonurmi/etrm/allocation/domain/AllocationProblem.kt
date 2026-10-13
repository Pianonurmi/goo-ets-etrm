package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.allocation.api.SupplyTier
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
class LotClass(val index: Int, val key: String, val lots: List<AvailableLot>, val future: FutureSupply? = null) {
    val representative: LotView = lots.first().lot

    /** Held lots, or supply still to come (ADR 0038): a purchase or a device's expected issuance. */
    val tier: SupplyTier get() = future?.tier ?: SupplyTier.HELD
    val quantity: Long = lots.sumOf { it.availableMwh }
    val registryCode: String get() = representative.registryCode

    /** The registry certificates leave from to reach [target]; future supply of unknown registry arrives where needed. */
    fun sourceRegistry(target: String?): String =
        if (future != null && registryCode == FutureSupply.UNKNOWN_REGISTRY) target ?: registryCode else registryCode

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

    /** The same obligation at another position (a problem that leaves some obligations out). */
    fun reindexed(index: Int): ObligationModel = ObligationModel(index, obligation, bound, minShares, maxSharePerDevice, specificity)
}

/** Everything a solver needs: classes, obligations, eligibility bitmaps and pairwise scores. */
class AllocationProblem(
    val classes: List<LotClass>,
    val obligations: List<ObligationModel>,
    /** Per obligation: bit `c` set when class `c` is eligible. */
    val eligible: List<BitSet>,
    private val scoring: (LotClass, ObligationModel) -> ScoreBreakdown,
    /**
     * Obligations with equal score keys give every class the same score (the score depends on the class and on the
     * key only). Solvers can then rank all classes once per key instead of once per obligation. Null when unknown:
     * every obligation is scored on its own.
     */
    val scoreKey: ((ObligationModel) -> Any)? = null,
    /**
     * Per class, a profile id such that classes with the same profile are eligible for exactly the same obligations
     * (ADR 0043); the flow then compresses profiles instead of classes. Null when unknown.
     */
    val profiles: IntArray? = null,
    /**
     * The order classes enter the flow in, so equal problems give the same graph however their indexes were assigned
     * (a patched index appends classes; ADR 0043). Null: index order.
     */
    val unitOrder: IntArray? = null,
) {
    /** Scores are not cached here: classes x obligations can reach tens of millions of pairs. Callers score per obligation. */
    fun score(cls: LotClass, obl: ObligationModel): ScoreBreakdown = scoring(cls, obl)

    fun eligibleClasses(obl: ObligationModel): List<LotClass> = eligible[obl.index].stream().mapToObj { classes[it] }.toList()

    /** Obligations share eligibility rows, so the supply of a row is summed once (classes and rows never change). */
    private val supplyOfRow: MutableMap<BitSet, Long> = java.util.Collections.synchronizedMap(java.util.IdentityHashMap())

    fun eligibleSupply(obl: ObligationModel): Long = eligible[obl.index].let { row ->
        supplyOfRow.getOrPut(row) { row.stream().mapToLong { classes[it].quantity }.sum() }
    }

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

/** A solver's assignments, the solver that actually produced them, and what it reports (status, fallback reason). */
data class SolveResult(val assignments: List<Assignment>, val solver: String, val details: Map<String, Any?> = emptyMap())

interface Solver {
    val name: String

    fun solve(problem: AllocationProblem): List<Assignment>

    fun solveDetailed(problem: AllocationProblem): SolveResult = SolveResult(solve(problem), name)
}
