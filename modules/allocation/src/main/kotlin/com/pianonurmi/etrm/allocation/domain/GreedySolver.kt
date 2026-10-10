package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Deterministic greedy: most constrained obligation first (eligible supply divided by demand), then earliest
 * delivery, then highest static specificity; within an obligation the cheapest-to-deliver class first.
 * `MinShare` quotas are filled first from the restricted subset; `MaxSharePerDevice` is enforced as a running cap.
 */
class GreedySolver(private val evaluate: (BoundCriterion, CriteriaSubject) -> Boolean) : Solver {
    override val name: String = "GREEDY"

    override fun solve(problem: AllocationProblem): List<Assignment> {
        val remaining = LongArray(problem.classes.size) { problem.classes[it].quantity }
        val assignments = mutableListOf<Assignment>()

        val order = problem.obligations
            .filter { it.demand > 0 }
            .sortedWith(
                compareBy<ObligationModel> { obl ->
                    val supply = problem.eligibleSupply(obl)
                    if (obl.demand == 0L) BigDecimal.ZERO else BigDecimal(supply).divide(BigDecimal(obl.demand), 6, RoundingMode.HALF_UP)
                }.thenBy { it.priority }.thenBy { it.obligation.deliveryDate }.thenByDescending { it.specificity }.thenBy { it.id },
            )

        order.forEach { obl ->
            var need = obl.demand
            val perDevice = HashMap<String, Long>()
            val deviceCap: Long? = obl.maxSharePerDevice?.let { pct -> obl.demand * pct / 100 }
            // Score each eligible class once for this obligation; the list is released before the next obligation.
            val scored = problem.eligibleClasses(obl).map { ScoredClass(it, problem.score(it, obl)) }
                .sortedWith(compareBy<ScoredClass> { it.score.total }.thenBy { it.cls.index })

            fun take(scoredClass: ScoredClass, maxQty: Long): Long {
                val cls = scoredClass.cls
                val capLeft = deviceCap?.let { it - (perDevice[cls.deviceGsrn] ?: 0L) } ?: Long.MAX_VALUE
                val qty = minOf(maxQty, remaining[cls.index], capLeft, need)
                if (qty <= 0) return 0
                remaining[cls.index] -= qty
                need -= qty
                perDevice.merge(cls.deviceGsrn, qty, Long::plus)
                assignments += Assignment(cls.index, obl.index, qty, scoredClass.score)
                return qty
            }

            obl.minShares.forEach { (bound, pct) ->
                val quota = ceilDiv(obl.demand * pct, 100)
                var taken = assignments.filter {
                    it.obligationIndex == obl.index && evaluate(bound, problem.classes[it.classIndex].representative)
                }.sumOf { it.quantityMwh }
                for (sc in scored) {
                    if (taken >= quota || need == 0L) break
                    if (!evaluate(bound, sc.cls.representative)) continue
                    taken += take(sc, quota - taken)
                }
            }
            for (sc in scored) {
                if (need == 0L) break
                take(sc, need)
            }
        }
        return merge(assignments)
    }

    /** Merges repeated (class, obligation) pairs produced by quota and fill passes. */
    private fun merge(assignments: List<Assignment>): List<Assignment> = assignments
        .groupBy { it.classIndex to it.obligationIndex }
        .map { (_, group) -> group.first().copy(quantityMwh = group.sumOf { it.quantityMwh }) }
        .sortedWith(compareBy({ it.obligationIndex }, { it.classIndex }))

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
}

private class ScoredClass(val cls: LotClass, val score: ScoreBreakdown)
