package com.pianonurmi.etrm.allocation.domain.flow

import java.util.ArrayDeque

/**
 * Dinic max-flow with paired reverse edges (edge `e` and `e xor 1`). Edges live in primitive arrays with linked-list
 * adjacency (`head`/`next`) so a large graph stays compact.
 *
 * Two additions serve the portfolio queries (ADR 0036):
 * - **blocked vertices**: [augment] can be told never to pass through some vertices. Headroom ("how much more can be
 *   sold") augments towards a new demand node with the sink blocked, so no path may take coverage from an existing
 *   obligation;
 * - **trials**: [mark], [snapshot], then [rollback] and [restore] undo a trial edge set and augmentation, so one
 *   solved graph answers many what-if questions.
 */
class FlowGraph(vertices: Int, expectedEdges: Int = 16) {
    private var n = vertices
    private var to = IntArray(maxOf(2, expectedEdges * 2))
    private var cap = LongArray(to.size)
    private var next = IntArray(to.size)
    private var head = IntArray(n) { -1 }
    private var level = IntArray(n)
    private var iter = IntArray(n)
    private var pathEdges = IntArray(n)
    private var edgeCount = 0

    val vertexCount: Int get() = n

    /** Number of directed edges added (reverse edges not counted). */
    val edges: Int get() = edgeCount / 2

    /** Adds a directed edge and its reverse; returns the edge index (reverse is index + 1). */
    fun addEdge(from: Int, target: Int, capacity: Long): Int {
        require(capacity >= 0) { "negative capacity" }
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

    /**
     * Raises the capacity of an edge whose flow stays as it is (the residual grows by the difference). Lowering below
     * the current flow is refused: it would leave an infeasible flow behind.
     */
    fun raiseCapacity(edge: Int, capacity: Long) {
        val current = cap[edge] + cap[edge xor 1]
        require(capacity >= current) { "capacity can only be raised ($capacity < $current)" }
        cap[edge] += capacity - current
    }

    /** Adds a vertex with no edges (for a trial); returns its index. */
    fun addVertex(): Int {
        if (n == head.size) {
            val size = head.size * 2
            head = head.copyOf(size)
            level = level.copyOf(size)
            iter = iter.copyOf(size)
            pathEdges = pathEdges.copyOf(size)
        }
        head[n] = -1
        return n++
    }

    /** Drops the vertices added after the first [count]; their edges must have been rolled back already. */
    fun truncateVertices(count: Int) {
        require(count in 0..n) { "cannot truncate $n vertices to $count" }
        n = count
    }

    /** A point to [rollback] to: edges added after it can be removed again. */
    fun mark(): Int = edgeCount

    /**
     * Removes every edge added after [mark], newest first. Edges are prepended to their vertices' adjacency lists, so
     * popping them in reverse order restores the lists exactly.
     */
    fun rollback(mark: Int) {
        require(mark in 0..edgeCount && mark % 2 == 0) { "not a mark of this graph: $mark" }
        while (edgeCount > mark) {
            val reverse = edgeCount - 1
            val forward = edgeCount - 2
            head[to[forward]] = next[reverse]
            head[to[reverse]] = next[forward]
            edgeCount -= 2
        }
    }

    /** The residual capacities of every edge, to be handed back to [restore]. */
    fun snapshot(): LongArray = cap.copyOf(edgeCount)

    fun restore(snapshot: LongArray) {
        require(snapshot.size == edgeCount) { "snapshot of a different graph (${snapshot.size} != $edgeCount)" }
        System.arraycopy(snapshot, 0, cap, 0, edgeCount)
    }

    /** Augments to a maximum flow from [source] to [sink], never passing through a [blocked] vertex. */
    fun augment(source: Int, sink: Int, blocked: BooleanArray? = null, limit: Long = Long.MAX_VALUE): Long {
        var total = 0L
        while (total < limit && bfs(source, sink, blocked)) {
            System.arraycopy(head, 0, iter, 0, n)
            while (total < limit) {
                val f = dfs(source, sink, limit - total)
                if (f == 0L) break
                total += f
            }
        }
        return total
    }

    fun reachableFrom(source: Int, blocked: BooleanArray? = null): BooleanArray {
        val seen = BooleanArray(n)
        val queue = ArrayDeque<Int>().apply { add(source) }
        seen[source] = true
        while (queue.isNotEmpty()) {
            val v = queue.poll()
            var e = head[v]
            while (e >= 0) {
                val w = to[e]
                if (cap[e] > 0 && !seen[w] && open(w, blocked, -1)) {
                    seen[w] = true
                    queue.add(w)
                }
                e = next[e]
            }
        }
        return seen
    }

    /** Whether a search may enter [w]: it is not blocked, or it is the [target] itself. */
    private fun open(w: Int, blocked: BooleanArray?, target: Int): Boolean = blocked == null || !blocked[w] || w == target

    private fun ensureCapacity(required: Int) {
        if (required <= to.size) return
        val size = maxOf(required, to.size * 2)
        to = to.copyOf(size)
        cap = cap.copyOf(size)
        next = next.copyOf(size)
    }

    private fun bfs(source: Int, sink: Int, blocked: BooleanArray?): Boolean {
        level.fill(-1, 0, n)
        level[source] = 0
        val queue = ArrayDeque<Int>().apply { add(source) }
        while (queue.isNotEmpty()) {
            val v = queue.poll()
            var e = head[v]
            while (e >= 0) {
                val w = to[e]
                if (cap[e] > 0 && level[w] < 0 && open(w, blocked, sink)) {
                    level[w] = level[v] + 1
                    queue.add(w)
                }
                e = next[e]
            }
        }
        return level[sink] >= 0
    }

    /**
     * One blocking-flow path, found iteratively (residual paths in a large bipartite graph can be long enough to
     * overflow a recursive search). Returns the amount pushed, at most [limit].
     */
    private fun dfs(source: Int, sink: Int, limit: Long): Long {
        var depth = 0
        var v = source
        while (true) {
            if (v == sink) {
                var pushed = limit
                for (i in 0 until depth) pushed = minOf(pushed, cap[pathEdges[i]])
                for (i in 0 until depth) {
                    val e = pathEdges[i]
                    cap[e] -= pushed
                    cap[e xor 1] += pushed
                }
                return pushed
            }
            var advanced = false
            while (iter[v] >= 0) {
                val e = iter[v]
                val w = to[e]
                if (cap[e] > 0 && level[w] == level[v] + 1) {
                    pathEdges[depth++] = e
                    v = w
                    advanced = true
                    break
                }
                iter[v] = next[e]
            }
            if (!advanced) {
                // Dead end: no path to the sink through v in this phase.
                level[v] = -1
                if (depth == 0) return 0
                val back = pathEdges[--depth]
                v = to[back xor 1]
                iter[v] = next[iter[v]]
            }
        }
    }
}
