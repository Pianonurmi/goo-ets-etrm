package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.api.criteria.VintageRule
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln
import kotlin.math.max

/** Weights for static specificity, loaded from `specificity_weight`. */
data class SpecificityWeights(private val weights: Map<String, BigDecimal>) {
    operator fun get(key: String): Double = weights[key]?.toDouble() ?: DEFAULTS.getValue(key)

    companion object {
        val DEFAULTS: Map<String, Double> = mapOf(
            "DEVICE_EQUALS" to 10.0,
            "TAXONOMY_PER_LEVEL" to 2.0,
            "COUNTRY" to 4.0,
            "LABEL" to 5.0,
            "VINTAGE_WITHIN_12_MONTHS" to 3.0,
            "VINTAGE_OTHER" to 1.5,
            "DEVICE_AGE" to 3.0,
            "SUPPORT" to 2.0,
            "CARRIER" to 0.5,
            "GENERIC_EQUALS" to 2.0,
            "RANGE" to 1.5,
            "NOT_FACTOR" to 0.5,
        )
        val DEFAULT = SpecificityWeights(emptyMap())
    }
}

/**
 * Static specificity: how constrained a criterion is independent of inventory. `All` sums, `Any` takes the minimum,
 * `Not` halves. Used to order equally supplied obligations and to pick the "best" bucket for a lot.
 * Dynamic constrainedness (eligible supply / demand) is computed by the allocation engine.
 */
class Specificity(
    private val weights: SpecificityWeights,
    private val taxonomyDepth: (LotAttribute, String) -> Int?,
    private val regionSize: (String) -> Int?,
) {
    private val europeCountries = 40.0

    fun score(criterion: Criterion): BigDecimal = BigDecimal(raw(criterion)).setScale(4, RoundingMode.HALF_UP)

    private fun raw(criterion: Criterion): Double = when (criterion) {
        is Criterion.Equals -> when (criterion.attribute) {
            LotAttribute.DEVICE_ID -> weights["DEVICE_EQUALS"]
            LotAttribute.DEVICE_COUNTRY, LotAttribute.ISSUING_COUNTRY -> weights["COUNTRY"]
            LotAttribute.SUPPORT_PRODUCTION, LotAttribute.SUPPORT_INVESTMENT, LotAttribute.SUPPORT_SCHEME -> weights["SUPPORT"]
            LotAttribute.ENERGY_CARRIER -> weights["CARRIER"]
            else -> weights["GENERIC_EQUALS"]
        }
        is Criterion.InSet -> when (criterion.attribute) {
            LotAttribute.DEVICE_COUNTRY, LotAttribute.ISSUING_COUNTRY -> log2(europeCountries / criterion.values.size) * weights["COUNTRY"] / 4.0
            LotAttribute.DEVICE_ID -> weights["DEVICE_EQUALS"] / criterion.values.size
            else -> weights["GENERIC_EQUALS"] / criterion.values.size
        }
        is Criterion.NumberRange, is Criterion.DateRange -> weights["RANGE"]
        is Criterion.DescendantOf -> weights["TAXONOMY_PER_LEVEL"] * ((taxonomyDepth(criterion.attribute, criterion.code) ?: 0) + 1)
        is Criterion.LabelsAny -> weights["LABEL"] / criterion.labels.size
        is Criterion.LabelsAll -> weights["LABEL"] * criterion.labels.size
        is Criterion.CountryInRegion -> log2(europeCountries / max(1, regionSize(criterion.region) ?: 40)) * weights["COUNTRY"] / 4.0
        is Criterion.DeviceAgeAtDelivery -> weights["DEVICE_AGE"]
        is Criterion.Vintage -> when (val rule = criterion.rule) {
            is VintageRule.ProductionYear, VintageRule.SameCalendarYearAsDelivery -> weights["VINTAGE_WITHIN_12_MONTHS"]
            is VintageRule.MonthsBeforeDelivery -> if (rule.months <= 12) weights["VINTAGE_WITHIN_12_MONTHS"] else weights["VINTAGE_OTHER"]
            is VintageRule.Explicit -> if (rule.toExclusive.toEpochDay() - rule.from.toEpochDay() <= 366) {
                weights["VINTAGE_WITHIN_12_MONTHS"]
            } else {
                weights["VINTAGE_OTHER"]
            }
        }
        is Criterion.All -> criterion.of.sumOf { raw(it) }
        is Criterion.AnyOf -> criterion.of.minOf { raw(it) }
        is Criterion.Not -> raw(criterion.of) * weights["NOT_FACTOR"]
        Criterion.MatchAll -> 0.0
    }

    private fun log2(x: Double): Double = max(0.0, ln(x) / ln(2.0))
}
