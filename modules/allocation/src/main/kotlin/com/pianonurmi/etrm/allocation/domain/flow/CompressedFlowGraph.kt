package com.pianonurmi.etrm.allocation.domain.flow

import com.pianonurmi.etrm.allocation.api.SupplyTier
import java.util.BitSet

/** Something that can deliver certificates: a class of held lots now; purchases and expected issuance later. */
sealed interface SupplyUnit {
    val tier: SupplyTier
    val quantityMwh: Long

    /** Held lots of one class (`LotClass.index`). */
    data class Held(val classIndex: Int, override val quantityMwh: Long) : SupplyUnit {
        override val tier: SupplyTier get() = SupplyTier.HELD
    }

    /** Supply still to come (ADR 0038): a purchase or expected issuance, as the lot class standing in for it. */
    data class Future(val classIndex: Int, override val quantityMwh: Long, override val tier: SupplyTier) : SupplyUnit
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
            unitProfile: IntArray? = null,
            unitOrder: IntArray? = null,
        ): CompressedFlowGraph {
            val groupIndex = LinkedHashMap<GroupKey, MutableList<FlowDemand>>()
            demands.forEach { d -> groupIndex.getOrPut(GroupKey(d.priority, eligibleUnits(d.obligationIndex))) { mutableListOf() } += d }
            val groupOfObligation = IntArray(obligationCount) { -1 }
            val groups = groupIndex.entries.mapIndexed { g, (key, members) ->
                members.forEach { groupOfObligation[it.obligationIndex] = g }
                DemandGroup(g, key.priority, members.map { it.obligationIndex }.toIntArray(), members.sumOf { it.demandMwh }, key.eligible)
            }

            // Units enter atoms in this order (atoms by their first unit, members in order); units without supply join none.
            val order = (unitOrder ?: IntArray(units.size) { it }).filter { units[it].quantityMwh > 0 }
            if (unitProfile != null) return byProfile(units, demands, groups, groupOfObligation, atomKey, unitProfile, order)
            val block = refine(units, groups, atomKey)
            val atomOfBlock = HashMap<Int, Int>()
            val atomOfUnit = IntArray(units.size) { NO_ATOM }
            val members = ArrayList<IntList>()
            order.forEach { u ->
                val a = atomOfBlock.getOrPut(block[u]) { members.add(IntList()).let { members.size - 1 } }
                atomOfUnit[u] = a
                members[a].add(u)
            }
            val atomGroups = Array(members.size) { IntList() }
            groups.forEach { g ->
                g.eligibleUnits.forEachSetBit { u ->
                    atomOfUnit[u].takeIf { it != NO_ATOM }?.let { a -> atomGroups[a].let { if (it.last != g.index) it.add(g.index) } }
                }
            }
            val atoms = members.mapIndexed { a, m ->
                val unitIndexes = m.toIntArray()
                val first = unitIndexes[0]
                SupplyAtom(a, units[first].tier, atomKey(first), unitIndexes, unitIndexes.sumOf { units[it].quantityMwh }, atomGroups[a].toIntArray())
            }
            return CompressedFlowGraph(units, demands, groups, atoms, groupOfObligation, atomOfUnit)
        }

        /**
         * Atoms when units come with profiles (ADR 0043): units of one profile are eligible for exactly the same groups,
         * so the groups are refined over one representative per profile, and a unit's atom is its tier, its reporting
         * key and its profile's block. Linear in the units plus groups × profiles; the eligible pairs are never
         * walked. Same atoms, in the same order, as without profiles.
         */
        private fun byProfile(
            units: List<SupplyUnit>,
            demands: List<FlowDemand>,
            groups: List<DemandGroup>,
            groupOfObligation: IntArray,
            atomKey: (Int) -> Any?,
            unitProfile: IntArray,
            order: List<Int>,
        ): CompressedFlowGraph {
            require(unitProfile.size == units.size) { "one profile per unit" }
            groups.forEach { g ->
                require(g.eligibleUnits.length() <= units.size) { "eligible unit ${g.eligibleUnits.length() - 1} outside ${units.size} units" }
            }
            val profiles = (unitProfile.maxOrNull() ?: -1) + 1
            val representative = IntArray(profiles) { -1 }
            unitProfile.forEachIndexed { u, p -> if (representative[p] < 0) representative[p] = u }
            // Groups served per profile, as a sorted list: equal lists mean equal blocks.
            val served = Array(profiles) { IntList() }
            groups.forEach { g ->
                for (p in 0 until profiles) {
                    val r = representative[p]
                    if (r >= 0 && g.eligibleUnits.get(r)) served[p].add(g.index)
                }
            }
            val servedBlock = HashMap<List<Int>, Int>()
            val blockOfProfile = IntArray(profiles) { p -> servedBlock.getOrPut(served[p].toIntArray().asList()) { servedBlock.size } }
            val atomIndex = LinkedHashMap<Triple<SupplyTier, Any?, Int>, IntList>()
            order.forEach { u ->
                atomIndex.getOrPut(Triple(units[u].tier, atomKey(u), blockOfProfile[unitProfile[u]])) { IntList() }.add(u)
            }
            val atomOfUnit = IntArray(units.size) { NO_ATOM }
            val atoms = atomIndex.entries.mapIndexed { a, (key, members) ->
                val unitIndexes = members.toIntArray()
                unitIndexes.forEach { atomOfUnit[it] = a }
                val groupIndexes = served[unitProfile[unitIndexes[0]]].toIntArray()
                SupplyAtom(a, key.first, key.second, unitIndexes, unitIndexes.sumOf { units[it].quantityMwh }, groupIndexes)
            }
            return CompressedFlowGraph(units, demands, groups, atoms, groupOfObligation, atomOfUnit)
        }

        /**
         * Units with the same tier, the same reporting key and exactly the same demand groups get the same block. By
         * partition refinement: one block per (tier, key), then every block is split by membership of each group in
         * turn. Exact, and linear in the eligible (unit, group) pairs, with no list of groups per unit.
         */
        private fun refine(units: List<SupplyUnit>, groups: List<DemandGroup>, atomKey: (Int) -> Any?): IntArray {
            val initial = HashMap<Pair<SupplyTier, Any?>, Int>()
            val block = IntArray(units.size) { u -> initial.getOrPut(units[u].tier to atomKey(u)) { initial.size } }
            var blocks = initial.size
            // For a block split in the current group's round: the block its members in the group move to.
            var twin = IntArray(maxOf(INITIAL_BLOCKS, blocks))
            var round = IntArray(twin.size) { -1 }
            groups.forEach { g ->
                g.eligibleUnits.forEachSetBit { u ->
                    require(u < units.size) { "eligible unit $u outside ${units.size} units" }
                    val b = block[u]
                    if (round[b] != g.index) {
                        if (blocks == twin.size) {
                            twin = twin.copyOf(blocks * 2)
                            round = round.copyOf(blocks * 2).also { it.fill(-1, blocks, blocks * 2) }
                        }
                        twin[b] = blocks++
                        round[b] = g.index
                    }
                    block[u] = twin[b]
                }
            }
            return block
        }

        private const val INITIAL_BLOCKS = 64

        /** [atomOfUnit] of a unit without supply (a class emptied in place, ADR 0043). */
        const val NO_ATOM = -1
    }

    private data class GroupKey(val priority: Int, val eligible: BitSet)

    private class IntList {
        private var items = IntArray(4)
        private var size = 0

        val last: Int get() = if (size == 0) -1 else items[size - 1]

        fun add(value: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = value
        }

        fun toIntArray(): IntArray = items.copyOf(size)
    }
}

private inline fun BitSet.forEachSetBit(action: (Int) -> Unit) {
    var i = nextSetBit(0)
    while (i >= 0) {
        action(i)
        i = nextSetBit(i + 1)
    }
}

/**
 * Nested eligibility as chains (increment 10). Groups that differ only by delivery date have nested eligible sets (a
 * later date drops the supply that expires first), and a dense book has thousands of them: one edge per (atom, group)
 * makes the network large and every augmenting search slow. Here the groups' distinct eligible [sets] are ordered into
 * chains of nested sets, each a subset of the one before it. An atom enters each chain once, at the smallest set that
 * holds it ([entries]); flow passes from a set to the larger one before it ([parent]) and from each set to its groups.
 * A group is reachable from exactly the atoms eligible for it, and every edge between atoms and groups is unbounded,
 * so the network has the same finite cuts as one edge per (atom, group): every maximum flow figure is unchanged.
 */
class EligibilityChains(graph: CompressedFlowGraph) {
    val sets: List<BitSet>
    val setOfGroup: IntArray
    val parent: IntArray
    val entries: Array<IntArray>

    /** Edges between atoms and groups: atom → set, set → larger set, set → group. */
    val edgeCount: Int

    init {
        val index = LinkedHashMap<BitSet, Int>()
        setOfGroup = IntArray(graph.groups.size) { g -> index.getOrPut(graph.groups[g].eligibleUnits) { index.size } }
        sets = index.keys.toList()
        val size = IntArray(sets.size) { sets[it].cardinality() }
        parent = IntArray(sets.size) { -1 }
        val chains = ArrayList<MutableList<Int>>()
        val outside = BitSet()
        sets.indices.sortedWith(compareByDescending<Int> { size[it] }.thenBy { it }).forEach { s ->
            val chain = chains.firstOrNull { chain ->
                outside.clear()
                outside.or(sets[s])
                outside.andNot(sets[chain.last()])
                outside.isEmpty
            }
            if (chain == null) {
                chains += mutableListOf(s)
            } else {
                parent[s] = chain.last()
                chain += s
            }
        }
        // Membership along a chain is a prefix (the sets shrink), so the smallest set holding a unit is found by bisection.
        entries = Array(graph.atoms.size) { a ->
            val unit = graph.atoms[a].unitIndexes[0]
            chains.filter { sets[it[0]][unit] }.map { chain ->
                var low = 0
                var high = chain.size - 1
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (sets[chain[mid]][unit]) low = mid else high = mid - 1
                }
                chain[low]
            }.toIntArray()
        }
        edgeCount = entries.sumOf { it.size } + parent.count { it >= 0 } + setOfGroup.size
    }
}

/**
 * A max-flow over a [CompressedFlowGraph]: source → atom (its quantity) → group (unbounded, through
 * [EligibilityChains]) → sink (its demand).
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
    val chains = EligibilityChains(graph)
    val network = FlowGraph(probe + 1 + chains.sets.size, atomCount + groupCount + chains.edgeCount)
    private val supplyEdges = IntArray(atomCount)
    private val demandEdges = IntArray(groupCount) { -1 }

    fun atomNode(atom: Int): Int = 1 + atom

    fun groupNode(group: Int): Int = 1 + atomCount + group

    /** The vertex of an eligible set of [chains], after the probe. */
    private fun setNode(set: Int): Int = probe + 1 + set

    init {
        graph.atoms.forEach { atom ->
            supplyEdges[atom.index] = network.addEdge(source, atomNode(atom.index), atom.quantityMwh)
            chains.entries[atom.index].forEach { set -> network.addEdge(atomNode(atom.index), setNode(set), UNBOUNDED) }
        }
        chains.parent.forEachIndexed { set, larger -> if (larger >= 0) network.addEdge(setNode(set), setNode(larger), UNBOUNDED) }
        graph.groups.forEach { g -> network.addEdge(setNode(chains.setOfGroup[g.index]), groupNode(g.index), UNBOUNDED) }
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
                    val atom = graph.atomOfUnit[u]
                    if (signature != 0L && atom != CompressedFlowGraph.NO_ATOM) parts.merge(atom to signature, graph.units[u].quantityMwh, Long::plus)
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
            val atom = graph.atomOfUnit[u]
            if (atom != CompressedFlowGraph.NO_ATOM) byAtom[atom] += graph.units[u].quantityMwh
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
