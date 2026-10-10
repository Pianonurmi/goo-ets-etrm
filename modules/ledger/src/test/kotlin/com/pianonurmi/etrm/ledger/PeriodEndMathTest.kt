package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.domain.CostLayer
import com.pianonurmi.etrm.ledger.domain.CostingMethod
import com.pianonurmi.etrm.ledger.domain.EmissionFigures
import com.pianonurmi.etrm.ledger.domain.PeriodEndMath
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

class PeriodEndMathTest {
    private fun layer(quantity: Int, cost: String, on: LocalDate) =
        CostLayer(UUID.randomUUID(), UUID.randomUUID(), "ALLOWANCE:a:EUA", on, BigDecimal(quantity), BigDecimal(quantity), BigDecimal(cost), "EUR", null)

    @Test
    fun `the last business day skips the weekend`() {
        PeriodEndMath.lastBusinessDay(YearMonth.of(2026, 10)) shouldBe LocalDate.of(2026, 10, 30)
        PeriodEndMath.lastBusinessDay(YearMonth.of(2026, 9)) shouldBe LocalDate.of(2026, 9, 30)
    }

    @Test
    fun `a movement is the difference to what is booked, on one side`() {
        PeriodEndMath.movement(BigDecimal("100"), BigDecimal("40")) shouldBe (BigDecimal("60.00") to BigDecimal("0.00"))
        PeriodEndMath.movement(BigDecimal("40"), BigDecimal("100")) shouldBe (BigDecimal("0.00") to BigDecimal("60.00"))
        PeriodEndMath.movement(BigDecimal("40"), BigDecimal("40.00")) shouldBe (BigDecimal("0.00") to BigDecimal("0.00"))
    }

    @Test
    fun `emissions to date use verified, else monthly estimates to date, else the forecast pro rata`() {
        val june = YearMonth.of(2026, 6)
        PeriodEndMath.emissionsToDate(EmissionFigures(null, emptyMap(), 1200), 2026, june) shouldBe 600
        PeriodEndMath.emissionsToDate(EmissionFigures(null, mapOf(1 to 100L, 6 to 100L, 7 to 100L), 1200), 2026, june) shouldBe 200
        PeriodEndMath.emissionsToDate(EmissionFigures(1100, mapOf(1 to 100L), 1200), 2026, june) shouldBe 1100
        PeriodEndMath.emissionsToDate(EmissionFigures(null, emptyMap(), 1200), 2025, june) shouldBe 1200
        PeriodEndMath.emissionsToDate(EmissionFigures(null, emptyMap(), 1200), 2027, june) shouldBe 0
        PeriodEndMath.emissionsToDate(EmissionFigures(null, emptyMap(), null), 2026, june) shouldBe 0
    }

    @Test
    fun `the provision covers emissions with allowances held at cost and the shortfall at market`() {
        val layers = listOf(layer(400, "60", LocalDate.of(2026, 1, 1)), layer(600, "70", LocalDate.of(2026, 2, 1)))
        PeriodEndMath.provision(500, layers, CostingMethod.FIFO, BigDecimal("80")).let {
            it.covered shouldBe 500
            it.coveredCost shouldBe BigDecimal("31000.00") // 400 x 60 + 100 x 70
            it.shortfall shouldBe 0
            it.required shouldBe BigDecimal("31000.00")
        }
        PeriodEndMath.provision(1200, layers, CostingMethod.WEIGHTED_AVERAGE, BigDecimal("80")).let {
            it.coveredCost shouldBe BigDecimal("66000.00")
            it.shortfall shouldBe 200
            it.required shouldBe BigDecimal("82000.00")
        }
        PeriodEndMath.provision(0, layers, CostingMethod.FIFO, null).required shouldBe BigDecimal("0.00")
    }

    @Test
    fun `inventory is written down to net realisable value, never up`() {
        PeriodEndMath.writeDown(BigDecimal("50.00"), BigDecimal(100), BigDecimal("0.28")) shouldBe BigDecimal("22.00")
        PeriodEndMath.writeDown(BigDecimal("50.00"), BigDecimal(100), BigDecimal("0.80")) shouldBe BigDecimal("0.00")
        PeriodEndMath.writeDown(BigDecimal("50.00"), BigDecimal(100), BigDecimal("-0.02")) shouldBe BigDecimal("50.00")
    }

    @Test
    fun `the grant is released with emissions incurred, evenly when there are none`() {
        val june = YearMonth.of(2026, 6)
        PeriodEndMath.grantReleaseShare(300, 1200, 2026, june).compareTo(BigDecimal("0.25")) shouldBe 0
        PeriodEndMath.grantReleaseShare(1500, 1200, 2026, june).compareTo(BigDecimal.ONE) shouldBe 0
        PeriodEndMath.grantReleaseShare(0, 0, 2026, june).compareTo(BigDecimal("0.5")) shouldBe 0
        PeriodEndMath.grantReleaseShare(0, 1200, 2025, june) shouldBe BigDecimal.ONE
    }
}
