package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.BucketSurplus
import com.pianonurmi.etrm.allocation.api.CoverageReport
import com.pianonurmi.etrm.allocation.api.ObligationCoverage
import com.pianonurmi.etrm.allocation.api.ShortageGroup
import com.pianonurmi.etrm.allocation.domain.flow.CompressedFlowGraph
import com.pianonurmi.etrm.allocation.domain.flow.FlowDemand
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.allocation.domain.flow.SupplyUnit
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

/**
 * Coverage: a max-flow between lot classes (supply) and obligations (demand) over the eligibility graph.
 * Obligations are attached to the sink one priority tier at a time and the flow is augmented after each tier, so
 * flow already granted to a higher-priority tier is never taken away (augmenting paths can reroute it but never
 * reduce it) while the final flow is still a global maximum. The flow runs on the compressed graph (ADR 0036):
 * obligations with identical eligible supply share one node, and the node's flow goes to them by delivery date, then
 * id. Shortage groups are the connected components of obligations left short together with the
 * (exhausted) classes they compete for; they explain the shortfall.
 *
 * Side constraints (share caps) are not part of the flow, so coverage is an upper bound on what a plan achieves.
 */
class CoverageAnalyser(private val bucketOf: (LotClass) -> String?) {
    /** The compressed graph and its solved flow, for callers that need more than the report (benchmarks, tests). */
    fun solve(problem: AllocationProblem): FlowState {
        val units = problem.classes.map { SupplyUnit.Held(it.index, it.quantity) }
        val demands = problem.obligations
            .sortedWith(compareBy<ObligationModel> { it.priority }.thenBy { it.obligation.deliveryDate }.thenBy { it.id })
            .map { FlowDemand(it.index, it.priority, it.demand) }
        val graph = CompressedFlowGraph.build(
            units = units,
            demands = demands,
            obligationCount = problem.obligations.size,
            eligibleUnits = { problem.eligible[it] },
            // Surplus is reported per (bucket, year), so supply of different buckets or years never merges.
            atomKey = { u -> problem.classes[u].let { cls -> (bucketOf(cls) ?: UNBUCKETED) to cls.productionYear } },
        )
        return FlowState(graph)
    }

    fun analyse(problem: AllocationProblem, holderEntityId: UUID, snapshotSeq: Long, asOf: Instant): CoverageReport {
        val state = solve(problem)
        val covered = state.obligationCoverage(problem.obligations.size)
        val coverage = problem.obligations.map { obl ->
            ObligationCoverage(
                obligationId = obl.id,
                demandMwh = obl.demand,
                coveredMwh = covered[obl.index],
                eligibleSupplyMwh = problem.eligibleSupply(obl),
                eligibleClassCount = problem.eligible[obl.index].cardinality(),
            )
        }

        val reachableGroups = state.reachableGroups()
        val reachable = BooleanArray(problem.obligations.size) { reachableGroups[state.graph.groupOfObligation[it]] }
        val groups = shortageGroups(problem, reachable, coverage)

        val surplus = state.graph.atoms
            .map { atom -> Triple(atom.key as Pair<*, *>, atom, state.atomResidual(atom.index)) }
            .filter { it.third > 0 }
            .groupBy { it.first }
            .map { (key, items) -> BucketSurplus(key.first as String, key.second as Int, items.sumOf { it.third }) }
            .sortedWith(compareBy({ it.bucketCode }, { it.productionYear }))

        return CoverageReport(
            holderEntityId = holderEntityId,
            snapshotSeq = snapshotSeq,
            asOf = asOf,
            totalDemandMwh = coverage.sumOf { it.demandMwh },
            totalCoveredMwh = coverage.sumOf { it.coveredMwh },
            obligations = coverage,
            shortageGroups = groups,
            surplusByBucket = surplus,
        )
    }

    /**
     * Obligations left short are unreachable from the source in the residual graph (otherwise an augmenting path would
     * exist). A shortage group is a connected component of unreachable obligations, covered or not, together with the
     * exhausted classes they compete for: it explains both the shortfall and where the supply went. Groups without
     * shortfall are not reported.
     */
    private fun shortageGroups(
        problem: AllocationProblem,
        reachable: BooleanArray,
        coverage: List<ObligationCoverage>,
    ): List<ShortageGroup> {
        val short = problem.obligations.filter { !reachable[it.index] }
        val seen = HashSet<Int>()
        return short.mapNotNull { start ->
            if (!seen.add(start.index)) return@mapNotNull null
            val members = mutableListOf<ObligationModel>()
            val classes = HashSet<Int>()
            val queue = ArrayDeque<ObligationModel>().apply { add(start) }
            while (queue.isNotEmpty()) {
                val obl = queue.poll()
                members += obl
                val newClasses = problem.eligible[obl.index].stream().filter { classes.add(it) }.toArray()
                short.filter { it.index !in seen && newClasses.any { ci -> problem.eligible[it.index].get(ci) } }.forEach {
                    seen.add(it.index)
                    queue.add(it)
                }
            }
            ShortageGroup(
                obligationIds = members.map { it.id }.sortedBy { it.toString() },
                demandMwh = members.sumOf { it.demand },
                eligibleSupplyMwh = classes.sumOf { problem.classes[it].quantity },
                shortfallMwh = members.sumOf { coverage[it.index].shortfallMwh },
            )
        }.filter { it.shortfallMwh > 0 }.sortedByDescending { it.shortfallMwh }
    }

    private companion object {
        const val UNBUCKETED = "UNBUCKETED"
    }
}
