package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.api.criteria.VintageRule

/** Resolves delivery-relative and taxonomy-relative predicates into concrete bound predicates. */
object CriteriaBinder {
    fun bind(criterion: Criterion, ctx: BindContext): BoundCriterion = when (criterion) {
        is Criterion.Equals -> BoundCriterion.Equals(criterion.attribute, criterion.value)
        is Criterion.InSet -> BoundCriterion.InSet(criterion.attribute, criterion.values)
        is Criterion.NumberRange -> BoundCriterion.NumberRange(criterion.attribute, criterion.min, criterion.max)
        is Criterion.DateRange -> BoundCriterion.DateRange(criterion.attribute, criterion.from, criterion.toExclusive)
        is Criterion.DescendantOf -> ctx.taxonomyPath(criterion.attribute, criterion.code)
            ?.let { BoundCriterion.PathDescendantOf(criterion.attribute, it) }
            ?: BoundCriterion.MatchNone("Unknown ${criterion.attribute} code ${criterion.code}")
        is Criterion.LabelsAny -> BoundCriterion.LabelsAny(criterion.labels)
        is Criterion.LabelsAll -> BoundCriterion.LabelsAll(criterion.labels)
        is Criterion.CountryInRegion -> ctx.regionCountries(criterion.region)
            .takeIf { it.isNotEmpty() }
            ?.let { BoundCriterion.InSet(LotAttribute.DEVICE_COUNTRY, it) }
            ?: BoundCriterion.MatchNone("Region ${criterion.region} has no countries")
        is Criterion.DeviceAgeAtDelivery -> BoundCriterion.DateRange(
            LotAttribute.COMMISSIONING_DATE,
            from = ctx.deliveryDate.minusYears(criterion.maxYears.toLong()),
            toExclusive = ctx.deliveryDate.minusYears(criterion.minYears.toLong()).plusDays(1),
        )
        is Criterion.Vintage -> BoundCriterion.ProductionPeriodWithin(window(criterion.rule, ctx))
        is Criterion.All -> BoundCriterion.All(criterion.of.map { bind(it, ctx) })
        is Criterion.AnyOf -> BoundCriterion.AnyOf(criterion.of.map { bind(it, ctx) })
        is Criterion.Not -> BoundCriterion.Not(bind(criterion.of, ctx))
        Criterion.MatchAll -> BoundCriterion.MatchAll
    }

    fun window(rule: VintageRule, ctx: BindContext): LocalDateRange = when (rule) {
        is VintageRule.ProductionYear -> LocalDateRange.ofYear(rule.year)
        VintageRule.SameCalendarYearAsDelivery -> LocalDateRange.ofYear(ctx.deliveryDate.year)
        is VintageRule.MonthsBeforeDelivery -> LocalDateRange(ctx.deliveryDate.minusMonths(rule.months.toLong()), ctx.deliveryDate.plusDays(1))
        is VintageRule.Explicit -> LocalDateRange(rule.from, rule.toExclusive)
    }
}
