package com.pianonurmi.etrm.allocation.domain

import com.google.ortools.graph.MinCostFlow
import com.google.ortools.graph.MinCostFlowBase
import java.math.RoundingMode
import java.util.IdentityHashMap

/**
 * The valuation-grade allocation of an entity's book (ADR 0039): the most coverage, higher priority first, at the
 * least cost, solved as a min-cost max-flow with costs in integer cents. Unlike the plan solvers it has no time
 * limit and no side constraints (device caps, minimum shares), so the same book always gives the same figures.
 *
 * Obligations that share their eligibility and their score key (delivery date and target registry) and priority
 * are one demand node: every class costs them the same. Flow into a node goes to its obligations by delivery date,
 * then id, each taking the cheapest classes first.
 */
object PortfolioValuer {
    /** Wider than any cost in cents per MWh, so a higher priority always wins over a cheaper certificate. */
    private const val PRIORITY_STEP = 1_000_000_000L

    /** Null when OR-Tools is not available: the portfolio has no value then, rather than a different one. */
    fun allocate(problem: AllocationProblem): List<Assignment>? {
        if (!OrTools.available) return null
        val obligations = problem.obligations.filter { it.demand > 0 }
        if (obligations.isEmpty() || problem.classes.isEmpty()) return emptyList()
        val groups = groups(problem, obligations)
        val classes = problem.classes.filter { it.quantity > 0 }
        val maxPriority = obligations.maxOf { it.priority }

        val flow = MinCostFlow()
        val source = 0
        val sink = 1
        val classNode = { i: Int -> 2 + i }
        val groupNode = { g: Int -> 2 + classes.size + g }
        // (class, group) arcs in a fixed order, so equal-cost solutions come out the same every time.
        val arcs = ArrayList<Triple<Int, Int, Int>>()
        classes.forEachIndexed { i, cls -> flow.addArcWithCapacityAndUnitCost(source, classNode(i), cls.quantity, 0) }
        groups.forEachIndexed { g, group ->
            val representative = group.first()
            val bonus = (maxPriority - representative.priority + 1) * PRIORITY_STEP
            classes.forEachIndexed { i, cls ->
                if (problem.isEligible(cls, representative)) {
                    val cents = problem.score(cls, representative).total.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
                    arcs += Triple(flow.addArcWithCapacityAndUnitCost(classNode(i), groupNode(g), group.sumOf { it.demand }, cents - bonus), i, g)
                }
            }
            flow.addArcWithCapacityAndUnitCost(groupNode(g), sink, group.sumOf { it.demand }, 0)
        }
        // The flow runs from nodes with supply to nodes with demand: the source holds every certificate, the sink wants every MWh.
        flow.setNodeSupply(source, classes.sumOf { it.quantity })
        flow.setNodeSupply(sink, -obligations.sumOf { it.demand })
        val status = try {
            flow.solveMaxFlowWithMinCost()
        } catch (e: LinkageError) {
            OrTools.unusable(e)
            return null
        }
        check(status == MinCostFlowBase.Status.OPTIMAL) { "Portfolio allocation did not solve: $status" }

        // Per group, the classes it took, cheapest first; then handed to its obligations in order.
        val taken = HashMap<Int, MutableList<Pair<LotClass, Long>>>()
        arcs.forEach { (arc, i, g) ->
            val mwh = flow.getFlow(arc)
            if (mwh > 0) taken.getOrPut(g) { ArrayList() } += classes[i] to mwh
        }
        return groups.flatMapIndexed { g, group ->
            val supply = (taken[g] ?: return@flatMapIndexed emptyList())
                .sortedWith(compareBy({ problem.score(it.first, group.first()).total }, { it.first.index }))
                .toMutableList()
            group.sortedWith(compareBy({ it.obligation.deliveryDate }, { it.id })).flatMap { obl ->
                var need = obl.demand
                val mine = ArrayList<Assignment>()
                val iterator = supply.listIterator()
                while (need > 0 && iterator.hasNext()) {
                    val (cls, left) = iterator.next()
                    val qty = minOf(need, left)
                    mine += Assignment(cls.index, obl.index, qty, problem.score(cls, obl))
                    need -= qty
                    if (qty == left) iterator.remove() else iterator.set(cls to left - qty)
                }
                mine
            }
        }
    }

    /** Obligations that are interchangeable for the flow: same priority, same eligible classes, same score key. */
    private fun groups(problem: AllocationProblem, obligations: List<ObligationModel>): List<List<ObligationModel>> {
        val byRow = IdentityHashMap<java.util.BitSet, LinkedHashMap<Any, MutableList<ObligationModel>>>()
        obligations.forEach { obl ->
            val key: Any = problem.scoreKey?.invoke(obl)?.let { it to obl.priority } ?: (obl.index to obl.priority)
            byRow.getOrPut(problem.eligible[obl.index]) { LinkedHashMap() }.getOrPut(key) { ArrayList() } += obl
        }
        return byRow.values.flatMap { it.values }.sortedBy { group -> group.minOf { it.index } }
    }
}
