package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.api.criteria.VintageRule

/** One-line human readable rendering of a criterion for blotters, confirmations and audit. */
object CriteriaDescriber {
    fun describe(criterion: Criterion): String = when (criterion) {
        is Criterion.Equals -> "${label(criterion.attribute)} = ${criterion.value}"
        is Criterion.InSet -> "${label(criterion.attribute)} in {${criterion.values.sorted().joinToString(", ")}}"
        is Criterion.NumberRange -> "${label(criterion.attribute)} in [${criterion.min ?: "-∞"}, ${criterion.max ?: "∞"}]"
        is Criterion.DateRange -> "${label(criterion.attribute)} in [${criterion.from ?: "…"}, ${criterion.toExclusive ?: "…"})"
        is Criterion.DescendantOf -> "${label(criterion.attribute)} within ${criterion.code}"
        is Criterion.LabelsAny -> "any label of {${criterion.labels.sorted().joinToString(", ")}}"
        is Criterion.LabelsAll -> "labels ${criterion.labels.sorted().joinToString(" + ")}"
        is Criterion.CountryInRegion -> "device in region ${criterion.region}"
        is Criterion.DeviceAgeAtDelivery -> "device age ${criterion.minYears}–${criterion.maxYears} years at delivery"
        is Criterion.Vintage -> when (val r = criterion.rule) {
            is VintageRule.ProductionYear -> "vintage ${r.year}"
            VintageRule.SameCalendarYearAsDelivery -> "vintage = delivery year"
            is VintageRule.MonthsBeforeDelivery -> "produced within ${r.months} months before delivery"
            is VintageRule.Explicit -> "produced in [${r.from}, ${r.toExclusive})"
        }
        is Criterion.All -> criterion.of.joinToString(" AND ") { wrap(it) }
        is Criterion.AnyOf -> criterion.of.joinToString(" OR ") { wrap(it) }
        is Criterion.Not -> "NOT ${wrap(criterion.of)}"
        Criterion.MatchAll -> "any certificate"
    }

    private fun wrap(c: Criterion): String = if (c is Criterion.All || c is Criterion.AnyOf) "(${describe(c)})" else describe(c)

    private fun label(attribute: LotAttribute): String = attribute.name.lowercase().replace('_', ' ')
}
