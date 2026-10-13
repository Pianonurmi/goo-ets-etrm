package com.pianonurmi.etrm.valuation.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The functions option pricing needs, in decimal arithmetic (the domain uses no floating point): series evaluated at
 * 50 significant digits and returned at [DecimalMath.MC] (34 digits, DECIMAL128).
 */
object DecimalMath {
    val MC: MathContext = MathContext.DECIMAL128
    private val WIDE = MathContext(50, RoundingMode.HALF_EVEN)
    private val EPSILON = BigDecimal("1E-48")
    private val TWO = BigDecimal(2)
    private val LN2: BigDecimal by lazy { lnSeries(TWO) }
    private val PI: BigDecimal by lazy { pi() }
    private val SQRT_2PI: BigDecimal by lazy { PI.multiply(TWO, WIDE).sqrt(WIDE) }
    private val SQRT_PI: BigDecimal by lazy { PI.sqrt(WIDE) }
    private val SQRT2: BigDecimal by lazy { TWO.sqrt(WIDE) }
    private const val MAX_TERMS = 2_000
    private const val ERF_LIMIT = 9
    private const val EXP_HALVINGS = 10

    /** π by Machin's formula: 16·atan(1/5) − 4·atan(1/239). */
    fun pi(): BigDecimal = (atanInverse(5) * BigDecimal(16)).subtract(atanInverse(239) * BigDecimal(4), WIDE)

    private fun atanInverse(n: Int): BigDecimal {
        val x = BigDecimal.ONE.divide(BigDecimal(n), WIDE)
        val x2 = x.multiply(x, WIDE)
        var power = x
        var sum = x
        var k = 1
        while (k < MAX_TERMS) {
            power = power.multiply(x2, WIDE)
            val term = power.divide(BigDecimal(2 * k + 1), WIDE)
            if (term.abs() < EPSILON) break
            sum = if (k % 2 == 1) sum.subtract(term, WIDE) else sum.add(term, WIDE)
            k++
        }
        return sum
    }

    fun exp(x: BigDecimal): BigDecimal {
        // exp(x) = exp(x / 2^n)^(2^n), with the Taylor series for the small argument.
        val reduced = x.divide(BigDecimal(1 shl EXP_HALVINGS), WIDE)
        var sum = BigDecimal.ONE
        var term = BigDecimal.ONE
        var k = 1
        while (k < MAX_TERMS) {
            term = term.multiply(reduced, WIDE).divide(BigDecimal(k), WIDE)
            if (term.abs() < EPSILON) break
            sum = sum.add(term, WIDE)
            k++
        }
        repeat(EXP_HALVINGS) { sum = sum.multiply(sum, WIDE) }
        return sum.round(MC)
    }

    fun ln(x: BigDecimal): BigDecimal {
        require(x.signum() > 0) { "ln of a non-positive number" }
        // x = m·2^k with m in [1, 2): ln x = ln m + k·ln 2.
        var m = x
        var k = 0
        while (m >= TWO) {
            m = m.divide(TWO, WIDE)
            k++
        }
        while (m < BigDecimal.ONE) {
            m = m.multiply(TWO, WIDE)
            k--
        }
        return lnSeries(m).add(LN2.multiply(BigDecimal(k), WIDE), WIDE).round(MC)
    }

    /** ln x = 2·atanh((x − 1)/(x + 1)), converging fast for x near 1. */
    private fun lnSeries(x: BigDecimal): BigDecimal {
        val y = (x - BigDecimal.ONE).divide(x + BigDecimal.ONE, WIDE)
        val y2 = y.multiply(y, WIDE)
        var power = y
        var sum = y
        var k = 1
        while (k < MAX_TERMS) {
            power = power.multiply(y2, WIDE)
            val term = power.divide(BigDecimal(2 * k + 1), WIDE)
            if (term.abs() < EPSILON) break
            sum = sum.add(term, WIDE)
            k++
        }
        return sum.multiply(TWO, WIDE)
    }

    fun sqrt(x: BigDecimal): BigDecimal = x.sqrt(WIDE).round(MC)

    /** The standard normal density. */
    fun normPdf(x: BigDecimal): BigDecimal = exp(x.multiply(x, WIDE).divide(TWO, WIDE).negate()).divide(SQRT_2PI, MC)

    /** The standard normal distribution, N(x) = (1 + erf(x/√2)) / 2; 0 or 1 beyond ±9√2 standard deviations. */
    fun normCdf(x: BigDecimal): BigDecimal {
        val z = x.divide(SQRT2, WIDE)
        if (z > BigDecimal(ERF_LIMIT)) return BigDecimal.ONE
        if (z < BigDecimal(-ERF_LIMIT)) return BigDecimal.ZERO
        return BigDecimal.ONE.add(erf(z), WIDE).divide(TWO, MC)
    }

    /** erf(z) = 2/√π · Σ (−1)^n z^(2n+1) / (n!·(2n+1)), at 80 digits so the alternating terms cancel cleanly. */
    private fun erf(z: BigDecimal): BigDecimal {
        val wide = MathContext(80, RoundingMode.HALF_EVEN)
        val z2 = z.multiply(z, wide)
        var power = z
        var sum = z
        var factorial = BigDecimal.ONE
        var n = 1
        while (n < MAX_TERMS) {
            power = power.multiply(z2, wide)
            factorial = factorial.multiply(BigDecimal(n), wide)
            val term = power.divide(factorial.multiply(BigDecimal(2 * n + 1), wide), wide)
            if (term.abs() < EPSILON) break
            sum = if (n % 2 == 1) sum.subtract(term, wide) else sum.add(term, wide)
            n++
        }
        return sum.multiply(TWO, wide).divide(SQRT_PI, wide)
    }
}

enum class OptionRight { CALL, PUT }

/** An option's value per unit and its sensitivities; [vega] is per volatility point (1 %). */
data class OptionValue(val price: BigDecimal, val delta: BigDecimal, val gamma: BigDecimal, val vega: BigDecimal)

/**
 * Black-76 for a European option on a future (ADR 0047), premium-style: the value is discounted from the premium's
 * payment to today with [discountFactor]. [volatility] is a fraction a year (0.45 for 45 %), [years] the time to
 * expiry. Without time or volatility the option is worth its discounted intrinsic value.
 */
object Black76 {
    private val HALF = BigDecimal("0.5")
    private const val PRICE_SCALE = 6
    private const val GREEK_SCALE = 8
    private val HUNDRED = BigDecimal(100)

    fun value(
        right: OptionRight,
        forward: BigDecimal,
        strike: BigDecimal,
        volatility: BigDecimal,
        years: BigDecimal,
        discountFactor: BigDecimal,
    ): OptionValue {
        require(forward.signum() > 0 && strike.signum() > 0) { "Forward and strike must be positive" }
        require(volatility.signum() >= 0 && years.signum() >= 0) { "Volatility and time must not be negative" }
        val mc = DecimalMath.MC
        if (volatility.signum() == 0 || years.signum() == 0) return intrinsic(right, forward, strike, discountFactor)
        val sigmaRootT = volatility.multiply(DecimalMath.sqrt(years), mc)
        val d1 = DecimalMath.ln(forward.divide(strike, mc))
            .add(volatility.multiply(volatility, mc).multiply(years, mc).multiply(HALF, mc), mc)
            .divide(sigmaRootT, mc)
        val d2 = d1.subtract(sigmaRootT, mc)
        val price = when (right) {
            OptionRight.CALL -> forward.multiply(DecimalMath.normCdf(d1), mc).subtract(strike.multiply(DecimalMath.normCdf(d2), mc), mc)
            OptionRight.PUT -> strike.multiply(DecimalMath.normCdf(d2.negate()), mc).subtract(forward.multiply(DecimalMath.normCdf(d1.negate()), mc), mc)
        }.multiply(discountFactor, mc)
        val delta = when (right) {
            OptionRight.CALL -> DecimalMath.normCdf(d1)
            OptionRight.PUT -> DecimalMath.normCdf(d1.negate()).negate()
        }.multiply(discountFactor, mc)
        val density = DecimalMath.normPdf(d1)
        val gamma = discountFactor.multiply(density, mc).divide(forward.multiply(sigmaRootT, mc), mc)
        val vega = discountFactor.multiply(forward, mc).multiply(density, mc).multiply(DecimalMath.sqrt(years), mc).divide(HUNDRED, mc)
        return OptionValue(
            price.max(BigDecimal.ZERO).setScale(PRICE_SCALE, RoundingMode.HALF_EVEN),
            delta.setScale(GREEK_SCALE, RoundingMode.HALF_EVEN),
            gamma.setScale(GREEK_SCALE, RoundingMode.HALF_EVEN),
            vega.setScale(GREEK_SCALE, RoundingMode.HALF_EVEN),
        )
    }

    private fun intrinsic(right: OptionRight, forward: BigDecimal, strike: BigDecimal, discountFactor: BigDecimal): OptionValue {
        val payoff = when (right) {
            OptionRight.CALL -> forward - strike
            OptionRight.PUT -> strike - forward
        }
        val delta = when {
            payoff.signum() > 0 -> if (right == OptionRight.CALL) discountFactor else discountFactor.negate()
            payoff.signum() == 0 -> (if (right == OptionRight.CALL) discountFactor else discountFactor.negate()).multiply(HALF)
            else -> BigDecimal.ZERO
        }
        return OptionValue(
            payoff.max(BigDecimal.ZERO).multiply(discountFactor).setScale(PRICE_SCALE, RoundingMode.HALF_EVEN),
            delta.setScale(GREEK_SCALE, RoundingMode.HALF_EVEN),
            BigDecimal.ZERO.setScale(GREEK_SCALE),
            BigDecimal.ZERO.setScale(GREEK_SCALE),
        )
    }
}
