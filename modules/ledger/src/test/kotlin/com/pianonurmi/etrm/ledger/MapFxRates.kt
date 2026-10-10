package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.marketdata.api.FxRateView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.money.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Currency

/** Rates for hand-wired ledger tests: the latest on or before the date, direct or inverted. */
class MapFxRates : FxRates {
    private val rates = mutableMapOf<Triple<String, String, LocalDate>, BigDecimal>()

    fun put(date: LocalDate, from: String, to: String, rate: BigDecimal) {
        rates[Triple(from, to, date)] = rate
    }

    private fun latest(date: LocalDate, from: String, to: String): Pair<LocalDate, BigDecimal>? =
        rates.filterKeys { it.first == from && it.second == to && !it.third.isAfter(date) }.maxByOrNull { it.key.third }?.let { it.key.third to it.value }

    override fun rate(date: LocalDate, from: String, to: String): FxRateView? {
        if (from == to) return FxRateView(date, from, to, BigDecimal.ONE, "identity", date)
        latest(date, from, to)?.let { return FxRateView(date, from, to, it.second, "$from/$to", it.first) }
        return latest(date, to, from)?.let {
            FxRateView(date, from, to, BigDecimal.ONE.divide(it.second, 10, RoundingMode.HALF_EVEN), "1/($to/$from)", it.first)
        }
    }

    override fun require(date: LocalDate, from: String, to: String): FxRateView = rate(
        date,
        from,
        to,
    ) ?: throw ValidationException("No FX rate $from/$to on $date")

    override fun convert(amount: Money, to: String, date: LocalDate): Money =
        Money((amount.amount * require(date, amount.currency.currencyCode, to).rate).setScale(2, RoundingMode.HALF_EVEN), Currency.getInstance(to))
}
