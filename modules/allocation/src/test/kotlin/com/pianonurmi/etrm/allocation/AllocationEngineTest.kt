package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.problem
import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.platform.test.PropertySeeds
import com.pianonurmi.etrm.platform.test.TestClock
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.all
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.region
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@OptIn(io.kotest.common.ExperimentalKotest::class)
class AllocationEngineTest {
    private val solver = GreedySolver(EngineFixtures::evaluate)
    private val analyser = CoverageAnalyser { it.representative.energySourceCode }

    @Test
    fun `cheapest eligible class is used first and the specific obligation keeps scarce hydro`() {
        val nordicHydro = lot("FI", "HYDRO_RESERVOIR", 1_000, costBasis = BigDecimal("3.00"))
        val germanWind = lot("DE", "WIND_ONSHORE", 1_000, costBasis = BigDecimal("0.50"))
        val finnishWind = lot("FI", "WIND_ONSHORE", 1_000, costBasis = BigDecimal("1.00"))
        val hydroSale = obligation(all(region("NORDIC"), energySource("HYDRO")), 800)
        val anyEuropean = obligation(all(region("EEA"), energySource("RENEWABLE")), 1_500)
        val problem = problem(listOf(nordicHydro, germanWind, finnishWind), listOf(hydroSale, anyEuropean))

        val assignments = solver.solve(problem)
        val byObligation = assignments.groupBy { problem.obligations[it.obligationIndex].id }
        byObligation.getValue(hydroSale.id).single().let {
            problem.classes[it.classIndex].representative.id shouldBe nordicHydro.id
            it.quantityMwh shouldBe 800
        }
        val generic = byObligation.getValue(anyEuropean.id)
        generic.sumOf { it.quantityMwh } shouldBe 1_500
        generic.map { problem.classes[it.classIndex].representative.id }.toSet() shouldBe setOf(germanWind.id, finnishWind.id)

        val coverage = analyser.analyse(problem, EngineFixtures.entity, 1, TestClock.DEFAULT_START)
        coverage.totalShortfallMwh shouldBe 0
        coverage.shortageGroups.shouldBeEmpty()
        coverage.surplusByBucket.sumOf { it.surplusMwh } shouldBe 700
    }

    @Test
    fun `coverage is a flow not a sum - one lot cannot serve two obligations`() {
        val hydro = lot("NO", "HYDRO", 500)
        val a = obligation(region("NORDIC"), 400, priority = 1)
        val b = obligation(energySource("HYDRO"), 400, priority = 2)
        val problem = problem(listOf(hydro), listOf(a, b))
        val coverage = analyser.analyse(problem, EngineFixtures.entity, 1, TestClock.DEFAULT_START)
        coverage.totalDemandMwh shouldBe 800
        coverage.totalCoveredMwh shouldBe 500
        coverage.obligations.first { it.obligationId == a.id }.coveredMwh shouldBe 400
        coverage.obligations.first { it.obligationId == b.id }.coveredMwh shouldBe 100
        coverage.shortageGroups.single().let {
            it.obligationIds.toSet() shouldBe setOf(a.id, b.id)
            it.demandMwh shouldBe 800
            it.eligibleSupplyMwh shouldBe 500
            it.shortfallMwh shouldBe 300
        }
    }

    @Test
    fun `shortage groups explain joint shortfalls`() {
        val wind = lot("FI", "WIND_ONSHORE", 100)
        val solar = lot("ES", "SOLAR", 1_000)
        val x = obligation(energySource("WIND"), 80, priority = 1)
        val y = obligation(all(region("NORDIC"), energySource("RENEWABLE")), 80, priority = 1)
        val z = obligation(energySource("SOLAR"), 200, priority = 1)
        val coverage = analyser.analyse(problem(listOf(wind, solar), listOf(x, y, z)), EngineFixtures.entity, 1, TestClock.DEFAULT_START)
        coverage.obligations.first { it.obligationId == z.id }.shortfallMwh shouldBe 0
        coverage.shortageGroups shouldHaveSize 1
        coverage.shortageGroups.single().obligationIds.toSet() shouldBe setOf(x.id, y.id)
        coverage.shortageGroups.single().shortfallMwh shouldBe 60
    }

    @Test
    fun `share constraints - device cap forces diversification and min share fills the quota first`() {
        val device1 = lot("FI", "WIND_ONSHORE", 1_000, device = "D1", costBasis = BigDecimal("0.10"))
        val device2 = lot("FI", "WIND_ONSHORE", 1_000, device = "D2", costBasis = BigDecimal("0.20"))
        val device3 = lot("SE", "WIND_ONSHORE", 1_000, device = "D3", costBasis = BigDecimal("0.30"))
        val capped = obligation(energySource("WIND"), 900, constraints = listOf(AllocationConstraint.MaxSharePerDevice(40)))
        val problem = problem(listOf(device1, device2, device3), listOf(capped))
        val result = solver.solve(problem)
        result.sumOf { it.quantityMwh } shouldBe 900
        result.forEach { it.quantityMwh shouldBe (if (problem.classes[it.classIndex].deviceGsrn == "D3") 180L else 360L) }

        val swedishQuota = obligation(energySource("WIND"), 500, constraints = listOf(AllocationConstraint.MinShare(Criterion.country("SE"), 50)))
        val quotaProblem = problem(listOf(device1, device2, device3), listOf(swedishQuota))
        val quota = solver.solve(quotaProblem)
        quota.first { quotaProblem.classes[it.classIndex].deviceGsrn == "D3" }.quantityMwh shouldBe 250
        quota.sumOf { it.quantityMwh } shouldBe 500
    }

    @Test
    fun `expired-before-delivery lots are ineligible and expiring lots are preferred only when cheaper`() {
        val stale = lot("FI", "HYDRO", 300, expiry = LocalDate.of(2026, 11, 1))
        val fresh = lot("FI", "HYDRO", 300, expiry = LocalDate.of(2027, 6, 1))
        val sale = obligation(energySource("HYDRO"), 400, delivery = LocalDate.of(2026, 12, 15))
        val problem = problem(listOf(stale, fresh), listOf(sale))
        val result = solver.solve(problem)
        result.single().let {
            problem.classes[it.classIndex].representative.id shouldBe fresh.id
            it.quantityMwh shouldBe 300
        }
        analyser.analyse(problem, EngineFixtures.entity, 1, TestClock.DEFAULT_START).totalShortfallMwh shouldBe 100
    }

    @Test
    fun `solver invariants hold for random inventories and obligations`() {
        val sources = listOf("WIND_ONSHORE", "HYDRO_RESERVOIR", "SOLAR", "NUCLEAR")
        val countries = listOf("FI", "SE", "NO", "DE", "ES")
        val lotArb = Arb.bind(Arb.element(countries), Arb.element(sources), Arb.long(10, 2_000), Arb.int(1, 6)) { c, s, q, d ->
            lot(c, s, q, device = "DEV-$d", costBasis = BigDecimal(d))
        }
        val criterionArb = Arb.element(
            listOf(
                energySource("RENEWABLE"),
                energySource("WIND"),
                energySource("HYDRO"),
                energySource("SOLAR"),
                region("NORDIC"),
                region("EEA"),
                all(region("NORDIC"), energySource("HYDRO")),
                all(region("EEA"), energySource("WIND")),
                Criterion.country("DE"),
            ),
        )
        val constraintArb = Arb.element(
            listOf(emptyList(), listOf(AllocationConstraint.MaxSharePerDevice(50)), listOf(AllocationConstraint.MinShare(region("NORDIC"), 30))),
        )
        val obligationArb = Arb.bind(criterionArb, Arb.long(10, 1_500), Arb.int(1, 3), constraintArb) { c, q, p, k ->
            obligation(c, q, priority = p, constraints = k)
        }

        runBlocking {
            checkAll(PropTestConfig(iterations = 150, seed = PropertySeeds.seed(42L)), Arb.list(lotArb, 1..25), Arb.list(obligationArb, 1..12)) {
                lots,
                obligations,
                ->
                val problem = problem(lots, obligations)
                val result = solver.solve(problem)
                val again = solver.solve(problem)
                again shouldBe result
                // never over-allocate a class
                result.groupBy { it.classIndex }.forEach { (c, a) -> a.sumOf { it.quantityMwh } shouldBeLessThanOrEqualTo problem.classes[c].quantity }
                // never over-fill an obligation and only use eligible classes
                result.groupBy { it.obligationIndex }.forEach { (o, a) ->
                    a.sumOf { it.quantityMwh } shouldBeLessThanOrEqualTo problem.obligations[o].demand
                    a.forEach { problem.isEligible(problem.classes[it.classIndex], problem.obligations[o]) shouldBe true }
                }
                // device caps hold
                problem.obligations.forEach { obl ->
                    obl.maxSharePerDevice?.let { pct ->
                        result.filter { it.obligationIndex == obl.index }.groupBy { problem.classes[it.classIndex].deviceGsrn }
                            .forEach { (_, a) -> a.sumOf { it.quantityMwh } shouldBeLessThanOrEqualTo obl.demand * pct / 100 }
                    }
                }
                // greedy never beats the flow bound
                val coverage = analyser.analyse(problem, EngineFixtures.entity, 1, TestClock.DEFAULT_START)
                result.sumOf { it.quantityMwh } shouldBeLessThanOrEqualTo coverage.totalCoveredMwh
                coverage.obligations.forEach { it.coveredMwh shouldBeLessThanOrEqualTo it.demandMwh }
                coverage.totalCoveredMwh shouldBeLessThanOrEqualTo problem.classes.sumOf { it.quantity }
            }
        }
    }

    @Test
    fun `priority ordered augmentation never takes flow from a higher priority obligation`() {
        val hydro = lot("FI", "HYDRO", 300)
        val high = obligation(energySource("HYDRO"), 300, priority = 1, id = UUID.fromString("00000000-0000-0000-0000-00000000aaaa"))
        val low = obligation(region("NORDIC"), 300, priority = 2, id = UUID.fromString("00000000-0000-0000-0000-00000000bbbb"))
        val coverage = analyser.analyse(problem(listOf(hydro), listOf(low, high)), EngineFixtures.entity, 1, TestClock.DEFAULT_START)
        coverage.obligations.first { it.obligationId == high.id }.coveredMwh shouldBe 300
        coverage.obligations.first { it.obligationId == low.id }.coveredMwh shouldBe 0
    }
}
