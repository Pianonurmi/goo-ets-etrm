package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Deterministic greedy: most constrained obligation first (eligible supply divided by demand), then earliest
 * delivery, then highest static specificity; within an obligation the cheapest-to-deliver class first (EUR cost,
 * ADR 0035; equal costs go to the class fewer obligations compete for).
 * `MinShare` quotas are filled first from the restricted subset; `MaxSharePerDevice` is enforced as a running cap.
 */
class GreedySolver(private val evaluate: (BoundCriterion, CriteriaSubject) -> Boolean) : Solver {
    override val name: String = "GREEDY"

    override fun solve(problem: AllocationProblem): List<Assignment> {
        val remaining = LongArray(problem.classes.size) { problem.classes[it].quantity }
        val assignments = mutableListOf<Assignment>()
        val rankings = HashMap<Any, IntArray>()

        // Eligible supply per obligation, computed once (not inside the comparator); obligations sharing an
        // eligibility row share the sum.
        val supplyOfRow = java.util.IdentityHashMap<java.util.BitSet, Long>()
        val pressure = problem.obligations.filter { it.demand > 0 }.associateWith { obl ->
            val supply = supplyOfRow.getOrPut(problem.eligible[obl.index]) { problem.eligibleSupply(obl) }
            BigDecimal(supply).divide(BigDecimal(obl.demand), 6, RoundingMode.HALF_UP)
        }
        val order = pressure.keys.sortedWith(
            compareBy<ObligationModel> { pressure.getValue(it) }
                .thenBy { it.priority }.thenBy { it.obligation.deliveryDate }.thenByDescending { it.specificity }.thenBy { it.id },
        )

        order.forEach { obl ->
            var need = obl.demand
            val perDevice = HashMap<String, Long>()
            val deviceCap: Long? = obl.maxSharePerDevice?.let { pct -> obl.demand * pct / 100 }
            val scored = ranked(problem, obl, rankings)

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

    /**
     * The eligible classes of [obl], cheapest first (score, then class index). When the problem declares score keys,
     * all classes are ranked once per key and each obligation walks that ranking, skipping ineligible classes and
     * scoring only the classes it looks at; otherwise the obligation's eligible classes are scored and sorted.
     */
    private fun ranked(problem: AllocationProblem, obl: ObligationModel, rankings: HashMap<Any, IntArray>): Sequence<ScoredClass> {
        val keyOf = problem.scoreKey
            ?: return problem.eligibleClasses(obl).map { ScoredClass(it, problem.score(it, obl)) }
                .sortedWith(BY_COST)
                .asSequence()
        val order = rankings.getOrPut(keyOf(obl)) {
            problem.classes.map { ScoredClass(it, problem.score(it, obl)) }
                .sortedWith(BY_COST)
                .map { it.cls.index }
                .toIntArray()
        }
        val eligible = problem.eligible[obl.index]
        return order.asSequence().filter { eligible.get(it) }.map { ScoredClass(problem.classes[it], problem.score(problem.classes[it], obl)) }
    }

    /** Merges repeated (class, obligation) pairs produced by quota and fill passes. */
    private fun merge(assignments: List<Assignment>): List<Assignment> = assignments
        .groupBy { it.classIndex to it.obligationIndex }
        .map { (_, group) -> group.first().copy(quantityMwh = group.sumOf { it.quantityMwh }) }
        .sortedWith(compareBy({ it.obligationIndex }, { it.classIndex }))

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
}

private class ScoredClass(val cls: LotClass, val score: ScoreBreakdown)

/** Cheapest first; between equal costs the class fewer obligations compete for (lower scarcity), then the index. */
private val BY_COST = compareBy<ScoredClass> { it.score.total }.thenBy { it.score.scarcity }.thenBy { it.cls.index }
