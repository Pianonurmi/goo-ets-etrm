package com.pianonurmi.etrm.platform.money

import java.math.BigDecimal

/** Physical units traded in the system. Certificates are counted whole; 1 GoO certificate = 1 MWh. */
enum class QuantityUnit(val symbol: String, val wholeOnly: Boolean) {
    MWH("MWh", wholeOnly = false),
    CERTIFICATE("cert", wholeOnly = true),
    TCO2E("tCO2e", wholeOnly = true),
    ;

    override fun toString(): String = symbol
}

/** A quantity in a unit. Mixed-unit arithmetic is rejected at the type level by construction and at runtime. */
data class Quantity(val amount: BigDecimal, val unit: QuantityUnit) : Comparable<Quantity> {
    init {
        if (unit.wholeOnly) {
            require(amount.stripTrailingZeros().scale() <= 0) { "Unit $unit only accepts whole numbers, got $amount" }
        }
    }

    constructor(amount: Long, unit: QuantityUnit) : this(BigDecimal.valueOf(amount), unit)

    operator fun plus(other: Quantity): Quantity = Quantity(amount + other.amount, sameUnit(other))

    operator fun minus(other: Quantity): Quantity = Quantity(amount - other.amount, sameUnit(other))

    operator fun times(factor: BigDecimal): Quantity = Quantity(amount * factor, unit)

    operator fun unaryMinus(): Quantity = Quantity(amount.negate(), unit)

    fun isZero(): Boolean = amount.signum() == 0

    fun isPositive(): Boolean = amount.signum() > 0

    fun isNegative(): Boolean = amount.signum() < 0

    fun abs(): Quantity = Quantity(amount.abs(), unit)

    fun toLongExact(): Long = amount.longValueExact()

    override fun compareTo(other: Quantity): Int = amount.compareTo(sameUnit(other).let { other.amount })

    private fun sameUnit(other: Quantity): QuantityUnit {
        require(other.unit == unit) { "Unit mismatch: $unit vs ${other.unit}" }
        return unit
    }

    override fun toString(): String = "${amount.toPlainString()} ${unit.symbol}"

    companion object {
        fun mwh(amount: Long): Quantity = Quantity(BigDecimal.valueOf(amount), QuantityUnit.MWH)

        fun mwh(amount: BigDecimal): Quantity = Quantity(amount, QuantityUnit.MWH)

        fun certificates(count: Long): Quantity = Quantity(BigDecimal.valueOf(count), QuantityUnit.CERTIFICATE)

        fun tco2e(amount: Long): Quantity = Quantity(BigDecimal.valueOf(amount), QuantityUnit.TCO2E)

        fun zero(unit: QuantityUnit): Quantity = Quantity(BigDecimal.ZERO, unit)
    }
}
