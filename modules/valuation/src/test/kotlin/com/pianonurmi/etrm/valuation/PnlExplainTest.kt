package com.pianonurmi.etrm.valuation

import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.domain.Mtm
import com.pianonurmi.etrm.valuation.domain.PnlExplainer
import com.pianonurmi.etrm.valuation.domain.VintageYears
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

@OptIn(io.kotest.common.ExperimentalKotest::class)
class PnlExplainTest {
    private val trade = UUID.randomUUID()
    private val book = UUID.randomUUID()

    private fun result(
        schedule: UUID,
        version: Int,
        q: BigDecimal,
        k: BigDecimal,
        f: BigDecimal,
        x: BigDecimal,
        status: ResultStatus = ResultStatus.OK,
    ): ValuationResultView {
        val value = Mtm.value(q, f, k)
        return ValuationResultView(
            UUID.randomUUID(),
            schedule,
            trade,
            "T-1",
            version,
            1,
            book,
            BookType.TRADING,
            UUID.randomUUID(),
            ProductFamily.EMISSION_ALLOWANCE,
            "EUA",
            null,
            LocalDate.of(2026, 12, 21),
            q,
            k,
            "EUR",
            f,
            "EUR",
            "POINT",
            "EUA_FWD",
            UUID.randomUUID(),
            LocalDate.of(2026, 10, 9),
            f,
            x,
            Mtm.money(value),
            Mtm.money(value * x),
            status,
            null,
        )
    }

    private fun components(rows: List<com.pianonurmi.etrm.valuation.api.ExplainRow>) = rows.groupBy {
        it.component
    }.mapValues { (_, r) -> r.sumOf { it.amountBase } }

    @Test
    fun `a price move is PRICE, a delivery DELIVERED, a new and a vanished schedule NEW and CANCELLED`() {
        val s = UUID.randomUUID()
        val before = result(s, 1, BigDecimal(1000), BigDecimal("70"), BigDecimal("72"), BigDecimal.ONE)
        components(
            PnlExplainer.explain(listOf(before), listOf(result(s, 1, BigDecimal(1000), BigDecimal("70"), BigDecimal("74.5"), BigDecimal.ONE))) {
                false
            },
        ) shouldBe
            mapOf(ExplainComponent.PRICE to BigDecimal("2500.00"))
        components(
            PnlExplainer.explain(listOf(before), listOf(result(s, 1, BigDecimal(400), BigDecimal("70"), BigDecimal("72"), BigDecimal.ONE))) {
                false
            },
        ) shouldBe
            mapOf(ExplainComponent.DELIVERED to BigDecimal("-1200.00"))
        components(PnlExplainer.explain(emptyList(), listOf(before)) { false }) shouldBe mapOf(ExplainComponent.NEW to BigDecimal("2000.00"))
        components(PnlExplainer.explain(listOf(before), emptyList()) { true }) shouldBe mapOf(ExplainComponent.CANCELLED to BigDecimal("-2000.00"))
        components(PnlExplainer.explain(listOf(before), emptyList()) { false }) shouldBe mapOf(ExplainComponent.DELIVERED to BigDecimal("-2000.00"))
    }

    @Test
    fun `components add up to the change in MtM for any change of quantity, terms, market and rate`() = runBlocking<Unit> {
        val money = Arb.int(1, 20_000).map { BigDecimal(it).divide(BigDecimal(100)) }
        val case = Arb.bind(
            Arb.int(-5_000, 5_000).map { BigDecimal(it) },
            Arb.int(-5_000, 5_000).map { BigDecimal(it) },
            money,
            money,
            money,
            money,
            Arb.int(80, 120).map { BigDecimal(it).divide(BigDecimal(100)).setScale(10, RoundingMode.HALF_EVEN) },
            Arb.int(80, 120).map { BigDecimal(it).divide(BigDecimal(100)).setScale(10, RoundingMode.HALF_EVEN) },
            Arb.boolean(),
        ) { q0, q1, k0, k1, f0, f1, x0, x1, amended -> listOf(q0, q1, k0, k1, f0, f1, x0, x1) to amended }
        checkAll(PropTestConfig(iterations = 500), Arb.list(case, 1..6)) { cases ->
            val before = mutableListOf<ValuationResultView>()
            val after = mutableListOf<ValuationResultView>()
            cases.forEach { (v, amended) ->
                val s = UUID.randomUUID()
                before += result(s, 1, v[0], v[2], v[4], v[6])
                after += result(s, if (amended) 2 else 1, v[1], if (amended) v[3] else v[2], v[5], v[7])
            }
            val rows = PnlExplainer.explain(before, after) { false }
            rows.fold(BigDecimal.ZERO) { sum, r -> sum + r.amountBase }.compareTo(after.sumOf { it.mtmBase!! } - before.sumOf { it.mtmBase!! }) shouldBe 0
            // Rounding is all that is left unexplained.
            rows.filter { it.component == ExplainComponent.UNEXPLAINED }.forEach { (it.amountBase.abs() <= BigDecimal("0.03")) shouldBe true }
        }
    }

    @Test
    fun `the vintage year is the year the criterion's window overlaps most`() {
        fun window(from: String, to: String) = BoundCriterion.ProductionPeriodWithin(LocalDateRange(LocalDate.parse(from), LocalDate.parse(to)))
        VintageYears.of(window("2026-01-01", "2027-01-01"), LocalDate.of(2027, 3, 1)) shouldBe 2026
        VintageYears.of(window("2025-10-01", "2026-04-01"), LocalDate.of(2026, 5, 1)) shouldBe 2025
        VintageYears.of(window("2025-07-01", "2026-07-01"), LocalDate.of(2026, 9, 1)) shouldBe 2025 // 184 days in 2025, 181 in 2026
        VintageYears.of(window("2025-07-05", "2026-07-05"), LocalDate.of(2026, 9, 1)) shouldBe 2026 // 180 days in 2025, 185 in 2026
        VintageYears.of(BoundCriterion.All(listOf(BoundCriterion.MatchAll, window("2024-01-01", "2025-01-01"))), LocalDate.of(2026, 9, 1)) shouldBe 2024
        VintageYears.of(BoundCriterion.MatchAll, LocalDate.of(2026, 9, 1)) shouldBe 2026
    }
}
