package com.pianonurmi.etrm.marketdata.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class DatedRate(val date: LocalDate, val rate: BigDecimal)

/** How a rate was found: directly, as the inverse of the opposite quote, or crossed through a pivot currency. */
data class ResolvedRate(val rate: BigDecimal, val path: String, val oldestDate: LocalDate)

/**
 * Resolves an exchange rate from published rates: the direct quote, else the inverse of the opposite quote, else a
 * cross through EUR (the ECB publishes EUR-based rates). Each leg uses its latest rate on or before the date; a rate
 * older than [maxStalenessDays] is not used.
 */
class FxResolver(private val lookup: (LocalDate, String, String) -> DatedRate?, private val maxStalenessDays: Long, private val pivot: String = "EUR") {
    fun resolve(date: LocalDate, from: String, to: String): ResolvedRate? {
        if (from == to) return ResolvedRate(BigDecimal.ONE, "identity", date)
        return direct(date, from, to) ?: if (from != pivot && to != pivot) cross(date, from, to) else null
    }

    private fun direct(date: LocalDate, from: String, to: String): ResolvedRate? {
        fresh(date, lookup(date, from, to))?.let { return ResolvedRate(it.rate, "$from/$to", it.date) }
        return fresh(date, lookup(date, to, from))?.let {
            ResolvedRate(BigDecimal.ONE.divide(it.rate, SCALE, RoundingMode.HALF_EVEN), "1/($to/$from)", it.date)
        }
    }

    private fun cross(date: LocalDate, from: String, to: String): ResolvedRate? {
        val first = direct(date, from, pivot) ?: return null
        val second = direct(date, pivot, to) ?: return null
        return ResolvedRate(
            (first.rate * second.rate).setScale(SCALE, RoundingMode.HALF_EVEN),
            "${first.path} x ${second.path}",
            minOf(first.oldestDate, second.oldestDate),
        )
    }

    private fun fresh(date: LocalDate, rate: DatedRate?): DatedRate? = rate?.takeIf { !it.date.plusDays(maxStalenessDays).isBefore(date) }

    private companion object {
        const val SCALE = 10
    }
}
