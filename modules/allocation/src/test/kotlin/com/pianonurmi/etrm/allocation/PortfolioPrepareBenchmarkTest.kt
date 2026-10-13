package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.application.ProblemBuilder
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.FutureSupply
import com.pianonurmi.etrm.allocation.domain.flow.CompressedFlowGraph
import com.pianonurmi.etrm.allocation.domain.flow.FlowDemand
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.allocation.domain.flow.SupplyUnit
import com.pianonurmi.etrm.inventory.api.LotSnapshot
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
        val builder = ProblemBuilder(FixtureCriteria, buckets(), routes(), GooMarks { _, _, _ -> BigDecimal("1.50") })
        val snapshot = LotSnapshot(EngineFixtures.entity, 0, lots)

        lateinit var built: ProblemBuilder.Built
        val buildMs = measureTimeMillis { built = builder.build(snapshot, obligations, emptyMap(), asOf, future = future) }
        val problem = built.problem
        lateinit var state: FlowState
        val flowMs = measureTimeMillis { state = CoverageAnalyser(built.scoring::bestBucketCode).solve(problem) }
        val graph = state.graph
        val compressMs = measureTimeMillis {
            CompressedFlowGraph.build(
                units = problem.classes.map { SupplyUnit.Held(it.index, it.quantity) },
                demands = problem.obligations.sortedBy { it.priority }.map { FlowDemand(it.index, it.priority, it.demand) },
                obligationCount = problem.obligations.size,
                eligibleUnits = { problem.eligible[it] },
                atomKey = { u -> problem.classes[u].productionYear },
            )
        }
        val scoreMs = measureTimeMillis { problem.score(problem.classes.first(), problem.obligations.first()) }
        println("benchmark prepare lots=$lotCount classes=${problem.classes.size} obligations=$obligationCount build=${buildMs}ms flow=${flowMs}ms")
        println(
            "benchmark prepare graph: groups=${graph.groups.size} atoms=${graph.atoms.size} edges=${graph.edgeCount} " +
                "compression=${compressMs}ms; solver scoring (first score)=${scoreMs}ms",
        )

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

    private fun buckets(): ProductBucketQuery {
        val views = listOf(
            EngineFixtures.bucket("NORDIC_HYDRO", Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")), 3),
            EngineFixtures.bucket("EU_WIND", Criterion.all(Criterion.region("EEA"), Criterion.energySource("WIND")), 2),
            EngineFixtures.bucket("EU_RENEWABLE", Criterion.energySource("RENEWABLE"), 1),
        )
        return mockk {
            every { bind(any(), any()) } answers {
                BoundBuckets(views.map { it to CriteriaBinder.bind(it.criterion, EngineFixtures.ctx(asOf)) }, CriteriaEvaluator::matches)
            }
        }
    }

    /** Every registry reaches every other, except that nothing leaves ISLAND. */
    private fun routes(): TransferRouteQuery = mockk {
        every { route(any(), any()) } answers {
            val from = firstArg<String>()
            val to = secondArg<String>()
            TransferRoute(from, to, from == to || from != "ISLAND", BigDecimal.ZERO, 0)
        }
    }

    /** The criteria language without a database: the fixture taxonomy and regions. */
    private object FixtureCriteria : CriteriaService {
        override fun parse(json: String): Criterion = error("not used")

        override fun toJson(criterion: Criterion): String = criterion.toString()

        override fun bind(criterion: Criterion, context: BindContext): BoundCriterion = CriteriaBinder.bind(criterion, context)

        override fun contextFor(deliveryDate: LocalDate): BindContext = EngineFixtures.ctx(deliveryDate)

        override fun evaluate(bound: BoundCriterion, subject: CriteriaSubject): Boolean = CriteriaEvaluator.matches(bound, subject)

        override fun compile(bound: BoundCriterion, columns: CriteriaColumns): org.jooq.Condition = error("not used")

        override fun implies(narrow: BoundCriterion, broad: BoundCriterion): Boolean = CriteriaImplication.implies(narrow, broad)

        override fun specificity(criterion: Criterion): BigDecimal = BigDecimal.ONE

        override fun describe(criterion: Criterion): String = criterion.toString()

        override fun catalogue(): CriteriaCatalogue = error("not used")

        override fun store(criterion: Criterion, createdBy: String?): CriteriaRef = error("not used")

        override fun load(id: UUID): Criterion = error("not used")

        override fun ref(id: UUID): CriteriaRef = error("not used")
    }
}
