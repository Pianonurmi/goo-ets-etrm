package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.api.criteria.VintageRule
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

/** Weights for static specificity, loaded from `specificity_weight`. */
data class SpecificityWeights(private val weights: Map<String, BigDecimal>) {
    operator fun get(key: String): BigDecimal = weights[key] ?: DEFAULTS.getValue(key)

    companion object {
        val DEFAULTS: Map<String, BigDecimal> = mapOf(
            "DEVICE_EQUALS" to "10.0",
            "TAXONOMY_PER_LEVEL" to "2.0",
            "COUNTRY" to "4.0",
            "LABEL" to "5.0",
            "VINTAGE_WITHIN_12_MONTHS" to "3.0",
            "VINTAGE_OTHER" to "1.5",
            "DEVICE_AGE" to "3.0",
            "SUPPORT" to "2.0",
            "CARRIER" to "0.5",
            "GENERIC_EQUALS" to "2.0",
            "RANGE" to "1.5",
            "NOT_FACTOR" to "0.5",
        ).mapValues { BigDecimal(it.value) }
        val DEFAULT = SpecificityWeights(emptyMap())
    }
}

/**
 * Static specificity: how constrained a criterion is independent of inventory. `All` sums, `Any` takes the minimum,
 * `Not` halves. Used to order equally supplied obligations and to pick the "best" bucket for a lot.
 * Dynamic constrainedness (eligible supply / demand) is computed by the allocation engine.
 *
 * Decimal throughout (no floating point in the domain): divisions keep ten decimals, and the share of Europe's 40
 * countries a country set covers is scored from a table of log2(40 / n), so the score is the same on every machine;
 * it is rounded to four decimals at the end.
 */
class Specificity(
    private val weights: SpecificityWeights,
    private val taxonomyDepth: (LotAttribute, String) -> Int?,
    private val regionSize: (String) -> Int?,
) {
    fun score(criterion: Criterion): BigDecimal = raw(criterion).setScale(4, RoundingMode.HALF_UP)

    private fun raw(criterion: Criterion): BigDecimal = when (criterion) {
        is Criterion.Equals -> when (criterion.attribute) {
            LotAttribute.DEVICE_ID -> weights["DEVICE_EQUALS"]
            LotAttribute.DEVICE_COUNTRY, LotAttribute.ISSUING_COUNTRY -> weights["COUNTRY"]
            LotAttribute.SUPPORT_PRODUCTION, LotAttribute.SUPPORT_INVESTMENT, LotAttribute.SUPPORT_SCHEME -> weights["SUPPORT"]
            LotAttribute.ENERGY_CARRIER -> weights["CARRIER"]
            else -> weights["GENERIC_EQUALS"]
        }
        is Criterion.InSet -> when (criterion.attribute) {
            LotAttribute.DEVICE_COUNTRY, LotAttribute.ISSUING_COUNTRY -> countryShare(criterion.values.size)
            LotAttribute.DEVICE_ID -> weights["DEVICE_EQUALS"].over(criterion.values.size)
            else -> weights["GENERIC_EQUALS"].over(criterion.values.size)
        }
        is Criterion.NumberRange, is Criterion.DateRange -> weights["RANGE"]
        is Criterion.DescendantOf -> weights["TAXONOMY_PER_LEVEL"] * BigDecimal((taxonomyDepth(criterion.attribute, criterion.code) ?: 0) + 1)
        is Criterion.LabelsAny -> weights["LABEL"].over(criterion.labels.size)
        is Criterion.LabelsAll -> weights["LABEL"] * BigDecimal(criterion.labels.size)
        is Criterion.CountryInRegion -> countryShare(max(1, regionSize(criterion.region) ?: EUROPE_COUNTRIES))
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
        is Criterion.All -> criterion.of.fold(BigDecimal.ZERO) { sum, part -> sum + raw(part) }
        is Criterion.AnyOf -> criterion.of.minOf { raw(it) }
        is Criterion.Not -> raw(criterion.of) * weights["NOT_FACTOR"]
        Criterion.MatchAll -> BigDecimal.ZERO
    }

    /** The country weight scaled by how small a share of Europe's 40 countries the set covers: log2(40 / n) x weight / 4. */
    private fun countryShare(countries: Int): BigDecimal = log2OfEuropeOver(countries) * weights["COUNTRY"].over(4)

    private fun BigDecimal.over(divisor: Int): BigDecimal = divide(BigDecimal(divisor), SCALE, RoundingMode.HALF_UP)

    private companion object {
        const val SCALE = 10
        const val EUROPE_COUNTRIES = 40

        /** log2(40 / n) for n = 1..40, to ten decimals; zero beyond 40. */
        val LOG2_EUROPE_OVER = listOf(
            "5.3219280949",
            "4.3219280949",
            "3.7369655942",
            "3.3219280949",
            "3.0000000000",
            "2.7369655942",
            "2.5145731728",
            "2.3219280949",
            "2.1520030934",
            "2.0000000000",
            "1.8624964763",
            "1.7369655942",
            "1.6214883767",
            "1.5145731728",
            "1.4150374993",
            "1.3219280949",
            "1.2344652536",
            "1.1520030934",
            "1.0740005814",
            "1.0000000000",
            "0.9296106721",
            "0.8624964763",
            "0.7983661388",
            "0.7369655942",
            "0.6780719051",
            "0.6214883767",
            "0.5670405927",
            "0.5145731728",
            "0.4639470998",
            "0.4150374993",
            "0.3677317845",
            "0.3219280949",
            "0.2775339755",
            "0.2344652536",
            "0.1926450779",
            "0.1520030934",
            "0.1124747293",
            "0.0740005814",
            "0.0365258760",
            "0.0000000000",
        ).map(::BigDecimal)

        fun log2OfEuropeOver(countries: Int): BigDecimal = LOG2_EUROPE_OVER.getOrElse(countries - 1) { BigDecimal.ZERO }
    }
}
