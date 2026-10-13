package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.application.ProblemBuilder
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.FutureSupply
import com.pianonurmi.etrm.allocation.domain.ObligationModel
import com.pianonurmi.etrm.allocation.domain.flow.CompressedFlowGraph
import com.pianonurmi.etrm.allocation.domain.flow.FlowDemand
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.allocation.domain.flow.SupplyUnit
import com.pianonurmi.etrm.inventory.api.LotSnapshot
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaCatalogue
import com.pianonurmi.etrm.product.api.criteria.CriteriaColumns
import com.pianonurmi.etrm.product.api.criteria.CriteriaRef
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.domain.CriteriaBinder
import com.pianonurmi.etrm.product.domain.CriteriaEvaluator
import com.pianonurmi.etrm.product.domain.CriteriaImplication
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.api.TransferRouteQuery
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Preparing an entity's portfolio flow (ADR 0036) the way `PortfolioFlowCache` does, phase by phase: the problem build
 * (classes, eligibility rows) and the flow solve, with the scoring the solvers need measured apart. Lots and
 * obligations are in memory, so the database read is not part of it. Defaults are sized for CI; run with
 * `-Detrm.benchmark.lots=100000 -Detrm.benchmark.obligations=5000` for the design size. It also checks that the shared
 * eligibility rows equal the per-class rule on a sample of obligations.
 */
class PortfolioPrepareBenchmarkTest {
    private val asOf = LocalDate.of(2026, 11, 1)

    @Test
    fun `preparing the portfolio flow scales to the configured inventory`() {
        val lotCount = System.getProperty("etrm.benchmark.lots")?.toInt() ?: 20_000
        val obligationCount = System.getProperty("etrm.benchmark.obligations")?.toInt() ?: 1_000
        val random = Random(11)
        val lots = lots(lotCount, random)
        val obligations = obligations(obligationCount, random)
        val future = futureLots()
        val builder = ProblemBuilder(FixturePorts.Criteria, FixturePorts.buckets(asOf), FixturePorts.routes(), GooMarks { _, _, _ -> BigDecimal("1.50") })
        val snapshot = LotSnapshot(EngineFixtures.entity, 0, lots)

        lateinit var built: ProblemBuilder.Built
        val buildMs = measureTimeMillis { built = builder.build(snapshot, obligations, emptyMap(), asOf, future = future) }
        val problem = built.problem
        lateinit var state: FlowState
        val flowMs = measureTimeMillis { state = CoverageAnalyser(built.scoring::bestBucketCode).solve(problem) }
        val graph = state.graph
        // The same graph without profiles: every class refined on its own (the slower exact path).
        lateinit var plain: CompressedFlowGraph
        val bucketOf = built.scoring::bestBucketCode
        val compressMs = measureTimeMillis {
            plain = CompressedFlowGraph.build(
                units = problem.classes.map {
                    if (it.future == null) SupplyUnit.Held(it.index, it.quantity) else SupplyUnit.Future(it.index, it.quantity, it.tier)
                },
                demands = problem.obligations
                    .sortedWith(compareBy<ObligationModel> { it.priority }.thenBy { it.obligation.deliveryDate }.thenBy { it.id })
                    .map { FlowDemand(it.index, it.priority, it.demand) },
                obligationCount = problem.obligations.size,
                eligibleUnits = { problem.eligible[it] },
                atomKey = { u -> problem.classes[u].let { (bucketOf(it) ?: "UNBUCKETED") to it.productionYear } },
            )
        }
        graph.atoms.size shouldBe plain.atoms.size
        graph.atoms.zip(plain.atoms).forEach { (a, b) ->
            a.unitIndexes.toList() shouldBe b.unitIndexes.toList()
            a.groupIndexes.toList() shouldBe b.groupIndexes.toList()
        }
        val scoreMs = measureTimeMillis { problem.score(problem.classes.first(), problem.obligations.first()) }
        println(
            "benchmark prepare lots=$lotCount classes=${problem.classes.size} obligations=$obligationCount " +
                "build=${buildMs}ms flow=${flowMs}ms",
        )
        val checks = obligations.map { it.criterion }.distinct().map { builder.eligible(problem.classes, it, LocalDate.of(2026, 12, 15), null) }
        val checkNs = List(CHECKS) { i -> kotlin.system.measureNanoTime { state.headroomDetail(checks[i % checks.size]) } }.sorted()
        val snapNs = List(CHECKS) { kotlin.system.measureNanoTime { state.network.restore(state.network.snapshot()) } }.sorted()
        val freeNs = List(CHECKS) { i -> kotlin.system.measureNanoTime { state.headroom(checks[i % checks.size], limit = 0) } }.sorted()
        println(
            "benchmark prepare available to sell: check p95=${"%.2f".format(checkNs[checkNs.size * 95 / 100] / 1_000_000.0)}ms " +
                "(snapshot+restore p95=${"%.2f".format(snapNs[snapNs.size * 95 / 100] / 1_000_000.0)}ms, " +
                "trial without augmenting p95=${"%.2f".format(freeNs[freeNs.size * 95 / 100] / 1_000_000.0)}ms)",
        )
        val tableMs = measureTimeMillis { repeat(TABLE_CELLS) { state.headroom(checks[it % checks.size]) } }
        println("benchmark prepare available to sell: table ($TABLE_CELLS cells)=${tableMs}ms")
        println(
            "benchmark prepare graph: groups=${graph.groups.size} atoms=${graph.atoms.size} edges=${graph.edgeCount} chained=${state.chains.edgeCount} " +
                "profiles=${(problem.profiles?.maxOrNull() ?: -1) + 1} without profiles=${compressMs}ms; solver scoring (first score)=${scoreMs}ms",
        )

        // A patch (ADR 0043) of the held figures: one lot reserved, then a thousand lots changed.
        val index = builder.index(lots, obligations)
        val bucketMemo = HashMap<String, String?>()

        fun patchMs(changed: List<LotView>): Long = measureTimeMillis {
            index.applyLots(changed, emptyList())
            index.setObligations(obligations)
            val patched = builder.problem(index, asOf)
            CoverageAnalyser { cls -> bucketMemo.getOrPut(cls.key) { patched.scoring.bestBucketCode(cls) } }.solve(patched.problem)
        }
        val firstMs = patchMs(emptyList())
        val oneMs = patchMs(listOf(lots[0].copy(status = LotStatus.RESERVED, version = 2)))
        val thousandMs = patchMs(lots.subList(1, PATCHED + 1).map { it.copy(quantityMwh = it.quantityMwh - 1, version = 2) })
        println("benchmark patch: first publication=${firstMs}ms one lot=${oneMs}ms $PATCHED lots=${thousandMs}ms")

        val sample = problem.obligations
            .distinctBy { Triple(it.obligation.criterion, it.obligation.deliveryDate, it.obligation.targetRegistryCode) }
            .take(SAMPLE)
        sample.forEach { obl ->
            val o = obl.obligation
            problem.eligible[obl.index] shouldBe builder.eligible(problem.classes, o.criterion, o.deliveryDate, o.targetRegistryCode)
        }
        (buildMs + flowMs < 60_000) shouldBe true
    }

    private companion object {
        const val SAMPLE = 40
        const val CHECKS = 200
        const val TABLE_CELLS = 192
        const val PATCHED = 1_000
    }

    /** Lots spread over sources, countries, registries (one without outgoing routes) and a year of expiry dates. */
    private fun lots(count: Int, random: Random): List<LotView> {
        val sources = listOf("WIND_ONSHORE", "HYDRO_RESERVOIR", "SOLAR", "NUCLEAR", "HYDRO", "WIND")
        val countries = listOf("FI", "SE", "NO", "DK", "DE", "FR", "ES", "NL")
        val registries = listOf("FINGRID", "STATNETT", "ISLAND")
        return List(count) {
            lot(
                country = countries[random.nextInt(countries.size)],
                source = sources[random.nextInt(sources.size)],
                quantity = 50L + random.nextInt(2_000),
                device = "DEV-${random.nextInt(800)}",
                month = YearMonth.of(2026, 1 + random.nextInt(12)),
                registry = registries[random.nextInt(registries.size)],
                expiry = LocalDate.of(2026, 12, 1).plusDays(random.nextInt(-30, 400).toLong()),
                costBasis = BigDecimal(random.nextInt(1, 500)).movePointLeft(2),
            )
        }
    }

    /** Sales of eight criteria over 60 delivery days, three priorities and three target registries. */
    private fun obligations(count: Int, random: Random): List<ObligationView> {
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
        val targets = listOf(null, "FINGRID", "STATNETT")
        return List(count) {
            obligation(
                criteria[random.nextInt(criteria.size)],
                100L + random.nextInt(5_000),
                delivery = LocalDate.of(2026, 12, 1).plusDays(random.nextInt(60).toLong()),
                priority = 1 + random.nextInt(3),
            ).copy(targetRegistryCode = targets[random.nextInt(targets.size)])
        }
    }

    /** Three devices' expected issuance and three purchases, so the future-supply rule is covered too. */
    private fun futureLots(): List<ProblemBuilder.FutureLot> = List(6) { i ->
        val stand = lot("FI", if (i % 2 == 0) "HYDRO" else "WIND", 1_000L, device = "FUT-$i", month = YearMonth.of(2027, 1))
        val supply = if (i < 3) {
            FutureSupply.Expected(Ids.newId(), "FUT-$i", "device $i", YearMonth.of(2027, 1), 1_000, 800, LocalDate.of(2026, 12, 10 + i))
        } else {
            val nordicHydro = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"))
            FutureSupply.Contracted(Ids.newId(), "T-$i", nordicHydro, null, LocalDate.of(2026, 12, 10 + i))
        }
        ProblemBuilder.FutureLot(supply, stand, 800L)
    }
}
