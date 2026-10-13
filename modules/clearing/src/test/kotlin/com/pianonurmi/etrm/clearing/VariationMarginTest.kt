package com.pianonurmi.etrm.clearing

import com.pianonurmi.etrm.clearing.domain.VariationMargin
import com.pianonurmi.etrm.clearing.domain.VariationMargin.Fill
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class VariationMarginTest {
    @Test
    fun `the overnight position earns the price move and the day's trades their difference to the settlement price`() {
        // 3 000 t long from yesterday: 3 000 x (72.10 - 71.50) = 1 800. Bought 1 000 at 71.90: +200. Sold 2 000 at 72.30: +400.
        VariationMargin.line(
            "EUAZ26",
            BigDecimal(3000),
            listOf(Fill(BigDecimal(1000), BigDecimal("71.90")), Fill(BigDecimal(-2000), BigDecimal("72.30"))),
            BigDecimal("72.10"),
            BigDecimal("71.50"),
        ).let {
            it.variationMargin shouldBe BigDecimal("2400.00")
            it.tradedQuantity shouldBe BigDecimal(-1000)
            it.positionAfter shouldBe BigDecimal(2000)
        }
        // A short position loses when the price rises.
        VariationMargin.line("EUAZ26", BigDecimal(-1000), emptyList(), BigDecimal("72.00"), BigDecimal("71.00")).variationMargin shouldBe
            BigDecimal("-1000.00")
        shouldThrow<IllegalArgumentException> { VariationMargin.line("EUAZ26", BigDecimal(1000), emptyList(), BigDecimal("72.00"), null) }
    }
}
