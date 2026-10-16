package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.problem
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.CpSatSolver
import com.pianonurmi.etrm.allocation.domain.flow.CompressedFlowGraph
import com.pianonurmi.etrm.allocation.domain.flow.FlowDemand
import com.pianonurmi.etrm.allocation.domain.flow.FlowGraph
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.allocation.domain.flow.SupplyUnit
import com.pianonurmi.etrm.platform.test.PropertySeeds
import com.pianonurmi.etrm.platform.test.TestClock
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.all
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.region
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.BitSet

/** The compressed flow core (ADR 0036): same answers as the uncompressed graph, plus the headroom primitives. */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class FlowCoreTest {
    private val analyser = CoverageAnalyser { it.representative.energySourceCode }

    @Test
    fun `the compressed flow agrees with the uncompressed one on everything a maximum flow determines`() {
        val sources = listOf("WIND_ONSHORE", "HYDRO_RESERVOIR", "SOLAR", "NUCLEAR", "HYDRO")
        val countries = listOf("FI", "SE", "NO", "DE", "ES")
        val lotArb = Arb.bind(Arb.element(countries), Arb.element(sources), Arb.long(10, 2_000), Arb.int(1, 4)) { c, s, q, d ->
            lot(c, s, q, device = "DEV-$d")
        }
        val criterionArb = Arb.element(
            listOf(
                energySource("RENEWABLE"),
                energySource("WIND"),
                energySource("HYDRO"),
                region("NORDIC"),
                region("EEA"),
                all(region("NORDIC"), energySource("HYDRO")),
                Criterion.country("DE"),
            ),
        )
        val dates = listOf(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15))
        val obligationArb = Arb.bind(criterionArb, Arb.long(10, 1_500), Arb.int(1, 3), Arb.element(dates)) { c, q, p, d ->
            obligation(c, q, delivery = d, priority = p)
        }

        runBlocking {
            checkAll(PropTestConfig(iterations = 300, seed = PropertySeeds.seed(11L)), Arb.list(lotArb, 1..30), Arb.list(obligationArb, 1..14)) {
                lots,
                obligations,
                ->
                val problem = problem(lots, obligations)
                val reference = Reference(problem)
                val report = analyser.analyse(problem, EngineFixtures.entity, 1, TestClock.DEFAULT_START)

                report.totalCoveredMwh shouldBe reference.total
                // What each priority tier gets is fixed by the tiered augmentation, whichever maximum flow is found.
                problem.obligations.groupBy { it.priority }.forEach { (_, tier) ->
                    tier.sumOf { report.obligations[it.index].coveredMwh } shouldBe tier.sumOf { reference.covered[it.index] }
                }
                // The obligations left on the sink side of the minimum cut are the same for every maximum flow.
                val state = analyser.solve(problem)
                val reachable = state.reachableGroups()
                problem.obligations.forEach { obl -> reachable[state.graph.groupOfObligation[obl.index]] shouldBe reference.reachable[obl.index] }
                // Shortage groups are components of unreachable obligations; their shortfall is fixed too.
                report.shortageGroups.map { it.obligationIds.toSet() to it.shortfallMwh }.toSet() shouldBe reference.shortageGroups()
                report.surplusByBucket.sumOf { it.surplusMwh } shouldBe problem.classes.sumOf { it.quantity } - reference.total
                // Compression never adds nodes.
                state.graph.groups.size shouldBeLessThanOrEqualTo problem.obligations.size
                state.graph.atoms.size shouldBeLessThanOrEqualTo problem.classes.size
            }
        }
    }

    @Test
    fun `greedy ranking classes once per score key gives exactly the per-obligation result`() {
        val sources = listOf("WIND_ONSHORE", "HYDRO_RESERVOIR", "SOLAR", "HYDRO")
        val countries = listOf("FI", "SE", "DE", "ES")
        val lotArb = Arb.bind(Arb.element(countries), Arb.element(sources), Arb.long(10, 900), Arb.int(1, 9)) { c, s, q, d ->
            lot(c, s, q, device = "DEV-$d", costBasis = java.math.BigDecimal(d))
        }
        val obligationArb = Arb.bind(
            Arb.element(listOf(energySource("RENEWABLE"), energySource("HYDRO"), region("NORDIC"), all(region("NORDIC"), energySource("HYDRO")))),
            Arb.long(10, 1_200),
            Arb.int(1, 3),
            Arb.element(
                listOf(
                    emptyList(),
                    listOf(com.pianonurmi.etrm.allocation.api.AllocationConstraint.MaxSharePerDevice(40)),
                    listOf(com.pianonurmi.etrm.allocation.api.AllocationConstraint.MinShare(region("NORDIC"), 50)),
                ),
            ),
        ) { c, q, p, k -> obligation(c, q, priority = p, constraints = k) }
        val greedy = com.pianonurmi.etrm.allocation.domain.GreedySolver(EngineFixtures::evaluate)
        runBlocking {
            checkAll(PropTestConfig(iterations = 200, seed = PropertySeeds.seed(3L)), Arb.list(lotArb, 1..25), Arb.list(obligationArb, 1..10)) {
                lots,
                obligations,
                ->
                val keyed = problem(lots, obligations)
                val unkeyed = AllocationProblem(keyed.classes, keyed.obligations, keyed.eligible, scoring = keyed::score)
                greedy.solve(keyed) shouldBe greedy.solve(unkeyed)
            }
        }
    }

    @Test
    fun `obligations with the same eligible supply share one node and its flow goes out by delivery date`() {
        val wind = lot("DE", "WIND_ONSHORE", 500)
        val early = obligation(energySource("WIND"), 400, delivery = LocalDate.of(2026, 12, 1))
        val late = obligation(energySource("WIND"), 400, delivery = LocalDate.of(2026, 12, 20))
        val problem = problem(listOf(wind), listOf(late, early))
        val state = analyser.solve(problem)
        state.graph.groups.size shouldBe 1
        val report = analyser.analyse(problem, EngineFixtures.entity, 1, TestClock.DEFAULT_START)
        report.obligations.first { it.obligationId == early.id }.coveredMwh shouldBe 400
        report.obligations.first { it.obligationId == late.id }.coveredMwh shouldBe 100
    }

    @Test
    fun `headroom reroutes existing coverage but never takes it, and leaves the solved flow as it was`() {
        // Hydro (unit 0) and wind (unit 1); a generic sale of 100 that either can serve.
        val units = listOf(SupplyUnit.Held(0, 100), SupplyUnit.Held(1, 100))
        val both = BitSet().apply { set(0, 2) }
        val state = FlowState(CompressedFlowGraph.build(units, listOf(FlowDemand(0, 1, 100)), 1, { both }))
        val hydro = BitSet().apply { set(0) }
        val hydroOrWind = BitSet().apply { set(0, 2) }
        state.totalFlow shouldBe 100
        val before = state.network.snapshot()

        // A new hydro-only sale can take all of the hydro: the generic sale moves to wind if it was on hydro.
        state.headroom(hydro) shouldBe 100
        // A new sale either can serve finds only what is left over: 100.
        state.headroom(hydroOrWind) shouldBe 100
        state.headroom(hydro, limit = 30) shouldBe 30
        state.network.snapshot() shouldBe before
        state.groupFlow(0) shouldBe 100

        // Hydro only for the generic sale: nothing is free for a new hydro sale, and nothing is stolen from the old one.
        val hydroOnly = BitSet().apply { set(0) }
        val tight = FlowState(CompressedFlowGraph.build(units, listOf(FlowDemand(0, 1, 100)), 1, { hydroOnly }))
        tight.headroom(BitSet().apply { set(0) }) shouldBe 0
        tight.headroom(BitSet().apply { set(1) }) shouldBe 100
        tight.groupFlow(0) shouldBe 100
    }

    @Test
    fun `headroom is the largest extra demand that fits while every group keeps its flow`() {
        runBlocking {
            checkAll(
                PropTestConfig(iterations = 300, seed = PropertySeeds.seed(5L)),
                Arb.list(Arb.long(0, 60), 1..6),
                Arb.list(Arb.bind(Arb.long(0, 80), Arb.long(1, 63)) { d, mask -> d to mask }, 1..5),
                Arb.long(1, 63),
            ) { supplies, demandSpecs, newMask ->
                val units = supplies.mapIndexed { i, q -> SupplyUnit.Held(i, q) }
                val masks = demandSpecs.map { (_, mask) -> BitSet().apply { units.indices.filter { mask shr it and 1L == 1L }.forEach { set(it) } } }
                val demands = demandSpecs.mapIndexed { i, (d, _) -> FlowDemand(i, 1, d) }
                val state = FlowState(CompressedFlowGraph.build(units, demands, demands.size, { masks[it] }))
                val newUnits = units.indices.filter { newMask shr it and 1L == 1L }
                val h = state.headroom(BitSet().apply { newUnits.forEach { set(it) } })
                val kept = state.graph.groups.map { state.groupFlow(it.index) }

                fun fits(extra: Long): Boolean {
                    // Every group must get exactly its current flow (its sink edge is capped at it) and the new demand `extra`.
                    val sink = 1 + units.size + state.graph.groups.size + 1
                    val g = FlowGraph(sink + 1)
                    units.forEach { g.addEdge(0, 1 + it.classIndex, it.quantityMwh) }
                    state.graph.groups.forEach { grp ->
                        val node = 1 + units.size + grp.index
                        var u = grp.eligibleUnits.nextSetBit(0)
                        while (u >= 0) {
                            g.addEdge(1 + u, node, FlowState.UNBOUNDED)
                            u = grp.eligibleUnits.nextSetBit(u + 1)
                        }
                        g.addEdge(node, sink, kept[grp.index])
                    }
                    val newNode = sink - 1
                    newUnits.forEach { g.addEdge(1 + it, newNode, FlowState.UNBOUNDED) }
                    g.addEdge(newNode, sink, extra)
                    return g.augment(0, sink) == kept.sum() + extra
                }
                fits(h) shouldBe true
                fits(h + 1) shouldBe false
            }
        }
    }

    @Test
    fun `a short demand names the exhausted supply that limits it, and free supply never exceeds headroom`() {
        // Hydro (unit 0, 100) of which a hydro sale takes 80; wind (unit 1, 100) nobody needs.
        val units = listOf(SupplyUnit.Held(0, 100), SupplyUnit.Held(1, 100))
        val hydro = BitSet().apply { set(0) }
        val state = FlowState(CompressedFlowGraph.build(units, listOf(FlowDemand(0, 1, 80)), 1, { hydro }, atomKey = { it }))

        val short = state.headroomDetail(hydro)
        short.mwh shouldBe 20
        short.freeMwh shouldBe 20
        short.bindingAtoms.map { state.graph.atoms[it].key } shouldBe listOf(0)

        val either = state.headroomDetail(BitSet().apply { set(0, 2) })
        either.mwh shouldBe 120
        either.freeMwh shouldBe 120
    }

    @Test
    fun `several new demands together get the most that fits while every group keeps its flow`() {
        runBlocking {
            checkAll(
                PropTestConfig(iterations = 300, seed = PropertySeeds.seed(6L)),
                Arb.list(Arb.long(0, 60), 1..6),
                Arb.list(Arb.bind(Arb.long(0, 80), Arb.long(1, 63)) { d, mask -> d to mask }, 1..4),
                Arb.list(Arb.bind(Arb.long(0, 90), Arb.long(1, 63)) { d, mask -> d to mask }, 1..3),
            ) { supplies, demandSpecs, newSpecs ->
                val units = supplies.mapIndexed { i, q -> SupplyUnit.Held(i, q) }

                fun maskOf(mask: Long) = BitSet().apply { units.indices.filter { mask shr it and 1L == 1L }.forEach { set(it) } }
                val masks = demandSpecs.map { (_, mask) -> maskOf(mask) }
                val demands = demandSpecs.mapIndexed { i, (d, _) -> FlowDemand(i, 1, d) }
                val state = FlowState(CompressedFlowGraph.build(units, demands, demands.size, { masks[it] }))
                val before = state.network.snapshot()
                val news = newSpecs.map { (q, mask) -> maskOf(mask) to q }
                val joint = state.jointHeadroom(news)!!
                state.network.snapshot() shouldBe before
                news.forEach { (eligible, _) -> state.headroomDetail(eligible).let { it.freeMwh shouldBeLessThanOrEqual it.mwh } }
                val kept = state.graph.groups.map { state.groupFlow(it.index) }

                fun fits(total: Long): Boolean {
                    // Groups keep exactly their flow; the new demands, each up to its quantity, get `total` together.
                    val groups = state.graph.groups.size
                    val sink = 1 + units.size + groups + news.size + 1
                    val g = FlowGraph(sink + 1)
                    units.forEach { g.addEdge(0, 1 + it.classIndex, it.quantityMwh) }
                    state.graph.groups.forEach { grp ->
                        val node = 1 + units.size + grp.index
                        var u = grp.eligibleUnits.nextSetBit(0)
                        while (u >= 0) {
                            g.addEdge(1 + u, node, FlowState.UNBOUNDED)
                            u = grp.eligibleUnits.nextSetBit(u + 1)
                        }
                        g.addEdge(node, sink, kept[grp.index])
                    }
                    val collector = sink - 1
                    news.forEachIndexed { i, (eligible, q) ->
                        val node = 1 + units.size + groups + i
                        var u = eligible.nextSetBit(0)
                        while (u >= 0) {
                            g.addEdge(1 + u, node, FlowState.UNBOUNDED)
                            u = eligible.nextSetBit(u + 1)
                        }
                        g.addEdge(node, collector, q)
                    }
                    g.addEdge(collector, sink, total)
                    return g.augment(0, sink) == kept.sum() + total
                }
                fits(joint) shouldBe true
                fits(joint + 1) shouldBe false
            }
        }
    }

    @Test
    fun `a residual path far longer than a recursive search could follow is augmented`() {
        val length = 200_000
        val graph = FlowGraph(length + 2, length + 1)
        graph.addEdge(0, 1, 5)
        for (v in 1 until length) graph.addEdge(v, v + 1, 5)
        graph.addEdge(length, length + 1, 5)
        graph.augment(0, length + 1) shouldBe 5
    }

    @Test
    fun `the exact solver's floor compares coverage per priority tier, then shortfall, then cost`() {
        val base = CpSatSolver.Objectives(coveredByTier = listOf(100, 50), shortfall = 10, cost = 500)
        CpSatSolver.Objectives(listOf(99, 200), 0, 0).worseThan(base) shouldBe "coverage of priority tier 1"
        CpSatSolver.Objectives(listOf(101, 0), 50, 900).worseThan(base) shouldBe null
        CpSatSolver.Objectives(listOf(100, 49), 0, 0).worseThan(base) shouldBe "coverage of priority tier 2"
        CpSatSolver.Objectives(listOf(100, 50), 11, 0).worseThan(base) shouldBe "minimum-share shortfall"
        CpSatSolver.Objectives(listOf(100, 50), 9, 900).worseThan(base) shouldBe null
        CpSatSolver.Objectives(listOf(100, 50), 10, 501).worseThan(base) shouldBe "cost"
        CpSatSolver.Objectives(listOf(100, 50), 10, 500).worseThan(base) shouldBe null
    }

    /** The uncompressed reference: one node per class and per obligation, augmented tier by tier. */
    private class Reference(private val problem: AllocationProblem) {
        val covered = LongArray(problem.obligations.size)
        val reachable: BooleanArray
        val total: Long

        init {
            val c = problem.classes.size
            val o = problem.obligations.size
            val sink = 1 + c + o
            val graph = FlowGraph(sink + 1)
            problem.classes.forEach { graph.addEdge(0, 1 + it.index, it.quantity) }
            problem.obligations.forEach { obl ->
                problem.eligible[obl.index].stream().forEach { ci -> graph.addEdge(1 + ci, 1 + c + obl.index, FlowState.UNBOUNDED) }
            }
            val demandEdges = IntArray(o)
            problem.obligations.groupBy { it.priority }.toSortedMap().values.forEach { tier ->
                tier.forEach { demandEdges[it.index] = graph.addEdge(1 + c + it.index, sink, it.demand) }
                graph.augment(0, sink)
            }
            problem.obligations.forEach { covered[it.index] = graph.flow(demandEdges[it.index]) }
            val seen = graph.reachableFrom(0)
            reachable = BooleanArray(o) { seen[1 + c + it] }
            total = covered.sum()
        }

        fun shortageGroups(): Set<Pair<Set<java.util.UUID>, Long>> {
            val short = problem.obligations.filter { !reachable[it.index] }
            val seen = HashSet<Int>()
            val groups = mutableSetOf<Pair<Set<java.util.UUID>, Long>>()
            short.forEach { start ->
                if (!seen.add(start.index)) return@forEach
                val members = mutableListOf(start)
                var i = 0
                while (i < members.size) {
                    val bits = problem.eligible[members[i].index]
                    short.filter { it.index !in seen && bits.intersects(problem.eligible[it.index]) }.forEach {
                        seen.add(it.index)
                        members += it
                    }
                    i++
                }
                val shortfall = members.sumOf { it.demand - covered[it.index] }
                if (shortfall > 0) groups += members.map { it.id }.toSet() to shortfall
            }
            return groups
        }
    }
}
