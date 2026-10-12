package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.BucketSurplus
import com.pianonurmi.etrm.allocation.api.CoverageReport
import com.pianonurmi.etrm.allocation.api.ObligationCoverage
import com.pianonurmi.etrm.allocation.api.ShortageGroup
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.domain.flow.CompressedFlowGraph
import com.pianonurmi.etrm.allocation.domain.flow.FlowDemand
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.allocation.domain.flow.SupplyUnit
import java.time.Instant
import java.util.ArrayDeque
import java.util.BitSet
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
    /**
     * The compressed graph and its solved flow, for callers that need more than the report (benchmarks, tests), with
     * supply up to [maxTier]: held lots only, plus purchases, plus expected issuance (ADR 0038). Units keep the class
     * index; supply above the tier simply has no eligible obligation.
     */
    fun solve(problem: AllocationProblem, maxTier: SupplyTier = SupplyTier.EXPECTED): FlowState {
        val units = problem.classes.map { cls ->
            if (cls.future == null) SupplyUnit.Held(cls.index, cls.quantity) else SupplyUnit.Future(cls.index, cls.quantity, cls.tier)
        }
        val allowed = tierMask(problem, maxTier)
        val demands = problem.obligations
            .sortedWith(compareBy<ObligationModel> { it.priority }.thenBy { it.obligation.deliveryDate }.thenBy { it.id })
            .map { FlowDemand(it.index, it.priority, it.demand) }
        val graph = CompressedFlowGraph.build(
            units = units,
            demands = demands,
            obligationCount = problem.obligations.size,
            eligibleUnits = { o -> if (allowed == null) problem.eligible[o] else (problem.eligible[o].clone() as BitSet).apply { and(allowed) } },
            // Surplus is reported per (bucket, year), so supply of different buckets or years never merges.
            atomKey = { u -> problem.classes[u].let { cls -> (bucketOf(cls) ?: UNBUCKETED) to cls.productionYear } },
        )
        return FlowState(graph)
    }

    /** Classes of [maxTier] or firmer; null when that is every class (no masking needed). */
    private fun tierMask(problem: AllocationProblem, maxTier: SupplyTier): BitSet? {
        if (problem.classes.none { it.tier > maxTier }) return null
        return BitSet(problem.classes.size).apply { problem.classes.filter { it.tier <= maxTier }.forEach { set(it.index) } }
    }

    /**
     * Coverage from held lots; when the problem carries future supply, also cumulatively with purchases and with
     * expected issuance. Shortage groups and surplus are about held lots.
     */
    fun analyse(problem: AllocationProblem, holderEntityId: UUID, snapshotSeq: Long, asOf: Instant): CoverageReport {
        val state = solve(problem, SupplyTier.HELD)
        val covered = state.obligationCoverage(problem.obligations.size)
        val future = problem.classes.any { it.future != null }
        val withContracted = if (future) solve(problem, SupplyTier.CONTRACTED).obligationCoverage(problem.obligations.size) else null
        val withExpected = if (future) solve(problem, SupplyTier.EXPECTED).obligationCoverage(problem.obligations.size) else null
        val held = tierMask(problem, SupplyTier.HELD)
        val heldEligible = { o: Int -> if (held == null) problem.eligible[o] else (problem.eligible[o].clone() as BitSet).apply { and(held) } }
        val coverage = problem.obligations.map { obl ->
            val eligible = heldEligible(obl.index)
            ObligationCoverage(
                obligationId = obl.id,
                demandMwh = obl.demand,
                coveredMwh = covered[obl.index],
                eligibleSupplyMwh = eligible.stream().mapToLong { problem.classes[it].quantity }.sum(),
                eligibleClassCount = eligible.cardinality(),
                coveredWithContractedMwh = withContracted?.get(obl.index),
                coveredWithExpectedMwh = withExpected?.get(obl.index),
            )
        }

        val reachableGroups = state.reachableGroups()
        val reachable = BooleanArray(problem.obligations.size) { reachableGroups[state.graph.groupOfObligation[it]] }
        val groups = shortageGroups(problem, reachable, coverage, heldEligible)

        val surplus = state.graph.atoms.filter { it.tier == SupplyTier.HELD }
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
        eligibleOf: (Int) -> BitSet,
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
                val newClasses = eligibleOf(obl.index).stream().filter { classes.add(it) }.toArray()
                short.filter { it.index !in seen && newClasses.any { ci -> eligibleOf(it.index).get(ci) } }.forEach {
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
