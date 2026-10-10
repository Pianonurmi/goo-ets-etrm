package com.pianonurmi.etrm.platform.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Monetary amount in a single currency. Arithmetic across currencies is rejected; FX conversion is an
 * explicit operation elsewhere. Amounts keep full precision; rounding happens only at presentation or posting.
 */
data class Money(val amount: BigDecimal, val currency: Currency) : Comparable<Money> {
    constructor(amount: String, currency: String) : this(BigDecimal(amount), Currency.getInstance(currency))

    operator fun plus(other: Money): Money = Money(amount + other.amount, sameCurrency(other))

    operator fun minus(other: Money): Money = Money(amount - other.amount, sameCurrency(other))

    operator fun times(factor: BigDecimal): Money = Money(amount * factor, currency)

    operator fun unaryMinus(): Money = Money(amount.negate(), currency)

    fun isZero(): Boolean = amount.signum() == 0

    fun isNegative(): Boolean = amount.signum() < 0

    fun abs(): Money = Money(amount.abs(), currency)

    /** Rounds to the currency's default fraction digits (2 for EUR) using banker's rounding. */
    fun rounded(): Money = Money(amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_EVEN), currency)

    override fun compareTo(other: Money): Int = amount.compareTo(other.sameCurrency(this).let { other.amount })

    private fun sameCurrency(other: Money): Currency {
        require(other.currency == currency) { "Currency mismatch: $currency vs ${other.currency}" }
        return currency
    }

    override fun toString(): String = "${amount.toPlainString()} ${currency.currencyCode}"

    companion object {
        val EUR: Currency = Currency.getInstance("EUR")

        fun zero(currency: Currency): Money = Money(BigDecimal.ZERO, currency)

        fun eur(amount: String): Money = Money(BigDecimal(amount), EUR)

        fun eur(amount: BigDecimal): Money = Money(amount, EUR)

        fun eur(amount: Long): Money = Money(BigDecimal.valueOf(amount), EUR)
    }
}
