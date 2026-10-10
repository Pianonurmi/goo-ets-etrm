package com.pianonurmi.etrm.product.api.criteria

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Certificate attributes that criteria can constrain. Each attribute has a kind that fixes which predicates apply
 * and how the inventory module exposes the value (see [CriteriaSubject] and [CriteriaColumns]).
 */
enum class LotAttribute(val kind: AttributeKind) {
    ENERGY_CARRIER(AttributeKind.STRING),
    CERTIFICATE_SCHEME(AttributeKind.STRING),
    ISSUING_BODY(AttributeKind.STRING),
    ISSUING_COUNTRY(AttributeKind.STRING),
    DEVICE_ID(AttributeKind.STRING),
    DEVICE_COUNTRY(AttributeKind.STRING),
    DEVICE_REGION(AttributeKind.STRING),
    SUPPORT_SCHEME(AttributeKind.STRING),
    REGISTRY(AttributeKind.STRING),
    SUPPORT_PRODUCTION(AttributeKind.BOOLEAN),
    SUPPORT_INVESTMENT(AttributeKind.BOOLEAN),
    DEVICE_CAPACITY_KW(AttributeKind.NUMERIC),
    COMMISSIONING_DATE(AttributeKind.DATE),
    ISSUE_DATE(AttributeKind.DATE),
    EXPIRY_DATE(AttributeKind.DATE),
    ENERGY_SOURCE(AttributeKind.TAXONOMY),
    TECHNOLOGY(AttributeKind.TAXONOMY),
    LABELS(AttributeKind.LABEL_SET),
    PRODUCTION_PERIOD(AttributeKind.DATE_RANGE),
}

enum class AttributeKind { STRING, BOOLEAN, NUMERIC, DATE, TAXONOMY, LABEL_SET, DATE_RANGE }

/**
 * Unbound criterion as stored on trades and buckets. Delivery-relative predicates (device age, vintage, regions)
 * are resolved into a [BoundCriterion] with a [BindContext] before evaluation or SQL compilation.
 *
 * Serialised as JSON with a `type` discriminator inside a versioned envelope, see [CriteriaCodec].
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(Criterion.Equals::class, name = "equals"),
    JsonSubTypes.Type(Criterion.InSet::class, name = "in"),
    JsonSubTypes.Type(Criterion.NumberRange::class, name = "numberRange"),
    JsonSubTypes.Type(Criterion.DateRange::class, name = "dateRange"),
    JsonSubTypes.Type(Criterion.DescendantOf::class, name = "descendantOf"),
    JsonSubTypes.Type(Criterion.LabelsAny::class, name = "labelsAny"),
    JsonSubTypes.Type(Criterion.LabelsAll::class, name = "labelsAll"),
    JsonSubTypes.Type(Criterion.CountryInRegion::class, name = "countryInRegion"),
    JsonSubTypes.Type(Criterion.DeviceAgeAtDelivery::class, name = "deviceAgeAtDelivery"),
    JsonSubTypes.Type(Criterion.Vintage::class, name = "vintage"),
    JsonSubTypes.Type(Criterion.All::class, name = "all"),
    JsonSubTypes.Type(Criterion.AnyOf::class, name = "any"),
    JsonSubTypes.Type(Criterion.Not::class, name = "not"),
    JsonSubTypes.Type(Criterion.MatchAll::class, name = "matchAll"),
)
sealed interface Criterion {

    /** `attribute = value` for STRING and BOOLEAN attributes (booleans as "true"/"false"). NULL never matches. */
    data class Equals(val attribute: LotAttribute, val value: String) : Criterion {
        init {
            require(attribute.kind == AttributeKind.STRING || attribute.kind == AttributeKind.BOOLEAN) { "$attribute is not a scalar attribute" }
        }
    }

    /** `attribute IN (values)` for STRING attributes. NULL never matches. */
    data class InSet(val attribute: LotAttribute, val values: Set<String>) : Criterion {
        init {
            require(attribute.kind == AttributeKind.STRING) { "$attribute is not a string attribute" }
            require(values.isNotEmpty()) { "InSet requires at least one value" }
        }
    }

    /** Inclusive numeric bounds; either may be open. */
    data class NumberRange(val attribute: LotAttribute, val min: BigDecimal? = null, val max: BigDecimal? = null) : Criterion {
        init {
            require(attribute.kind == AttributeKind.NUMERIC) { "$attribute is not numeric" }
            require(min != null || max != null) { "NumberRange needs a bound" }
        }
    }

    /** Date bounds, `from` inclusive and `toExclusive` exclusive. */
    data class DateRange(val attribute: LotAttribute, val from: LocalDate? = null, val toExclusive: LocalDate? = null) : Criterion {
        init {
            require(attribute.kind == AttributeKind.DATE) { "$attribute is not a date" }
            require(from != null || toExclusive != null) { "DateRange needs a bound" }
            require(from == null || toExclusive == null || from < toExclusive) { "from must precede toExclusive" }
        }
    }

    /** Taxonomy predicate: the lot's code is [code] or one of its descendants. */
    data class DescendantOf(val attribute: LotAttribute, val code: String) : Criterion {
        init {
            require(attribute.kind == AttributeKind.TAXONOMY) { "$attribute is not a taxonomy attribute" }
        }
    }

    data class LabelsAny(val labels: Set<String>) : Criterion {
        init {
            require(labels.isNotEmpty()) { "LabelsAny requires labels" }
        }
    }

    data class LabelsAll(val labels: Set<String>) : Criterion {
        init {
            require(labels.isNotEmpty()) { "LabelsAll requires labels" }
        }
    }

    /** Device country is in the named region set (resolved from the taxonomy at bind time). */
    data class CountryInRegion(val region: String) : Criterion

    /** Device commissioned at most [maxYears] (and at least [minYears]) before the delivery date. */
    data class DeviceAgeAtDelivery(val maxYears: Int, val minYears: Int = 0) : Criterion {
        init {
            require(maxYears > minYears && minYears >= 0) { "0 <= minYears < maxYears required" }
        }
    }

    /** Production period must be contained in the vintage window defined by [rule]. */
    data class Vintage(val rule: VintageRule) : Criterion

    data class All(val of: List<Criterion>) : Criterion {
        init {
            require(of.isNotEmpty()) { "All requires operands" }
        }
    }

    data class AnyOf(val of: List<Criterion>) : Criterion {
        init {
            require(of.isNotEmpty()) { "Any requires operands" }
        }
    }

    data class Not(val of: Criterion) : Criterion

    /** Matches every certificate; the neutral element for buckets such as "any GoO". */
    data object MatchAll : Criterion

    companion object {
        fun all(vararg of: Criterion): Criterion = All(of.toList())

        fun anyOf(vararg of: Criterion): Criterion = AnyOf(of.toList())

        fun energySource(code: String): Criterion = DescendantOf(LotAttribute.ENERGY_SOURCE, code)

        fun technology(code: String): Criterion = DescendantOf(LotAttribute.TECHNOLOGY, code)

        fun country(vararg codes: String): Criterion = InSet(LotAttribute.DEVICE_COUNTRY, codes.toSet())

        fun region(code: String): Criterion = CountryInRegion(code)

        fun label(code: String): Criterion = LabelsAll(setOf(code))

        fun unsupported(): Criterion = all(
            Equals(LotAttribute.SUPPORT_PRODUCTION, "false"),
            Equals(LotAttribute.SUPPORT_INVESTMENT, "false"),
        )

        fun productionYear(year: Int): Criterion = Vintage(VintageRule.ProductionYear(year))

        fun device(gsrn: String): Criterion = Equals(LotAttribute.DEVICE_ID, gsrn)

        fun carrier(carrier: String): Criterion = Equals(LotAttribute.ENERGY_CARRIER, carrier)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(VintageRule.ProductionYear::class, name = "productionYear"),
    JsonSubTypes.Type(VintageRule.SameCalendarYearAsDelivery::class, name = "sameCalendarYearAsDelivery"),
    JsonSubTypes.Type(VintageRule.MonthsBeforeDelivery::class, name = "monthsBeforeDelivery"),
    JsonSubTypes.Type(VintageRule.Explicit::class, name = "explicit"),
)
sealed interface VintageRule {
    /** Production period within calendar year [year]. */
    data class ProductionYear(val year: Int) : VintageRule

    /** Production period within the calendar year of the delivery date. */
    data object SameCalendarYearAsDelivery : VintageRule

    /** Production period within the [months] months ending at the delivery date. */
    data class MonthsBeforeDelivery(val months: Int) : VintageRule {
        init {
            require(months > 0) { "months must be positive" }
        }
    }

    /** Production period within `[from, toExclusive)`. */
    data class Explicit(val from: LocalDate, val toExclusive: LocalDate) : VintageRule {
        init {
            require(from < toExclusive) { "from must precede toExclusive" }
        }
    }
}
