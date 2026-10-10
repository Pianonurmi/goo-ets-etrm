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
 * Obligations are attached to the sink one priority tier at a time and the flow is augmented after each tier, so
 * flow already granted to a higher-priority tier is never taken away (augmenting paths can reroute it but never
 * reduce it) while the final flow is still a global maximum. Within a tier the flow is distributed without
 * preference. Shortage groups are the connected components of obligations left short together with the
 * (exhausted) classes they compete for; they explain the shortfall.
 *
 * Side constraints (share caps) are not part of the flow, so coverage is an upper bound on what a plan achieves.
 */
class CoverageAnalyser(private val bucketOf: (LotClass) -> String?) {
    fun analyse(problem: AllocationProblem, holderEntityId: UUID, snapshotSeq: Long, asOf: Instant): CoverageReport {
        val c = problem.classes.size
        val o = problem.obligations.size
        val source = 0
        val sink = 1 + c + o
        val expectedEdges = c + o + problem.eligible.sumOf { it.cardinality() }
        val graph = FlowGraph(sink + 1, expectedEdges)
        val supplyEdges = IntArray(c)
        problem.classes.forEach { cls -> supplyEdges[cls.index] = graph.addEdge(source, 1 + cls.index, cls.quantity) }
        problem.obligations.forEach { obl ->
            problem.eligible[obl.index].stream().forEach { ci -> graph.addEdge(1 + ci, 1 + c + obl.index, Long.MAX_VALUE / 4) }
        }
        val demandEdges = IntArray(o) { -1 }
        problem.obligations.groupBy { it.priority }.toSortedMap().values.forEach { tier ->
            tier.sortedWith(compareBy<ObligationModel> { it.obligation.deliveryDate }.thenBy { it.id }).forEach { obl ->
                demandEdges[obl.index] = graph.addEdge(1 + c + obl.index, sink, obl.demand)
            }
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

/**
 * Dinic max-flow with paired reverse edges (edge `e` and `e xor 1`). Edges live in primitive arrays with
 * linked-list adjacency (`head`/`next`) so a graph with tens of millions of eligibility edges stays compact.
 */
class FlowGraph(private val n: Int, expectedEdges: Int = 16) {
    private var to = IntArray(maxOf(2, expectedEdges * 2))
    private var cap = LongArray(to.size)
    private var next = IntArray(to.size)
    private val head = IntArray(n) { -1 }
    private val level = IntArray(n)
    private val iter = IntArray(n)
    private var edgeCount = 0

    /** Adds a directed edge and its reverse; returns the edge index (reverse is index + 1). */
    fun addEdge(from: Int, target: Int, capacity: Long): Int {
        ensureCapacity(edgeCount + 2)
        val id = edgeCount
        to[id] = target
        cap[id] = capacity
        next[id] = head[from]
        head[from] = id
        to[id + 1] = from
        cap[id + 1] = 0L
        next[id + 1] = head[target]
        head[target] = id + 1
        edgeCount += 2
        return id
    }

    fun flow(edge: Int): Long = cap[edge xor 1]

    fun residual(edge: Int): Long = cap[edge]

    fun augment(source: Int, sink: Int): Long {
        var total = 0L
        while (bfs(source, sink)) {
            System.arraycopy(head, 0, iter, 0, n)
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
            var e = head[v]
            while (e >= 0) {
                if (cap[e] > 0 && !seen[to[e]]) {
                    seen[to[e]] = true
                    queue.add(to[e])
                }
                e = next[e]
            }
        }
        return seen
    }

    private fun ensureCapacity(required: Int) {
        if (required <= to.size) return
        val size = maxOf(required, to.size * 2)
        to = to.copyOf(size)
        cap = cap.copyOf(size)
        next = next.copyOf(size)
    }

    private fun bfs(source: Int, sink: Int): Boolean {
        level.fill(-1)
        level[source] = 0
        val queue = ArrayDeque<Int>().apply { add(source) }
        while (queue.isNotEmpty()) {
            val v = queue.poll()
            var e = head[v]
            while (e >= 0) {
                if (cap[e] > 0 && level[to[e]] < 0) {
                    level[to[e]] = level[v] + 1
                    queue.add(to[e])
                }
                e = next[e]
            }
        }
        return level[sink] >= 0
    }

    private fun dfs(v: Int, sink: Int, pushed: Long): Long {
        if (v == sink) return pushed
        while (iter[v] >= 0) {
            val e = iter[v]
            val w = to[e]
            if (cap[e] > 0 && level[w] == level[v] + 1) {
                val d = dfs(w, sink, minOf(pushed, cap[e]))
                if (d > 0) {
                    cap[e] = cap[e] - d
                    cap[e xor 1] = cap[e xor 1] + d
                    return d
                }
            }
            iter[v] = next[e]
        }
        return 0
    }
}
