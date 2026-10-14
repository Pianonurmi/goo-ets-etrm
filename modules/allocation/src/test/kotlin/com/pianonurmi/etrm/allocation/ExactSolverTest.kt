package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.problem
import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.domain.Assignment
import com.pianonurmi.etrm.allocation.domain.CpSatSolver
import com.pianonurmi.etrm.allocation.domain.ExactSolverSettings
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.platform.test.PropertySeeds
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.all
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.country
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.region
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
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
import java.time.Duration
import java.time.LocalDate

/** The CP-SAT solver against greedy on problems where greedy's ordering is not optimal. */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class ExactSolverTest {
    private val greedy = GreedySolver(EngineFixtures::evaluate)
    private val exact = CpSatSolver(EngineFixtures::evaluate, ExactSolverSettings(timeLimit = Duration.ofSeconds(5), workers = 2))

    private fun covered(assignments: List<Assignment>) = assignments.sumOf { it.quantityMwh }

    private fun cost(assignments: List<Assignment>): BigDecimal =
        assignments.sumOf { it.score.total * BigDecimal(it.quantityMwh) }

    @Test
    fun `exact covers a cycle of overlapping demands that greedy leaves short`() {
        // Three obligations, each eligible for two of three lots; the cheapest pick for the first two leaves the
        // third with nothing. A perfect assignment exists.
        val finnishHydro = lot("FI", "HYDRO_RESERVOIR", 100, costBasis = BigDecimal("1.00"))
        val germanWind = lot("DE", "WIND_ONSHORE", 100, costBasis = BigDecimal("3.00"))
        val swedishSolar = lot("SE", "SOLAR", 100, costBasis = BigDecimal("2.00"))
        val problem = problem(
            listOf(finnishHydro, germanWind, swedishSolar),
            listOf(
                obligation(country("FI", "DE"), 100, delivery = LocalDate.of(2026, 12, 1)),
                obligation(country("DE", "SE"), 100, delivery = LocalDate.of(2026, 12, 2)),
                obligation(country("FI", "SE"), 100, delivery = LocalDate.of(2026, 12, 3)),
            ),
        )

        covered(greedy.solve(problem)) shouldBe 200
        val result = exact.solveDetailed(problem)
        result.solver shouldBe "CP_SAT"
        result.details["status"] shouldBe "OPTIMAL"
        covered(result.assignments) shouldBe 300
        problem.obligations.forEach { obl -> result.assignments.filter { it.obligationIndex == obl.index }.sumOf { it.quantityMwh } shouldBe 100 }
    }

    @Test
    fun `with full coverage either way, exact keeps the cheap lot for the obligation that has no cheap alternative`() {
        val finnishHydro = lot("FI", "HYDRO_RESERVOIR", 100, costBasis = BigDecimal("0.50"))
        val germanWind = lot("DE", "WIND_ONSHORE", 100, costBasis = BigDecimal("1.00"))
        val swedishWind = lot("SE", "WIND_ONSHORE", 100, costBasis = BigDecimal("5.00"))
        val anyEuropean = obligation(all(region("EEA"), energySource("RENEWABLE")), 100)
        val nordic = obligation(region("NORDIC"), 50)
        val problem = problem(listOf(finnishHydro, germanWind, swedishWind), listOf(anyEuropean, nordic))

        val greedyResult = greedy.solve(problem)
        val exactResult = exact.solve(problem)

        covered(exactResult) shouldBe covered(greedyResult)
        covered(exactResult) shouldBe 150
        cost(exactResult) shouldBeLessThan cost(greedyResult)
        val nordicLines = exactResult.filter { it.obligationIndex == 1 }
        nordicLines.map { problem.classes[it.classIndex].representative.id } shouldBe listOf(finnishHydro.id)
    }

    @Test
    fun `device caps and minimum shares hold in the exact solution`() {
        val cheapHydro = lot("FI", "HYDRO_RESERVOIR", 100, device = "DEV-1", costBasis = BigDecimal("0.10"))
        val otherHydro = lot("SE", "HYDRO_RESERVOIR", 100, device = "DEV-2", costBasis = BigDecimal("0.20"))
        val wind = lot("FI", "WIND_ONSHORE", 100, device = "DEV-3", costBasis = BigDecimal("4.00"))
        val sale = obligation(
            region("NORDIC"),
            100,
            constraints = listOf(
                AllocationConstraint.MaxSharePerDevice(50),
                AllocationConstraint.MinShare(energySource("WIND"), 30),
            ),
        )
        val problem = problem(listOf(cheapHydro, otherHydro, wind), listOf(sale))

        val result = exact.solve(problem)

        covered(result) shouldBe 100
        result.groupBy { problem.classes[it.classIndex].deviceGsrn }.values.forEach { it.sumOf { a -> a.quantityMwh } shouldBeLessThanOrEqualTo 50 }
        result.filter { problem.classes[it.classIndex].representative.energySourceCode == "WIND_ONSHORE" }.sumOf { it.quantityMwh } shouldBe 30
    }

    @Test
    fun `a problem above the size limit is handed to greedy and says why`() {
        val problem = problem(listOf(lot("FI", "HYDRO_RESERVOIR", 100), lot("SE", "SOLAR", 100)), listOf(obligation(region("NORDIC"), 150)))
        val small = CpSatSolver(EngineFixtures::evaluate, ExactSolverSettings(maxPairs = 1))

        val result = small.solveDetailed(problem)

        result.solver shouldBe "GREEDY"
        result.details.keys shouldContain "fallbackReason"
        result.assignments shouldBe greedy.solve(problem)
    }

    @Test
    fun `exact never covers less than greedy and never exceeds supply or demand`() {
        runBlocking { neverWorseThanGreedy() }
    }

    private suspend fun neverWorseThanGreedy() {
        val countries = listOf("FI", "SE", "NO", "DE", "FR")
        val sources = listOf("HYDRO_RESERVOIR", "WIND_ONSHORE", "SOLAR")
        val lots = Arb.list(
            Arb.bind(Arb.element(countries), Arb.element(sources), Arb.long(10L..200L), Arb.int(1..9)) { c, s, q, cost ->
                lot(c, s, q, costBasis = BigDecimal(cost))
            },
            1..8,
        )
        val obligations = Arb.list(
            Arb.bind(Arb.element(countries), Arb.element(countries), Arb.long(10L..250L)) { a, b, q -> obligation(country(a, b), q) },
            1..5,
        )
        checkAll(PropTestConfig(iterations = 40, seed = PropertySeeds.seed(6L)), lots, obligations) { ls, os ->
            val problem = problem(ls, os)
            val g = greedy.solve(problem)
            val x = exact.solve(problem)
            covered(x) shouldBeGreaterThanOrEqualTo covered(g)
            problem.classes.forEach { cls -> x.filter { it.classIndex == cls.index }.sumOf { it.quantityMwh } shouldBeLessThanOrEqualTo cls.quantity }
            problem.obligations.forEach { obl -> x.filter { it.obligationIndex == obl.index }.sumOf { it.quantityMwh } shouldBeLessThanOrEqualTo obl.demand }
            x.filter { !problem.isEligible(problem.classes[it.classIndex], problem.obligations[it.obligationIndex]) }.shouldBeEmpty()
        }
    }
}
