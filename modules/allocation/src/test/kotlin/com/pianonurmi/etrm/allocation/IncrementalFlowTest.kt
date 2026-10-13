package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.application.ProblemBuilder
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.inventory.api.LotSnapshot
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.product.api.criteria.Criterion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.random.Random

/**
 * A patched index (ADR 0043) is the problem a full build makes: random sequences of lot changes (receipts, splits,
 * reservations and releases, removals, corrections that move a lot to another class) and obligation changes are
 * applied to one index, and after every step its publication is compared with a fresh build of the same lots and
 * obligations: classes, eligibility, the compressed graph, coverage, residuals and headroom.
 */
class IncrementalFlowTest {
    private val asOf = LocalDate.of(2026, 11, 1)
    private val builder = ProblemBuilder(FixturePorts.Criteria, FixturePorts.buckets(asOf), FixturePorts.routes(), GooMarks { _, _, _ -> BigDecimal.ONE })
    private val criteria = listOf(
        Criterion.energySource("RENEWABLE"),
        Criterion.energySource("WIND"),
        Criterion.energySource("HYDRO"),
        Criterion.region("NORDIC"),
        Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")),
        Criterion.all(Criterion.region("EEA"), Criterion.energySource("WIND"), Criterion.productionYear(2026)),
    )

    @Test
    fun `a patched index publishes what a full build makes`() {
        repeat(SEEDS) { seed ->
            val world = World(Random(seed))
            val index = builder.index(world.live(), world.obligations.values.toList())
            repeat(STEPS) { step ->
                world.change()
                val (changed, removed) = index.diff(world.live().associate { it.id to it.version })
                index.applyLots(changed.map { world.lots.getValue(it) }, removed)
                index.setObligations(world.obligations.values.toList())
                compare(
                    builder.problem(index, asOf),
                    builder.build(LotSnapshot(EngineFixtures.entity, 0, world.live()), world.obligations.values.toList(), emptyMap(), asOf),
                    "seed $seed step $step",
                )
            }
        }
    }

    private fun compare(patched: ProblemBuilder.Built, full: ProblemBuilder.Built, at: String) {
        val p = patched.problem
        val f = full.problem
        val liveP = p.classes.filter { it.quantity > 0 }
        withClue(at) {
            liveP.associate { it.key to it.quantity } shouldBe f.classes.associate { it.key to it.quantity }
            p.obligations.map { it.id } shouldBe f.obligations.map { it.id }
            p.obligations.indices.forEach { o ->
                keys(p, p.eligible[o]) shouldBe keys(f, f.eligible[o])
            }
            val ps = CoverageAnalyser(patched.scoring::bestBucketCode).solve(p)
            val fs = CoverageAnalyser(full.scoring::bestBucketCode).solve(f)
            ps.graph.groups.size shouldBe fs.graph.groups.size
            ps.graph.atoms.map { atom -> atom.unitIndexes.map { p.classes[it].key } to atom.groupIndexes.toList() } shouldBe
                fs.graph.atoms.map { atom -> atom.unitIndexes.map { f.classes[it].key } to atom.groupIndexes.toList() }
            ps.obligationCoverage(p.obligations.size).toList() shouldBe fs.obligationCoverage(f.obligations.size).toList()
            residuals(p, ps) shouldBe residuals(f, fs)
            criteria.forEach { c ->
                val date = LocalDate.of(2026, 12, 15)
                ps.headroom(builder.eligible(p.classes, c, date, null)) shouldBe fs.headroom(builder.eligible(f.classes, c, date, null))
            }
        }
    }

    private fun keys(problem: com.pianonurmi.etrm.allocation.domain.AllocationProblem, bits: java.util.BitSet): Set<String> =
        bits.stream().mapToObj { problem.classes[it].key }.toList().toSet()

    private fun residuals(problem: com.pianonurmi.etrm.allocation.domain.AllocationProblem, state: FlowState): Map<String, Long> =
        state.unitResidual().withIndex().filter { problem.classes[it.index].quantity > 0 }.associate { problem.classes[it.index].key to it.value }

    private fun <T> withClue(clue: String, block: () -> T): T = io.kotest.assertions.withClue(clue, block)

    /** Lots and obligations of one holder, changed at random in the ways the system changes them. */
    private inner class World(private val random: Random) {
        val lots = LinkedHashMap<UUID, LotView>()
        val obligations = LinkedHashMap<UUID, ObligationView>()

        init {
            repeat(INITIAL_LOTS) { newLot().let { lots[it.id] = it } }
            repeat(INITIAL_OBLIGATIONS) { newObligation().let { obligations[it.id] = it } }
        }

        fun live(): List<LotView> = lots.values.filter { it.status in LotStatus.LIVE }

        fun change() {
            val held = lots.values.filter { it.status == LotStatus.HELD }
            when (random.nextInt(CHANGES)) {
                0 -> newLot().let { lots[it.id] = it }
                // Another lot of an existing class: same attributes, its own id.
                1 -> held.randomOrNull(random)?.let { twin ->
                    Ids.newId().let { id -> lots[id] = twin.copy(id = id, rootLotId = id, quantityMwh = 10L + random.nextInt(500), version = 1) }
                }
                2 -> held.randomOrNull(random)?.takeIf { it.quantityMwh > 1 }?.let { split(it) }
                3 -> held.randomOrNull(random)?.let { bump(it.copy(status = LotStatus.RESERVED)) }
                4 -> lots.values.filter { it.status == LotStatus.RESERVED }.randomOrNull(random)?.let { bump(it.copy(status = LotStatus.HELD)) }
                5 -> held.randomOrNull(random)?.let { bump(it.copy(status = if (random.nextBoolean()) LotStatus.TRANSFERRED_OUT else LotStatus.CANCELLED)) }
                // A correction moves the lot to another class (a new key, or one that exists).
                6 -> held.randomOrNull(random)?.let { bump(it.copy(expiryDate = it.expiryDate.plusMonths(random.nextLong(-2, 3)))) }
                7 -> held.randomOrNull(random)?.let { bump(it.copy(labels = if (it.labels.isEmpty()) setOf("EKOENERGY") else emptySet())) }
                8 -> newObligation().let { obligations[it.id] = it }
                9 -> obligations.keys.randomOrNull(random)?.let { obligations.remove(it) }
                10 -> obligations.values.randomOrNull(random)?.let { o ->
                    // Delivery in flight: the remaining quantity changes without a new version.
                    obligations[o.id] = o.copy(fulfilledMwh = random.nextLong(0, o.quantityMwh + 1))
                }
                else -> obligations.values.randomOrNull(random)?.let { o ->
                    obligations[o.id] = o.copy(criterion = criteria.random(random), version = o.version + 1)
                }
            }
        }

        private fun bump(lot: LotView) {
            lots[lot.id] = lot.copy(version = lot.version + 1)
        }

        private fun split(lot: LotView) {
            val carved = 1L + random.nextLong(lot.quantityMwh - 1)
            bump(lot.copy(quantityMwh = lot.quantityMwh - carved))
            val id = Ids.newId()
            lots[id] = lot.copy(id = id, quantityMwh = carved, parentLotId = lot.id, version = 1)
        }

        private fun newLot(): LotView = lot(
            country = listOf("FI", "SE", "DE", "ES").random(random),
            source = listOf("WIND", "HYDRO", "SOLAR", "WIND_ONSHORE").random(random),
            quantity = 10L + random.nextInt(800),
            device = "DEV-${random.nextInt(6)}",
            month = YearMonth.of(2026, 1 + random.nextInt(4)),
            registry = listOf("FINGRID", "STATNETT", "ISLAND").random(random),
            expiry = LocalDate.of(2026, 12, 1).plusDays(random.nextLong(-20, 60)),
        )

        private fun newObligation(): ObligationView = obligation(
            criteria.random(random),
            50L + random.nextInt(1_500),
            delivery = LocalDate.of(2026, 12, 1).plusDays(random.nextLong(0, 40)),
            priority = 1 + random.nextInt(2),
        ).copy(targetRegistryCode = listOf(null, "FINGRID", "STATNETT").random(random))
    }

    private companion object {
        const val SEEDS = 40
        const val STEPS = 30
        const val INITIAL_LOTS = 40
        const val INITIAL_OBLIGATIONS = 12
        const val CHANGES = 12
    }
}
