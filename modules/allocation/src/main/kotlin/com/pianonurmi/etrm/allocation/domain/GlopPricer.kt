package com.pianonurmi.etrm.allocation.domain

import com.google.ortools.linearsolver.MPConstraint
import com.google.ortools.linearsolver.MPSolver
import com.google.ortools.linearsolver.MPVariable
import com.pianonurmi.etrm.allocation.api.ObligationShadowPrice
import com.pianonurmi.etrm.allocation.api.PlanPricing
import com.pianonurmi.etrm.allocation.api.PricingStatus
import com.pianonurmi.etrm.allocation.api.SupplyShadowPrice
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/** Limits of the pricing LP; beyond them a plan has no shadow prices and says why. */
data class PricingSettings(val timeLimit: Duration = Duration.ofSeconds(5), val maxPairs: Int = 300_000)

/**
 * Shadow prices of an allocation (ADR 0035), from a linear program solved with GLOP after the plan:
 *
 *   minimise Σ cost(c, o) · x(c, o) over eligible pairs, x ≥ 0
 *   supply  Σ_o x(c, o) ≤ quantity(c)                         dual −rent(c)
 *   demand  Σ_c x(c, o) = covered(o), the plan's coverage      dual λ(o)
 *   device  Σ_{c of device} x(c, o) ≤ cap(o)
 *   share   Σ_{c matching} x(c, o) ≥ min(quota, the plan's own matching MWh)
 *
 * The plan's own solution is feasible, so the LP always has one. With coverage fixed, λ(o) is the marginal cost of
 * delivering obligation o, rerouting included, and rent(c) what one more MWh of class c would save. Duals are found
 * in floating point and converted at the boundary; a degenerate LP has many dual solutions, of which GLOP returns
 * one, so the figures are indicative.
 */
class GlopPricer(
    private val evaluate: (BoundCriterion, CriteriaSubject) -> Boolean,
    private val settings: PricingSettings = PricingSettings(),
) {
    /** The LP's solution in model terms, before aggregation; tests check it against LP duality. */
    internal class Duals(
        val status: PricingStatus,
        val reason: String?,
        val lpCost: Double,
        /** λ per obligation index (NaN for an obligation outside the LP). */
        val marginalCost: DoubleArray,
        /** Scarcity rent per class index, ≥ 0. */
        val rent: DoubleArray,
        /** LP quantity per (class index, obligation index). */
        val flow: Map<Pair<Int, Int>, Double>,
    )

    fun price(problem: AllocationProblem, assignments: List<Assignment>, bucketOf: (LotClass) -> String?): PlanPricing {
        val duals = solve(problem, assignments)
        if (duals.status != PricingStatus.OPTIMAL) return PlanPricing(duals.status, duals.reason)
        val covered = coverage(problem, assignments)
        val planCost = assignments.sumOf { it.score.total * BigDecimal(it.quantityMwh) }
        val lpCost = BigDecimal.valueOf(duals.lpCost)
        val obligations = problem.obligations.filter { !duals.marginalCost[it.index].isNaN() }.map { obl ->
            ObligationShadowPrice(
                obligationId = obl.id,
                coveredMwh = covered[obl.index],
                short = covered[obl.index] < obl.demand,
                marginalCostEur = perMwh(duals.marginalCost[obl.index]),
            )
        }.sortedWith(compareByDescending<ObligationShadowPrice> { it.marginalCostEur }.thenBy { it.obligationId })

        val used = LongArray(problem.classes.size)
        assignments.forEach { used[it.classIndex] += it.quantityMwh }
        val inModel = BooleanArray(problem.classes.size)
        duals.flow.keys.forEach { (c, _) -> inModel[c] = true }
        val supply = problem.classes.filter { inModel[it.index] }
            .groupBy { (bucketOf(it) ?: UNBUCKETED) to it.productionYear }
            .map { (key, classes) ->
                val quantity = classes.sumOf { it.quantity }
                val weighted = classes.sumOf { duals.rent[it.index] * it.quantity }
                SupplyShadowPrice(
                    bucketCode = key.first,
                    productionYear = key.second,
                    supplyMwh = quantity,
                    usedMwh = classes.sumOf { used[it.index] },
                    averageRentEur = perMwh(if (quantity > 0) weighted / quantity else 0.0),
                    maxRentEur = perMwh(classes.maxOf { duals.rent[it.index] }),
                )
            }
            .sortedWith(compareByDescending<SupplyShadowPrice> { it.maxRentEur }.thenBy { it.bucketCode }.thenBy { it.productionYear })
        return PlanPricing(
            status = PricingStatus.OPTIMAL,
            lpCostEur = lpCost.setScale(MONEY, RoundingMode.HALF_UP),
            planCostEur = planCost.setScale(MONEY, RoundingMode.HALF_UP),
            gapEur = (planCost - lpCost).setScale(MONEY, RoundingMode.HALF_UP).max(BigDecimal.ZERO.setScale(MONEY)),
            obligations = obligations,
            supply = supply,
        )
    }

    internal fun solve(problem: AllocationProblem, assignments: List<Assignment>): Duals {
        val solver = skipReason(problem, assignments)?.let { return skipped(it) }
            ?: runCatching { MPSolver.createSolver("GLOP") }.getOrElse { e ->
                if (e !is LinkageError) throw e
                OrTools.unusable(e)
                null
            }
            ?: return skipped("GLOP not available")
        try {
            return Model(solver, problem, problem.obligations.filter { it.demand > 0 }, assignments).run()
        } finally {
            solver.delete()
        }
    }

    private fun skipReason(problem: AllocationProblem, assignments: List<Assignment>): String? {
        val pairs = problem.obligations.filter { it.demand > 0 }.sumOf { problem.eligible[it.index].cardinality().toLong() }
        return when {
            assignments.isEmpty() -> "nothing allocated"
            !OrTools.available -> "native OR-Tools library not available"
            pairs > settings.maxPairs -> "problem too large ($pairs pairs, limit ${settings.maxPairs})"
            else -> null
        }
    }

    private fun skipped(reason: String) = Duals(PricingStatus.SKIPPED, reason, 0.0, DoubleArray(0), DoubleArray(0), emptyMap())

    private inner class Model(
        private val solver: MPSolver,
        private val problem: AllocationProblem,
        private val active: List<ObligationModel>,
        private val assignments: List<Assignment>,
    ) {
        private val covered = coverage(problem, assignments)
        private val supplyRows = HashMap<Int, MPConstraint>()
        private val demandRows = HashMap<Int, MPConstraint>()
        private val vars = HashMap<Pair<Int, Int>, MPVariable>()

        fun run(): Duals {
            val objective = solver.objective()
            active.forEach { obl ->
                val classes = problem.eligibleClasses(obl)
                if (classes.isEmpty()) return@forEach
                val demand = solver.makeConstraint(covered[obl.index].toDouble(), covered[obl.index].toDouble(), "d_${obl.index}")
                demandRows[obl.index] = demand
                val mine = classes.map { cls ->
                    // No upper bound: the supply and demand rows imply it, and a bound would take dual weight from them.
                    val x = solver.makeNumVar(0.0, MPSolver.infinity(), "x_${cls.index}_${obl.index}")
                    vars[cls.index to obl.index] = x
                    objective.setCoefficient(x, problem.score(cls, obl).total.toDouble())
                    demand.setCoefficient(x, 1.0)
                    supplyRows.getOrPut(cls.index) { solver.makeConstraint(0.0, cls.quantity.toDouble(), "s_${cls.index}") }.setCoefficient(x, 1.0)
                    cls to x
                }
                obl.maxSharePerDevice?.let { pct ->
                    val cap = (obl.demand * pct / 100).toDouble()
                    mine.groupBy { it.first.deviceGsrn }.values.forEach { group ->
                        val row = solver.makeConstraint(0.0, cap)
                        group.forEach { (_, x) -> row.setCoefficient(x, 1.0) }
                    }
                }
                obl.minShares.forEach { (bound, pct) ->
                    val quota = (obl.demand * pct + 99) / 100
                    val matching = mine.filter { evaluate(bound, it.first.representative) }
                    val achieved = assignments.filter { a -> a.obligationIndex == obl.index && matching.any { it.first.index == a.classIndex } }
                        .sumOf { it.quantityMwh }
                    val row = solver.makeConstraint(minOf(quota, achieved).toDouble(), MPSolver.infinity())
                    matching.forEach { (_, x) -> row.setCoefficient(x, 1.0) }
                }
            }
            objective.setMinimization()
            solver.setTimeLimit(settings.timeLimit.toMillis())
            val status = solver.solve()
            if (status != MPSolver.ResultStatus.OPTIMAL) {
                return Duals(PricingStatus.FAILED, "LP status $status", 0.0, DoubleArray(0), DoubleArray(0), emptyMap())
            }
            val marginal = DoubleArray(problem.obligations.size) { Double.NaN }
            demandRows.forEach { (o, row) -> marginal[o] = row.dualValue() }
            val rent = DoubleArray(problem.classes.size)
            // A binding supply row has a dual ≤ 0 in a minimisation: more supply lowers the cost.
            supplyRows.forEach { (c, row) -> rent[c] = maxOf(0.0, -row.dualValue()) }
            return Duals(PricingStatus.OPTIMAL, null, objective.value(), marginal, rent, vars.mapValues { it.value.solutionValue() })
        }
    }

    private fun coverage(problem: AllocationProblem, assignments: List<Assignment>): LongArray =
        LongArray(problem.obligations.size).also { covered -> assignments.forEach { covered[it.obligationIndex] += it.quantityMwh } }

    private fun perMwh(value: Double): BigDecimal = BigDecimal.valueOf(value).setScale(PRICE, RoundingMode.HALF_UP).let {
        // GLOP returns -0.0 and values like 1e-12 for zero.
        if (it.signum() == 0) BigDecimal.ZERO.setScale(PRICE) else it
    }

    private companion object {
        const val MONEY = 2
        const val PRICE = 4
        const val UNBUCKETED = "UNBUCKETED"
    }
}
