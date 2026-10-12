package com.pianonurmi.etrm.product.api.criteria

import com.pianonurmi.etrm.platform.time.LocalDateRange
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The production year a GoO delivery is marked at: the calendar year overlapping the criterion's vintage window most
 * (the later year on a tie), or the delivery year when the criterion has no vintage.
 */
object VintageYears {
    fun of(bound: BoundCriterion, deliveryDate: LocalDate): Int {
        val windows = windows(bound)
        if (windows.isEmpty()) return deliveryDate.year
        val window = windows.reduce { a, b -> a.intersect(b) ?: a }
        return (window.start.year..window.endExclusive.minusDays(1).year)
            .maxWith(compareBy<Int> { overlap(window, it) }.thenBy { it })
    }

    private fun windows(bound: BoundCriterion): List<LocalDateRange> = when (bound) {
        is BoundCriterion.ProductionPeriodWithin -> listOf(bound.window)
        is BoundCriterion.All -> bound.of.flatMap(::windows)
        else -> emptyList()
    }

    private fun overlap(window: LocalDateRange, year: Int): Long {
        val y = LocalDateRange.ofYear(year)
        val start = maxOf(window.start, y.start)
        val end = minOf(window.endExclusive, y.endExclusive)
        return if (end.isAfter(start)) ChronoUnit.DAYS.between(start, end) else 0
    }
}
