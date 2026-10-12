package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.bucket
import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.valued
import com.pianonurmi.etrm.allocation.api.PricingStatus
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.Assignment
import com.pianonurmi.etrm.allocation.domain.CpSatSolver
import com.pianonurmi.etrm.allocation.domain.ExactSolverSettings
import com.pianonurmi.etrm.allocation.domain.GlopPricer
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.allocation.domain.PricingSettings
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.country
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import kotlin.math.abs

/** Shadow prices from the pricing LP (ADR 0035): hand-computed cases and LP duality on random problems. */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class ShadowPriceTest {
    private val greedy = GreedySolver(EngineFixtures::evaluate)
    private val exact = CpSatSolver(EngineFixtures::evaluate, ExactSolverSettings(timeLimit = Duration.ofSeconds(5), workers = 2))
    private val pricer = GlopPricer(EngineFixtures::evaluate)

    /** One bucket per country, so a lot's value is its country's mark. */
    private val countries = listOf("FI", "SE", "NO", "DE", "FR")
    private val buckets = countries.mapIndexed { i, c -> bucket("C_$c", country(c), i + 1) }

    private fun marked(vararg marks: Pair<String, String>): (String, Int) -> BigDecimal? {
        val byBucket = marks.associate { (c, m) -> "C_$c" to BigDecimal(m) }
        return { code, _ -> byBucket[code] }
    }

    @Test
    fun `the marginal MWh comes from the dearer lot, and the cheap one earns the difference as rent`() {
        val finnish = lot("FI", "HYDRO_RESERVOIR", 100)
        val german = lot("DE", "WIND_ONSHORE", 100)
        val problem = valued(listOf(finnish, german), listOf(obligation(energySource("RENEWABLE"), 150)), buckets, marked("FI" to "1.00", "DE" to "3.00"))

        val assignments = greedy.solve(problem)
        val duals = pricer.solve(problem, assignments)
        duals.status shouldBe PricingStatus.OPTIMAL
        duals.lpCost shouldBe (250.0 plusOrMinus 1e-6)
        duals.marginalCost[0] shouldBe (3.0 plusOrMinus 1e-6)
        duals.rent[classOf(problem, "FI")] shouldBe (2.0 plusOrMinus 1e-6)
        duals.rent[classOf(problem, "DE")] shouldBe (0.0 plusOrMinus 1e-6)

        val pricing = pricer.price(problem, assignments) { cls -> "C_${cls.representative.deviceCountry}" }
        pricing.gapEur shouldBe BigDecimal("0.00")
        pricing.obligations.single().marginalCostEur shouldBe BigDecimal("3.0000")
        pricing.supply.first().let {
            it.bucketCode shouldBe "C_FI"
            it.maxRentEur shouldBe BigDecimal("2.0000")
            it.usedMwh shouldBe 100
        }
    }

    @Test
    fun `an obligation served only by cheap supply still has the marginal cost of the rerouting it causes`() {
        // One more MWh for the Finnish-only sale takes Finnish supply from the generic sale, which then buys German.
        val finnish = lot("FI", "HYDRO_RESERVOIR", 100)
        val german = lot("DE", "WIND_ONSHORE", 100)
        val finnishOnly = obligation(country("FI"), 80)
        val generic = obligation(energySource("RENEWABLE"), 50)
        val problem = valued(listOf(finnish, german), listOf(finnishOnly, generic), buckets, marked("FI" to "1.00", "DE" to "3.00"))

        val duals = pricer.solve(problem, greedy.solve(problem))
        duals.marginalCost[0] shouldBe (3.0 plusOrMinus 1e-6)
        duals.marginalCost[1] shouldBe (3.0 plusOrMinus 1e-6)
        duals.rent[classOf(problem, "FI")] shouldBe (2.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a plan above the size limit, or with nothing allocated, has no shadow prices and says why`() {
        val problem = valued(listOf(lot("FI", "HYDRO_RESERVOIR", 100)), listOf(obligation(energySource("RENEWABLE"), 10)), buckets, marked("FI" to "1.00"))
        GlopPricer(EngineFixtures::evaluate, PricingSettings(maxPairs = 0)).price(problem, greedy.solve(problem)) { null }.let {
            it.status shouldBe PricingStatus.SKIPPED
            it.reason!! shouldContain "too large"
        }
        pricer.price(problem, emptyList()) { null }.reason shouldBe "nothing allocated"
    }

    @Test
    fun `duals satisfy LP duality on random problems, and an optimal exact plan has no gap`() {
        runBlocking { randomDuality() }
    }

    private suspend fun randomDuality() {
        val sources = listOf("HYDRO_RESERVOIR", "WIND_ONSHORE", "SOLAR")
        val lotArb = Arb.bind(Arb.element(countries), Arb.element(sources), Arb.long(10L..200L)) { c, s, q -> Triple(c, s, q) }
        val oblArb = Arb.bind(Arb.set(Arb.element(countries), 1..3), Arb.long(10L..250L)) { cs, q -> cs to q }
        val markArb = Arb.list(Arb.int(10..300), countries.size..countries.size)
        checkAll(PropTestConfig(seed = 8L, iterations = 40), Arb.list(lotArb, 1..8), Arb.list(oblArb, 1..5), markArb) { lotSpecs, oblSpecs, cents ->
            val lots = lotSpecs.mapIndexed { i, (c, s, q) -> lot(c, s, q, device = "DEV-$i") }
            val obligations = oblSpecs.map { (cs, q) -> obligation(country(*cs.toTypedArray()), q) }
            val marks = countries.zip(cents).associate { (c, m) -> "C_$c" to BigDecimal(m).movePointLeft(2) }
            val problem = valued(lots, obligations, buckets, { code, _ -> marks[code] })

            listOf(greedy.solveDetailed(problem), exact.solveDetailed(problem)).forEach { result ->
                val assignments = result.assignments
                if (assignments.isEmpty()) return@forEach
                val duals = pricer.solve(problem, assignments)
                duals.status shouldBe PricingStatus.OPTIMAL
                checkDuality(problem, assignments, duals)
                val planCost = assignments.sumOf { it.score.total.toDouble() * it.quantityMwh }
                duals.lpCost shouldBeLessThanOrEqual planCost + 1e-6
                if (result.solver == "CP_SAT" && result.details["status"] == "OPTIMAL") {
                    duals.lpCost shouldBe (planCost plusOrMinus 1e-4)
                }
            }
        }
    }

    /** Reduced costs are non-negative and zero where the LP delivers; the dual objective equals the primal one. */
    private fun checkDuality(problem: AllocationProblem, assignments: List<Assignment>, duals: GlopPricer.Duals) {
        duals.flow.forEach { (pair, x) ->
            val (c, o) = pair
            val reduced = problem.score(problem.classes[c], problem.obligations[o]).total.toDouble() - duals.marginalCost[o] + duals.rent[c]
            reduced shouldBeGreaterThanOrEqual -1e-6
            if (x > 1e-6) abs(reduced) shouldBeLessThanOrEqual 1e-6
        }
        val covered = LongArray(problem.obligations.size).also { cov -> assignments.forEach { cov[it.obligationIndex] += it.quantityMwh } }
        val inModel = duals.flow.keys.map { it.first }.toSet()
        val dual = problem.obligations.filter { !duals.marginalCost[it.index].isNaN() }.sumOf { duals.marginalCost[it.index] * covered[it.index] } -
            inModel.sumOf { duals.rent[it] * problem.classes[it].quantity }
        dual shouldBe (duals.lpCost plusOrMinus 1e-4 * maxOf(1.0, abs(duals.lpCost)))
    }

    private fun classOf(problem: AllocationProblem, country: String) = problem.classes.single { it.representative.deviceCountry == country }.index
}
