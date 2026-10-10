package com.pianonurmi.etrm.product.api.criteria

/** How a criteria builder offers a field: which predicates apply and where its values come from. */
enum class CriteriaOperator { EQUALS, IN, NUMBER_RANGE, DATE_RANGE, DESCENDANT_OF, LABELS_ANY, LABELS_ALL, VINTAGE }

enum class CriteriaValueSource {
    /** Free text. */
    TEXT,
    BOOLEAN,

    /** A fixed list in [CriteriaField.values]. */
    ENUM,

    /** ISO 3166 country codes. */
    COUNTRY,

    /** Registry codes from reference data. */
    REGISTRY,

    /** Production device GSRNs from reference data. */
    DEVICE,

    /** Codes of the taxonomy scheme in [CriteriaField.taxonomyScheme]. */
    TAXONOMY,
    NUMBER,
    DATE,
}

data class CriteriaField(
    val attribute: LotAttribute,
    val kind: AttributeKind,
    val label: String,
    val operators: List<CriteriaOperator>,
    val valueSource: CriteriaValueSource,
    val taxonomyScheme: String? = null,
    val values: List<String> = emptyList(),
)

/**
 * Everything a UI needs to build criteria without hard-coding the language: one entry per [LotAttribute], plus the
 * taxonomy schemes behind regions and labels. Delivery-relative predicates (`countryInRegion`, `deviceAgeAtDelivery`,
 * `vintage`) and the combinators (`all`, `any`, `not`, `matchAll`) are fixed parts of the language.
 */
data class CriteriaCatalogue(
    val fields: List<CriteriaField>,
    val regionScheme: String = "REGION",
    val labelScheme: String = "LABEL",
    val vintageRules: List<String> = listOf("productionYear", "sameCalendarYearAsDelivery", "monthsBeforeDelivery", "explicit"),
)
