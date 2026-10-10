package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.BucketSurplus
import com.pianonurmi.etrm.allocation.api.CoverageReport
import com.pianonurmi.etrm.allocation.api.ObligationCoverage
import com.pianonurmi.etrm.allocation.api.ShortageGroup
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

/**
 * Coverage: a max-flow between lot classes (supply) and obligations (demand) over the eligibility graph.
 * Obligations are attached to the sink in priority order and the flow is augmented after each, so flow already
 * granted to a higher-priority obligation is never taken away. Shortage groups are the connected components of
 * obligations left short together with the (exhausted) classes they compete for; they explain the shortfall.
 *
 * Side constraints (share caps) are not part of the flow, so coverage is an upper bound on what a plan achieves.
 */
class CoverageAnalyser(private val bucketOf: (LotClass) -> String?) {
    fun analyse(problem: AllocationProblem, holderEntityId: UUID, snapshotSeq: Long, asOf: Instant): CoverageReport {
        val c = problem.classes.size
        val o = problem.obligations.size
        val source = 0
        val sink = 1 + c + o
        val graph = FlowGraph(sink + 1)
        val supplyEdges = IntArray(c)
        problem.classes.forEach { cls -> supplyEdges[cls.index] = graph.addEdge(source, 1 + cls.index, cls.quantity) }
        problem.obligations.forEach { obl ->
            problem.eligible[obl.index].stream().forEach { ci -> graph.addEdge(1 + ci, 1 + c + obl.index, Long.MAX_VALUE / 4) }
        }
        val demandEdges = IntArray(o) { -1 }
        val ordered = problem.obligations.sortedWith(compareBy<ObligationModel> { it.priority }.thenBy { it.obligation.deliveryDate }.thenBy { it.id })
        ordered.forEach { obl ->
            demandEdges[obl.index] = graph.addEdge(1 + c + obl.index, sink, obl.demand)
            graph.augment(source, sink)
        }

        val coverage = problem.obligations.map { obl ->
            ObligationCoverage(
                obligationId = obl.id,
                demandMwh = obl.demand,
                coveredMwh = if (demandEdges[obl.index] >= 0) graph.flow(demandEdges[obl.index]) else 0,
                eligibleSupplyMwh = problem.eligibleSupply(obl),
                eligibleClassCount = problem.eligible[obl.index].cardinality(),
            )
        }

        val groups = shortageGroups(problem, graph.reachableFrom(source), coverage, 1 + c)

        val surplus = problem.classes
            .map { cls -> Triple(bucketOf(cls) ?: "UNBUCKETED", cls.productionYear, graph.residual(supplyEdges[cls.index])) }
            .filter { it.third > 0 }
            .groupBy { it.first to it.second }
            .map { (key, items) -> BucketSurplus(key.first, key.second, items.sumOf { it.third }) }
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
        obligationOffset: Int,
    ): List<ShortageGroup> {
        val short = problem.obligations.filter { !reachable[obligationOffset + it.index] }
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
}

/** Dinic max-flow on an adjacency-list graph with paired reverse edges. */
class FlowGraph(private val n: Int) {
    private val to = ArrayList<Int>()
    private val cap = ArrayList<Long>()
    private val adj = Array(n) { ArrayList<Int>() }
    private val level = IntArray(n)
    private val iter = IntArray(n)

    /** Adds a directed edge and its reverse; returns the edge index (reverse is index + 1). */
    fun addEdge(from: Int, target: Int, capacity: Long): Int {
        val id = to.size
        to += target
        cap += capacity
        adj[from] += id
        to += from
        cap += 0L
        adj[target] += id + 1
        return id
    }

    fun flow(edge: Int): Long = cap[edge xor 1]

    fun residual(edge: Int): Long = cap[edge]

    fun augment(source: Int, sink: Int): Long {
        var total = 0L
        while (bfs(source, sink)) {
            iter.fill(0)
            while (true) {
                val f = dfs(source, sink, Long.MAX_VALUE)
                if (f == 0L) break
                total += f
            }
        }
        return total
    }

    fun reachableFrom(source: Int): BooleanArray {
        val seen = BooleanArray(n)
        val queue = ArrayDeque<Int>().apply { add(source) }
        seen[source] = true
        while (queue.isNotEmpty()) {
            val v = queue.poll()
            adj[v].forEach { e ->
                if (cap[e] > 0 && !seen[to[e]]) {
                    seen[to[e]] = true
                    queue.add(to[e])
                }
            }
        }
        return seen
    }

    private fun bfs(source: Int, sink: Int): Boolean {
        level.fill(-1)
        level[source] = 0
        val queue = ArrayDeque<Int>().apply { add(source) }
        while (queue.isNotEmpty()) {
            val v = queue.poll()
            adj[v].forEach { e ->
                if (cap[e] > 0 && level[to[e]] < 0) {
                    level[to[e]] = level[v] + 1
                    queue.add(to[e])
                }
            }
        }
        return level[sink] >= 0
    }

    private fun dfs(v: Int, sink: Int, pushed: Long): Long {
        if (v == sink) return pushed
        while (iter[v] < adj[v].size) {
            val e = adj[v][iter[v]]
            val w = to[e]
            if (cap[e] > 0 && level[w] == level[v] + 1) {
                val d = dfs(w, sink, minOf(pushed, cap[e]))
                if (d > 0) {
                    cap[e] = cap[e] - d
                    cap[e xor 1] = cap[e xor 1] + d
                    return d
                }
            }
            iter[v]++
        }
        return 0
    }
}
