package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.bucket
import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.valued
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.Assignment
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.CpSatSolver
import com.pianonurmi.etrm.allocation.domain.ExactSolverSettings
import com.pianonurmi.etrm.allocation.domain.PortfolioValuer
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.all
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.region
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/** The valuation-grade allocation of ADR 0039: most coverage, priority first, least cost, and the same every time. */
class PortfolioValueTest {
    private val anyGoo = bucket("ANY_GOO", energySource("RENEWABLE"), 1)
    private val euRenewable = bucket("EU_RENEWABLE", all(energySource("RENEWABLE"), region("EEA")), 2)
    private val nordicHydro = bucket("NORDIC_HYDRO", all(energySource("HYDRO"), region("NORDIC")), 4)
    private val buckets = listOf(anyGoo, euRenewable, nordicHydro)
    private val marks = mapOf("ANY_GOO" to "0.30", "EU_RENEWABLE" to "0.50", "NORDIC_HYDRO" to "2.00")
    private val priced: (String, Int) -> BigDecimal? = { code, year -> if (year == 2026) marks[code]?.let(::BigDecimal) else null }
    private val genericSale = energySource("RENEWABLE")
    private val hydroSale = all(energySource("HYDRO"), region("NORDIC"))

    @Test
    fun `generic supply serves the generic sale and the hydro goes to the hydro sale, the same every time`() {
        val wind = lot("DE", "WIND_ONSHORE", 100)
        val hydro = lot("FI", "HYDRO_RESERVOIR", 150)
        val problem = valued(
            listOf(hydro, wind),
            listOf(
                obligation(genericSale, 150, delivery = LocalDate.of(2026, 12, 1), bucket = "EU_RENEWABLE"),
                obligation(hydroSale, 100, delivery = LocalDate.of(2026, 12, 15), bucket = "NORDIC_HYDRO"),
            ),
            buckets,
            priced,
        )
        val assignments = PortfolioValuer.allocate(problem).shouldNotBeNull()
        byLot(problem, assignments) shouldBe mapOf((0 to wind.id) to 100L, (0 to hydro.id) to 50L, (1 to hydro.id) to 100L)
        // 50 MWh of hydro worth 2.00 delivered against a 0.50 sale: 75 EUR handed over, and nothing more.
        assignments.sumOf { (it.score.giveAwayPerMwh ?: BigDecimal.ZERO) * BigDecimal(it.quantityMwh) }.setScale(2) shouldBe BigDecimal("75.00")
        PortfolioValuer.allocate(problem) shouldBe assignments
    }

    @Test
    fun `when supply is short the higher priority obligation is covered first, even if it costs more`() {
        val hydro = lot("FI", "HYDRO_RESERVOIR", 100)
        val problem = valued(
            listOf(hydro),
            listOf(
                obligation(genericSale, 100, delivery = LocalDate.of(2026, 12, 1), priority = 2, bucket = "EU_RENEWABLE"),
                obligation(hydroSale, 100, delivery = LocalDate.of(2026, 12, 15), priority = 0, bucket = "NORDIC_HYDRO"),
            ),
            buckets,
            priced,
        )
        val assignments = PortfolioValuer.allocate(problem).shouldNotBeNull()
        assignments.single().obligationIndex shouldBe 1
    }

    @Test
    fun `it covers as much as the flow does and costs no more than the exact solver on random books`() {
        val exact = CpSatSolver(EngineFixtures::evaluate, ExactSolverSettings(timeLimit = Duration.ofSeconds(5), workers = 2))
        val random = Random(39)
        val sources = listOf("WIND_ONSHORE" to "DE", "HYDRO_RESERVOIR" to "FI", "SOLAR" to "ES", "HYDRO_RESERVOIR" to "SE")
        repeat(25) {
            val lots = (0 until random.nextInt(2, 7)).map {
                val (source, country) = sources[random.nextInt(sources.size)]
                lot(country, source, random.nextLong(20, 200), month = YearMonth.of(2026, random.nextInt(1, 10)))
            }
            val obligations = (0 until random.nextInt(1, 5)).map {
                val hydro = random.nextBoolean()
                obligation(
                    if (hydro) hydroSale else genericSale,
                    random.nextLong(20, 250),
                    delivery = LocalDate.of(2026, 12, random.nextInt(1, 28)),
                    bucket = if (hydro) "NORDIC_HYDRO" else "EU_RENEWABLE",
                )
            }
            val problem = valued(lots, obligations, buckets, priced)
            val valuation = PortfolioValuer.allocate(problem).shouldNotBeNull()
            val covered = CoverageAnalyser { null }.solve(problem).totalFlow
            valuation.sumOf { it.quantityMwh } shouldBe covered
            valuation.forEach { problem.isEligible(problem.classes[it.classIndex], problem.obligations[it.obligationIndex]) shouldBe true }
            val solved = exact.solve(problem)
            if (solved.sumOf { it.quantityMwh } == covered) {
                (cost(valuation) <= cost(solved)) shouldBe true
            }
        }
    }

    private fun cost(assignments: List<Assignment>) = assignments.sumOf { it.score.total * BigDecimal(it.quantityMwh) }

    private fun byLot(problem: AllocationProblem, assignments: List<Assignment>) =
        assignments.associate { (it.obligationIndex to problem.classes[it.classIndex].representative.id) to it.quantityMwh }
}
