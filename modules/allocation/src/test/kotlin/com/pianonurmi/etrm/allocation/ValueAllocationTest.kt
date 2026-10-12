package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.EngineFixtures.bucket
import com.pianonurmi.etrm.allocation.EngineFixtures.lot
import com.pianonurmi.etrm.allocation.EngineFixtures.obligation
import com.pianonurmi.etrm.allocation.EngineFixtures.valued
import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.Assignment
import com.pianonurmi.etrm.allocation.domain.CpSatSolver
import com.pianonurmi.etrm.allocation.domain.ExactSolverSettings
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.allocation.domain.PlanValuation
import com.pianonurmi.etrm.allocation.domain.ValuePolicy
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.all
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.region
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate

/** Value-based allocation in EUR (ADR 0035): what a lot is worth, what a sale gives away, and what the plan adds up to. */
class ValueAllocationTest {
    private val greedy = GreedySolver(EngineFixtures::evaluate)
    private val exact = CpSatSolver(EngineFixtures::evaluate, ExactSolverSettings(timeLimit = Duration.ofSeconds(5), workers = 2))

    private val anyGoo = bucket("ANY_GOO", energySource("RENEWABLE"), 1)
    private val euRenewable = bucket("EU_RENEWABLE", all(energySource("RENEWABLE"), region("EEA")), 2)
    private val nordicHydro = bucket("NORDIC_HYDRO", all(energySource("HYDRO"), region("NORDIC")), 4)
    private val buckets = listOf(anyGoo, euRenewable, nordicHydro)

    private val marks = mapOf("ANY_GOO" to "0.30", "EU_RENEWABLE" to "0.50", "NORDIC_HYDRO" to "2.00")
    private val priced: (String, Int) -> BigDecimal? = { code, year -> if (year == 2026) marks[code]?.let(::BigDecimal) else null }

    private val genericSale = energySource("RENEWABLE")
    private val hydroSale = all(energySource("HYDRO"), region("NORDIC"))

    @Test
    fun `a generic sale takes generic supply and keeps the hydro for the hydro sale`() {
        val wind = lot("DE", "WIND_ONSHORE", 100)
        val hydro = lot("FI", "HYDRO_RESERVOIR", 100)
        val obligations = listOf(
            // The generic sale is due first, so a solver that ignored value could hand it the hydro.
            obligation(genericSale, 100, delivery = LocalDate.of(2026, 12, 1), bucket = "EU_RENEWABLE"),
            obligation(hydroSale, 100, delivery = LocalDate.of(2026, 12, 15), bucket = "NORDIC_HYDRO"),
        )
        val problem = valued(listOf(hydro, wind), obligations, buckets, priced)

        listOf(greedy.solve(problem), exact.solve(problem)).forEach { assignments ->
            lotOf(problem, assignments, 0) shouldBe wind.id
            lotOf(problem, assignments, 1) shouldBe hydro.id
            val value = PlanValuation.of(lines(problem, assignments), obligations).shouldNotBeNull()
            value.giveAwayEur shouldBe BigDecimal("0.00")
            value.deliveredValueEur shouldBe BigDecimal("250.00")
            value.totalCostEur shouldBe BigDecimal("250.00")
        }
    }

    @Test
    fun `a lot is worth the best price it qualifies for, also when a broader curve is marked higher`() {
        val inverted: (String, Int) -> BigDecimal? = { code, _ -> mapOf("ANY_GOO" to "0.80", "EU_RENEWABLE" to "0.50")[code]?.let(::BigDecimal) }
        val wind = lot("DE", "WIND_ONSHORE", 100)
        val problem = valued(listOf(wind), listOf(obligation(genericSale, 10, bucket = "EU_RENEWABLE")), buckets, inverted)

        val score = problem.score(problem.classes[0], problem.obligations[0])
        score.opportunityCost shouldBe BigDecimal("0.800000")
        score.bucketCode shouldBe "ANY_GOO"
        score.saleMarkEur shouldBe BigDecimal("0.500000")
        score.giveAwayPerMwh shouldBe BigDecimal("0.300000")
        score.unit shouldBe ScoreBreakdown.EUR_PER_MWH
    }

    @Test
    fun `a generic sale forced onto hydro gives away the hydro premium`() {
        val hydro = lot("FI", "HYDRO_RESERVOIR", 100)
        val sale = obligation(genericSale, 60, bucket = "EU_RENEWABLE")
        val problem = valued(listOf(hydro), listOf(sale), buckets, priced)

        val assignments = greedy.solve(problem)
        val value = PlanValuation.of(lines(problem, assignments), listOf(sale)).shouldNotBeNull()
        value.giveAwayEur shouldBe BigDecimal("90.00")
        value.deliveredValueEur shouldBe BigDecimal("120.00")
        value.byObligation.single().let {
            it.saleBucketCode shouldBe "EU_RENEWABLE"
            it.saleMarkEur shouldBe BigDecimal("0.5000")
            it.averageDeliveredMarkEur shouldBe BigDecimal("2.0000")
            it.giveAwayEur shouldBe BigDecimal("90.00")
        }
    }

    @Test
    fun `a lot that lapses soon after delivery is cheaper to deliver, so it goes first`() {
        val delivery = LocalDate.of(2026, 12, 15)
        val lapsing = lot("FI", "HYDRO_RESERVOIR", 100, device = "DEV-A", expiry = delivery.plusDays(30))
        val lasting = lot("FI", "HYDRO_RESERVOIR", 100, device = "DEV-B", expiry = delivery.plusDays(365))
        val sale = obligation(hydroSale, 100, delivery = delivery, bucket = "NORDIC_HYDRO")
        val problem = valued(listOf(lasting, lapsing), listOf(sale), buckets, priced, policy = ValuePolicy(liquidityHorizonDays = 60))

        val lapsingClass = problem.classes.single { it.lots.single().lot.id == lapsing.id }
        val score = problem.score(lapsingClass, problem.obligations[0])
        score.opportunityCost shouldBe BigDecimal("2.000000")
        score.urgencyBonus shouldBe BigDecimal("1.000000")
        score.total shouldBe BigDecimal("1.000000")

        lotOf(problem, greedy.solve(problem), 0) shouldBe lapsing.id
        val value = PlanValuation.of(lines(problem, greedy.solve(problem)), listOf(sale)).shouldNotBeNull()
        value.expiryHaircutEur shouldBe BigDecimal("100.00")
        value.totalCostEur shouldBe BigDecimal("100.00")
    }

    @Test
    fun `transfer fees add to the cost, and supply with no priced bucket is valued at zero and flagged`() {
        val hydro = lot("FI", "HYDRO_RESERVOIR", 100, registry = "GREXEL")
        val sale = obligation(hydroSale, 40, bucket = "NORDIC_HYDRO")
        val problem = valued(listOf(hydro), listOf(sale), buckets, { _, _ -> null }, feePerMwh = BigDecimal("0.05"))

        val score = problem.score(problem.classes[0], problem.obligations[0])
        score.priced shouldBe false
        score.bucketCode shouldBe "NORDIC_HYDRO"
        score.total shouldBe BigDecimal("0.050000")
        score.giveAwayPerMwh.shouldBeNull()

        val value = PlanValuation.of(lines(problem, greedy.solve(problem)), listOf(sale)).shouldNotBeNull()
        value.unpricedMwh shouldBe 40
        value.transferFeesEur shouldBe BigDecimal("2.00")
        value.byObligation.single().giveAwayEur.shouldBeNull()
    }

    @Test
    fun `plans scored before ADR 0035 have no value in EUR`() {
        val wind = lot("DE", "WIND_ONSHORE", 100)
        val sale = obligation(genericSale, 10)
        val problem = EngineFixtures.problem(listOf(wind), listOf(sale))
        PlanValuation.of(lines(problem, greedy.solve(problem)), listOf(sale)).shouldBeNull()
    }

    @Test
    fun `the exact solver serves the higher priority first when only one of two can be covered`() {
        // One hydro lot, two sales that both accept it: the priority-1 sale must get it, whatever the cost phase prefers.
        val hydro = lot("FI", "HYDRO_RESERVOIR", 100)
        val low = obligation(genericSale, 100, delivery = LocalDate.of(2026, 11, 1), priority = 2, bucket = "EU_RENEWABLE")
        val high = obligation(hydroSale, 100, delivery = LocalDate.of(2026, 12, 15), priority = 1, bucket = "NORDIC_HYDRO")
        val problem = valued(listOf(hydro), listOf(low, high), buckets, priced)

        val assignments = exact.solve(problem)
        assignments.filter { it.obligationIndex == 1 }.sumOf { it.quantityMwh } shouldBe 100
        assignments.filter { it.obligationIndex == 0 }.sumOf { it.quantityMwh } shouldBe 0
    }

    private fun lotOf(problem: AllocationProblem, assignments: List<Assignment>, obligation: Int) =
        assignments.filter { it.obligationIndex == obligation }.map { problem.classes[it.classIndex].lots.single().lot.id }.distinct().single()

    private fun lines(problem: AllocationProblem, assignments: List<Assignment>): List<AllocationLineView> = assignments.mapIndexed { i, a ->
        AllocationLineView(
            id = Ids.newId(),
            lineNo = i + 1,
            obligationId = problem.obligations[a.obligationIndex].id,
            lotId = problem.classes[a.classIndex].lots.first().lot.id,
            lotClassKey = problem.classes[a.classIndex].key,
            quantityMwh = a.quantityMwh,
            score = a.score.total,
            breakdown = a.score,
            status = LineStatus.PROPOSED,
            reservedLotId = null,
            instructionId = null,
            externalRef = null,
            failureReason = null,
        )
    }
}
