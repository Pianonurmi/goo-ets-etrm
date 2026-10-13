package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.domain.Effectiveness
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EffectivenessTest {
    private fun split(
        value: String,
        baseline: String,
        hypothetical: String,
    ) = Effectiveness.split(BigDecimal(value), BigDecimal(baseline), BigDecimal(hypothetical))

    @Test
    fun `the reserve takes the lower of the instruments' change and the hypothetical derivative`() {
        // Over-hedged: the instruments gained more than the forecast's price moved; the excess is ineffective.
        split("7500", "2000", "5000").let {
            it.instrumentChange shouldBe BigDecimal("5500.00")
            it.effective shouldBe BigDecimal("5000.00")
            it.ineffective shouldBe BigDecimal("2500.00")
            it.offsetRatio shouldBe BigDecimal("110.0")
        }
        // Under-hedged: all of the change is effective; only the value at designation is in profit or loss.
        split("6000", "2000", "-0").effective shouldBe BigDecimal("0.00")
        split("6000", "2000", "4500").let {
            it.effective shouldBe BigDecimal("4000.00")
            it.ineffective shouldBe BigDecimal("2000.00")
        }
        // Losses the same way round.
        split("-3000", "0", "-2000").effective shouldBe BigDecimal("-2000.00")
        // Moving against each other: nothing is effective.
        split("1000", "0", "-800").let {
            it.effective shouldBe BigDecimal("0.00")
            it.ineffective shouldBe BigDecimal("1000.00")
        }
        split("0", "0", "0").offsetRatio shouldBe null
    }
}
