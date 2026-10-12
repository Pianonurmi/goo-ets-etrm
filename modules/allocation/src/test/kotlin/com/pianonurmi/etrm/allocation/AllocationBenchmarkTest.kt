package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.problem
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.product.api.criteria.Criterion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.BitSet
import kotlin.random.Random
import kotlin.system.measureNanoTime
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
        val analyser = CoverageAnalyser { it.representative.energySourceCode }
        val coverageMs = measureTimeMillis {
            covered = analyser.analyse(problem, EngineFixtures.entity, 1, Instant.now()).totalCoveredMwh
        }
        val pairs = problem.eligible.sumOf { it.cardinality().toLong() }
        lateinit var state: FlowState
        val flowMs = measureTimeMillis { state = analyser.solve(problem) }
        val graph = state.graph
        // Headroom for a new "Nordic hydro" sale: every class of a Nordic country and a hydro source.
        val nordic = setOf("FI", "SE", "NO", "DK")
        val eligible = BitSet().apply {
            problem.classes.forEach { if (it.representative.deviceCountry in nordic && it.representative.energySourceCode.startsWith("HYDRO")) set(it.index) }
        }
        var headroom = 0L
        val headroomNs = measureNanoTime { repeat(20) { headroom = state.headroom(eligible) } } / 20
        println("benchmark lots=$lotCount classes=${problem.classes.size} obligations=$obligationCount build=${buildMs}ms")
        println("benchmark greedy=${solveMs}ms ($assignments assignments) coverage=${coverageMs}ms covered=$covered")
        println(
            "benchmark flow: pairs=$pairs compressed groups=${graph.groups.size} atoms=${graph.atoms.size} edges=${graph.edgeCount} " +
                "solve=${flowMs}ms headroom=${headroomNs / 1_000}us (nordic hydro: $headroom MWh)",
        )
        // Available to sell (ADR 0036): a check is one detailed trial per delivery and one joint trial; the table one
        // trial per bucket, vintage and month-end. Eligibility comes from the prepared figures, so it is not timed here.
        val checks = criteria.map { c ->
            val bound = com.pianonurmi.etrm.product.domain.CriteriaBinder.bind(c, EngineFixtures.ctx(LocalDate.of(2026, 12, 1)))
            BitSet().apply { problem.classes.forEach { if (EngineFixtures.evaluate(bound, it.representative)) set(it.index) } }
        }
        val checkNs = List(200) { i -> measureNanoTime { state.headroomDetail(checks[i % checks.size]) } }.sorted()
        val p95Ms = checkNs[checkNs.size * 95 / 100] / 1_000_000.0
        val jointMs = measureTimeMillis { state.jointHeadroom(List(12) { checks[it % checks.size] to 1_000L }) }
        val tableMs = measureTimeMillis { repeat(16 * 2 * 6) { state.headroom(checks[it % checks.size]) } }
        println("benchmark available to sell: check p95=${"%.2f".format(p95Ms)}ms joint(12)=${jointMs}ms table(192 cells)=${tableMs}ms")
        (solveMs < 60_000) shouldBe true
        (coverageMs < 30_000) shouldBe true
        (p95Ms < 150) shouldBe true
        (tableMs < 2_000) shouldBe true
    }
}
