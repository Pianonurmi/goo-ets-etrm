package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.problem
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.product.api.criteria.Criterion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Engine throughput check against the design targets (greedy over the full inventory < 60 s, coverage < 5 s).
 * Defaults are sized for CI; run with `-Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000` for the
 * full non-functional target. Timings are printed; the assertion only guards against pathological regressions.
 */
class AllocationBenchmarkTest {
    @Test
    fun `greedy solver and coverage analysis scale to the configured inventory`() {
        val lotCount = System.getProperty("etrm.benchmark.lots")?.toInt() ?: 20_000
        val obligationCount = System.getProperty("etrm.benchmark.obligations")?.toInt() ?: 1_000
        val random = Random(7)
        val sources = listOf("WIND_ONSHORE", "HYDRO_RESERVOIR", "SOLAR", "NUCLEAR", "HYDRO", "WIND")
        val countries = listOf("FI", "SE", "NO", "DK", "DE", "FR", "ES", "NL")
        val lots = List(lotCount) {
            lot(
                country = countries[random.nextInt(countries.size)],
                source = sources[random.nextInt(sources.size)],
                quantity = 50L + random.nextInt(2_000),
                device = "DEV-${random.nextInt(800)}",
                month = YearMonth.of(2026, 1 + random.nextInt(12)),
                costBasis = BigDecimal(random.nextInt(1, 500)).movePointLeft(2),
            )
        }
        val criteria = listOf(
            Criterion.energySource("RENEWABLE"),
            Criterion.energySource("WIND"),
            Criterion.energySource("HYDRO"),
            Criterion.energySource("SOLAR"),
            Criterion.region("NORDIC"),
            Criterion.region("EEA"),
            Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")),
            Criterion.all(Criterion.region("EEA"), Criterion.energySource("WIND"), Criterion.productionYear(2026)),
        )
        val obligations = List(obligationCount) {
            obligation(
                criteria[random.nextInt(criteria.size)],
                100L + random.nextInt(5_000),
                delivery = LocalDate.of(2026, 12, 1).plusDays(random.nextInt(60).toLong()),
                priority = 1 + random.nextInt(3),
            )
        }

        val buildMs = measureTimeMillis { problem(lots, obligations) }
        val problem = problem(lots, obligations)
        var assignments = 0
        val solveMs = measureTimeMillis { assignments = GreedySolver(EngineFixtures::evaluate).solve(problem).size }
        var covered = 0L
        val coverageMs = measureTimeMillis {
            covered = CoverageAnalyser { it.representative.energySourceCode }.analyse(problem, EngineFixtures.entity, 1, Instant.now()).totalCoveredMwh
        }
        println("benchmark lots=$lotCount classes=${problem.classes.size} obligations=$obligationCount build=${buildMs}ms")
        println("benchmark greedy=${solveMs}ms ($assignments assignments) coverage=${coverageMs}ms covered=$covered")
        (solveMs < 60_000) shouldBe true
        (coverageMs < 30_000) shouldBe true
    }
}
