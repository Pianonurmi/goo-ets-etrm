package com.pianonurmi.etrm.allocation.domain.flow

import java.util.BitSet

/** How firm a unit of supply is. Coverage and headroom are reported cumulatively per tier (ADR 0036). */
enum class SupplyTier { HELD, CONTRACTED, EXPECTED }

/** Something that can deliver certificates: a class of held lots now; purchases and expected issuance later. */
sealed interface SupplyUnit {
    val tier: SupplyTier
    val quantityMwh: Long

    /** Held lots of one class (`LotClass.index`). */
    data class Held(val classIndex: Int, override val quantityMwh: Long) : SupplyUnit {
        override val tier: SupplyTier get() = SupplyTier.HELD
    }
}

/** An obligation as the flow sees it: its index in the problem, its priority tier and the MWh it still needs. */
data class FlowDemand(val obligationIndex: Int, val priority: Int, val demandMwh: Long)

/** Obligations with the same priority and exactly the same eligible supply: one demand node. */
class DemandGroup(val index: Int, val priority: Int, val obligationIndexes: IntArray, val demandMwh: Long, val eligibleUnits: BitSet)

/** Supply units with the same tier, the same reporting key and exactly the same demand groups: one supply node. */
class SupplyAtom(val index: Int, val tier: SupplyTier, val key: Any?, val unitIndexes: IntArray, val quantityMwh: Long, val groupIndexes: IntArray)

/**
 * The eligibility graph with interchangeable nodes merged (ADR 0036). Merging obligations that have identical
 * eligible supply, and supply units that serve identical demand groups, changes no max-flow value: merged nodes
 * have identical neighbourhoods, so any flow on the original graph maps to one on the compressed graph and back.
 * At 100k lots and 5k obligations the uncompressed graph can have hundreds of millions of edges; the compressed one
 * has as many nodes as there are distinct eligibility patterns, typically a few thousand.
 *
 * Demand order inside a group (delivery date, then id) decides how the group's flow is shared out
 * ([FlowState.obligationCoverage]); [atomKey] keeps apart supply that must be reported separately (bucket and year).
 */
class CompressedFlowGraph private constructor(
    val units: List<SupplyUnit>,
    val demands: List<FlowDemand>,
    val groups: List<DemandGroup>,
    val atoms: List<SupplyAtom>,
    /** Group index per obligation index (`-1` for an obligation not given). */
    val groupOfObligation: IntArray,
    /** Atom index per unit index. */
    val atomOfUnit: IntArray,
) {
    /** Eligibility edges between atoms and groups. */
    val edgeCount: Int get() = atoms.sumOf { it.groupIndexes.size }

    companion object {
        /**
         * @param demands obligations in the order their group shares flow out (delivery date, then id).
         * @param eligibleUnits the units eligible for an obligation index (bit `u` set for unit `units[u]`).
         * @param obligationCount the size of the obligation index space.
         */
        fun build(
            units: List<SupplyUnit>,
            demands: List<FlowDemand>,
            obligationCount: Int,
            eligibleUnits: (Int) -> BitSet,
            atomKey: (Int) -> Any? = { null },
        ): CompressedFlowGraph {
            val groupIndex = LinkedHashMap<GroupKey, MutableList<FlowDemand>>()
            demands.forEach { d -> groupIndex.getOrPut(GroupKey(d.priority, eligibleUnits(d.obligationIndex))) { mutableListOf() } += d }
            val groupOfObligation = IntArray(obligationCount) { -1 }
            val groups = groupIndex.entries.mapIndexed { g, (key, members) ->
                members.forEach { groupOfObligation[it.obligationIndex] = g }
                DemandGroup(g, key.priority, members.map { it.obligationIndex }.toIntArray(), members.sumOf { it.demandMwh }, key.eligible)
            }

            val unitGroups = Array(units.size) { IntList() }
            groups.forEach { g ->
                var u = g.eligibleUnits.nextSetBit(0)
                while (u >= 0) {
                    require(u < units.size) { "eligible unit $u outside ${units.size} units" }
                    unitGroups[u].add(g.index)
                    u = g.eligibleUnits.nextSetBit(u + 1)
                }
            }
            val atomIndex = LinkedHashMap<AtomKey, MutableList<Int>>()
            units.indices.forEach { u -> atomIndex.getOrPut(AtomKey(units[u].tier, atomKey(u), unitGroups[u].toList())) { mutableListOf() } += u }
            val atomOfUnit = IntArray(units.size)
            val atoms = atomIndex.entries.mapIndexed { a, (key, members) ->
                members.forEach { atomOfUnit[it] = a }
                SupplyAtom(a, key.tier, key.extra, members.toIntArray(), members.sumOf { units[it].quantityMwh }, key.groups.toIntArray())
            }
            return CompressedFlowGraph(units, demands, groups, atoms, groupOfObligation, atomOfUnit)
        }
    }

    private data class GroupKey(val priority: Int, val eligible: BitSet)

    private data class AtomKey(val tier: SupplyTier, val extra: Any?, val groups: List<Int>)

    private class IntList {
        private var items = IntArray(4)
        private var size = 0

        fun add(value: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = value
        }

        fun toList(): List<Int> = items.copyOf(size).asList()
    }
}

/**
 * A max-flow over a [CompressedFlowGraph]: source → atom (its quantity) → group (unbounded) → sink (its demand).
 * Groups join the sink one priority tier at a time and the flow is augmented after each tier, so supply granted to a
 * higher tier is never taken back (augmenting paths can reroute it but not reduce it) while the result is still a
 * global maximum.
 */
class FlowState(val graph: CompressedFlowGraph) {
    private val atomCount = graph.atoms.size
    private val groupCount = graph.groups.size
    val source = 0
    val sink = 1 + atomCount + groupCount

    /** A spare vertex that collects a trial's flow ([headroom]); it has no edges outside a trial. */
    val probe = sink + 1
    val network = FlowGraph(probe + 1, atomCount + groupCount + graph.edgeCount)
    private val supplyEdges = IntArray(atomCount)
    private val demandEdges = IntArray(groupCount) { -1 }

    fun atomNode(atom: Int): Int = 1 + atom

    fun groupNode(group: Int): Int = 1 + atomCount + group

    init {
        graph.atoms.forEach { atom ->
            supplyEdges[atom.index] = network.addEdge(source, atomNode(atom.index), atom.quantityMwh)
            atom.groupIndexes.forEach { g -> network.addEdge(atomNode(atom.index), groupNode(g), UNBOUNDED) }
        }
        graph.groups.groupBy { it.priority }.toSortedMap().values.forEach { tier ->
            tier.forEach { g -> demandEdges[g.index] = network.addEdge(groupNode(g.index), sink, g.demandMwh) }
            network.augment(source, sink)
        }
    }

    fun groupFlow(group: Int): Long = network.flow(demandEdges[group])

    fun atomResidual(atom: Int): Long = network.residual(supplyEdges[atom])

    val totalFlow: Long get() = graph.groups.sumOf { groupFlow(it.index) }

    /** Covered MWh per obligation index: each group's flow shared out in the group's demand order. */
    fun obligationCoverage(obligationCount: Int): LongArray {
        val covered = LongArray(obligationCount)
        val demandOf = graph.demands.associate { it.obligationIndex to it.demandMwh }
        graph.groups.forEach { g ->
            var left = groupFlow(g.index)
            g.obligationIndexes.forEach { o ->
                val take = minOf(left, demandOf.getValue(o))
                covered[o] = take
                left -= take
            }
        }
        return covered
    }

    /** Unused MWh per unit index: each atom's residual shared out over its units in index order. */
    fun unitResidual(): LongArray {
        val residual = LongArray(graph.units.size)
        graph.atoms.forEach { atom ->
            var left = atomResidual(atom.index)
            atom.unitIndexes.forEach { u ->
                val take = minOf(left, graph.units[u].quantityMwh)
                residual[u] = take
                left -= take
            }
        }
        return residual
    }

    /**
     * Demand groups reachable from the source in the residual graph. A group left short is never reachable (otherwise
     * an augmenting path would exist); reachable groups have spare supply within reach.
     */
    fun reachableGroups(): BooleanArray {
        val seen = network.reachableFrom(source)
        return BooleanArray(groupCount) { seen[groupNode(it)] }
    }

    /**
     * Headroom: the most a new demand, served by [eligibleUnits], could receive without taking anything from existing
     * coverage. It is a max-flow from the source to a trial node in the residual graph with the sink blocked: paths
     * may reroute existing flow from one atom to another (freeing supply the new demand can use), but none may end at
     * the sink, so no group loses flow. The solved flow is left exactly as it was.
     *
     * The new demand's eligibility need not follow atom boundaries. Each atom's edge to the trial node is capped at
     * the quantity of its eligible units, which is exact: units of one atom serve the same groups, so existing flow
     * can always be moved onto the units the new demand cannot use.
     */
    fun headroom(eligibleUnits: BitSet, limit: Long = UNBOUNDED): Long = headroomDetail(eligibleUnits, limit).mwh

    /**
     * [headroom] with what limits it. [bindingAtoms] are the atoms the new demand may use that stay exhausted: no
     * augmenting path reaches them, so more of their supply would mean more headroom (the minimal cut). [freeMwh] is
     * the eligible supply nobody uses, before any rerouting; headroom is at least that.
     */
    fun headroomDetail(eligibleUnits: BitSet, limit: Long = UNBOUNDED): Headroom {
        val eligibleByAtom = eligibleByAtom(eligibleUnits)
        if (eligibleByAtom.all { it == 0L }) return Headroom(0, 0, IntArray(0))
        val free = (0 until atomCount).sumOf { minOf(eligibleByAtom[it], atomResidual(it)) }
        val blocked = BooleanArray(network.vertexCount).apply { this[sink] = true }
        return trial {
            eligibleByAtom.forEachIndexed { atom, quantity -> if (quantity > 0) network.addEdge(atomNode(atom), probe, quantity) }
            val extra = network.augment(source, probe, blocked, limit)
            val reachable = network.reachableFrom(source, blocked)
            val binding = (0 until atomCount).filter { eligibleByAtom[it] > 0 && !reachable[atomNode(it)] }.toIntArray()
            Headroom(extra, free, binding)
        }
    }

    /**
     * The most that several new demands can receive together without taking coverage from anyone: one trial vertex
     * per demand, capped at its quantity, feeding [probe]. Units of one atom serve the same existing groups, but each
     * new demand may take a different subset of them, so atoms are split by which new demands their units serve: no
     * unit is offered twice. Null when there are more demands than [MAX_JOINT].
     */
    fun jointHeadroom(demands: List<Pair<BitSet, Long>>): Long? {
        if (demands.size > MAX_JOINT) return null
        if (demands.isEmpty()) return 0
        val vertices = network.vertexCount
        try {
            return trial {
                val nodes = demands.map { (_, quantity) -> network.addVertex().also { network.addEdge(it, probe, quantity) } }
                val parts = HashMap<Pair<Int, Long>, Long>()
                graph.units.indices.forEach { u ->
                    var signature = 0L
                    demands.forEachIndexed { i, (eligible, _) -> if (eligible[u]) signature = signature or (1L shl i) }
                    if (signature != 0L) parts.merge(graph.atomOfUnit[u] to signature, graph.units[u].quantityMwh, Long::plus)
                }
                parts.forEach { (key, quantity) ->
                    val part = network.addVertex()
                    network.addEdge(atomNode(key.first), part, quantity)
                    nodes.indices.filter { key.second shr it and 1L == 1L }.forEach { network.addEdge(part, nodes[it], UNBOUNDED) }
                }
                network.augment(source, probe, BooleanArray(network.vertexCount).apply { this[sink] = true })
            }
        } finally {
            network.truncateVertices(vertices)
        }
    }

    private fun eligibleByAtom(eligibleUnits: BitSet): LongArray {
        val byAtom = LongArray(atomCount)
        var u = eligibleUnits.nextSetBit(0)
        while (u >= 0) {
            byAtom[graph.atomOfUnit[u]] += graph.units[u].quantityMwh
            u = eligibleUnits.nextSetBit(u + 1)
        }
        return byAtom
    }

    /** Runs [block] on trial edges and flow, then puts the graph back exactly as it was. */
    private inline fun <T> trial(block: () -> T): T {
        val mark = network.mark()
        val residuals = network.snapshot()
        try {
            return block()
        } finally {
            network.rollback(mark)
            network.restore(residuals)
        }
    }

    companion object {
        const val UNBOUNDED = Long.MAX_VALUE / 4

        /** Demands one joint trial can hold (one bit each in a unit's signature): a trade's deliveries. */
        const val MAX_JOINT = 63
    }
}

/** A trial's result: what the new demand can get, the unused eligible supply, and the atoms that limit it. */
class Headroom(val mwh: Long, val freeMwh: Long, val bindingAtoms: IntArray)
