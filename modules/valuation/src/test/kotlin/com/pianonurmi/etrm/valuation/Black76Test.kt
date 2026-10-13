package com.pianonurmi.etrm.valuation

import com.pianonurmi.etrm.valuation.domain.Black76
import com.pianonurmi.etrm.valuation.domain.DecimalMath
import com.pianonurmi.etrm.valuation.domain.OptionRight
import io.kotest.matchers.bigdecimal.shouldBeGreaterThan
import io.kotest.matchers.bigdecimal.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class Black76Test {
    private fun close(actual: BigDecimal, expected: String, tolerance: String = "0.000001") =
        (actual - BigDecimal(expected)).abs() shouldBeLessThan BigDecimal(tolerance)

    @Test
    fun `decimal functions agree with their known values`() {
        close(DecimalMath.pi(), "3.14159265358979323846", "1E-18")
        close(DecimalMath.exp(BigDecimal.ONE), "2.71828182845904523536", "1E-18")
        close(DecimalMath.ln(BigDecimal(10)), "2.30258509299404568402", "1E-18")
        close(DecimalMath.normCdf(BigDecimal.ZERO), "0.5", "1E-18")
        close(DecimalMath.normCdf(BigDecimal("1.96")), "0.97500210485177952", "1E-15")
        close(DecimalMath.normCdf(BigDecimal("-3")), "0.00134989803163009", "1E-15")
        DecimalMath.normCdf(BigDecimal(20)) shouldBe BigDecimal.ONE
    }

    @Test
    fun `calls and puts match reference values, and their sensitivities`() {
        Black76.value(OptionRight.CALL, BigDecimal(100), BigDecimal(100), BigDecimal("0.2"), BigDecimal.ONE, BigDecimal.ONE).let {
            close(it.price, "7.965567")
            close(it.delta, "0.53982784")
            close(it.gamma, "0.01984763")
            close(it.vega, "0.39695255")
        }
        val df = BigDecimal("0.99")
        val call = Black76.value(OptionRight.CALL, BigDecimal(72), BigDecimal(80), BigDecimal("0.5"), BigDecimal("0.25"), df)
        val put = Black76.value(OptionRight.PUT, BigDecimal(72), BigDecimal(80), BigDecimal("0.5"), BigDecimal("0.25"), df)
        close(call.price, "4.175470")
        close(put.price, "12.095470")
        close(call.delta, "0.37961179")
        close(put.delta, "-0.61038821")
        // Put-call parity: C − P = DF·(F − K).
        close(call.price - put.price, "-7.92")
        Black76.value(OptionRight.PUT, BigDecimal("72.4"), BigDecimal(65), BigDecimal("0.45"), BigDecimal("0.18"), BigDecimal("0.995")).let {
            close(it.price, "2.321225")
            close(it.vega, "0.09805329")
        }
    }

    @Test
    fun `delta is the slope of the price, and without time or volatility the option is worth its intrinsic value`() {
        val h = BigDecimal("0.01")
        val up = Black76.value(OptionRight.CALL, BigDecimal("72.01"), BigDecimal(80), BigDecimal("0.5"), BigDecimal("0.25"), BigDecimal.ONE)
        val down = Black76.value(OptionRight.CALL, BigDecimal("71.99"), BigDecimal(80), BigDecimal("0.5"), BigDecimal("0.25"), BigDecimal.ONE)
        val mid = Black76.value(OptionRight.CALL, BigDecimal(72), BigDecimal(80), BigDecimal("0.5"), BigDecimal("0.25"), BigDecimal.ONE)
        close((up.price - down.price).divide(h * BigDecimal(2)), mid.delta.toPlainString(), "0.001")
        Black76.value(OptionRight.CALL, BigDecimal(90), BigDecimal(80), BigDecimal.ZERO, BigDecimal("0.25"), BigDecimal("0.99")).let {
            it.price shouldBe BigDecimal("9.900000")
            it.delta shouldBe BigDecimal("0.99000000")
        }
        Black76.value(OptionRight.PUT, BigDecimal(90), BigDecimal(80), BigDecimal("0.4"), BigDecimal.ZERO, BigDecimal.ONE).price shouldBe BigDecimal("0.000000")
        Black76.value(OptionRight.CALL, BigDecimal(72), BigDecimal(80), BigDecimal("0.5"), BigDecimal("0.25"), BigDecimal.ONE).price shouldBeGreaterThan
            BigDecimal.ZERO
    }
}
