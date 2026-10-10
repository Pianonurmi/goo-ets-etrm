package com.pianonurmi.etrm.platform.time

import java.time.LocalDate
import java.time.YearMonth

/**
 * Closed-open date interval `[start, endExclusive)`. All business periods in the system (production periods,
 * delivery windows, accounting periods, validity) use this convention so adjacent ranges never overlap by one day.
 */
data class LocalDateRange(val start: LocalDate, val endExclusive: LocalDate) {
    init {
        require(start < endExclusive) { "Range start $start must be before end $endExclusive" }
    }

    /** Last day included in the range. */
    val endInclusive: LocalDate get() = endExclusive.minusDays(1)

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && date.isBefore(endExclusive)

    fun overlaps(other: LocalDateRange): Boolean = start < other.endExclusive && other.start < endExclusive

    fun containsRange(other: LocalDateRange): Boolean = start <= other.start && other.endExclusive <= endExclusive

    fun isAdjacentTo(other: LocalDateRange): Boolean = endExclusive == other.start || other.endExclusive == start

    fun intersect(other: LocalDateRange): LocalDateRange? {
        val s = maxOf(start, other.start)
        val e = minOf(endExclusive, other.endExclusive)
        return if (s < e) LocalDateRange(s, e) else null
    }

    fun lengthInDays(): Long = endExclusive.toEpochDay() - start.toEpochDay()

    override fun toString(): String = "[$start, $endExclusive)"

    companion object {
        fun ofMonth(month: YearMonth): LocalDateRange = LocalDateRange(month.atDay(1), month.plusMonths(1).atDay(1))

        fun ofYear(year: Int): LocalDateRange = LocalDateRange(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))

        fun ofQuarter(year: Int, quarter: Int): LocalDateRange {
            require(quarter in 1..4) { "Quarter must be 1..4" }
            val start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1)
            return LocalDateRange(start, start.plusMonths(3))
        }

        /** Inclusive end variant for callers holding `start..endInclusive` data. */
        fun inclusive(start: LocalDate, endInclusive: LocalDate): LocalDateRange = LocalDateRange(start, endInclusive.plusDays(1))
    }
}
