package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.criteria.AttributeKind
import com.pianonurmi.etrm.product.api.criteria.CriteriaCatalogue
import com.pianonurmi.etrm.product.api.criteria.CriteriaField
import com.pianonurmi.etrm.product.api.criteria.CriteriaOperator
import com.pianonurmi.etrm.product.api.criteria.CriteriaValueSource
import com.pianonurmi.etrm.product.api.criteria.LotAttribute

/** The criteria catalogue; the `when` is exhaustive, so a new attribute does not compile until it is described here. */
object CriteriaCatalogueBuilder {
    val catalogue: CriteriaCatalogue = CriteriaCatalogue(LotAttribute.entries.map(::field))

    private fun field(attribute: LotAttribute): CriteriaField = when (attribute) {
        LotAttribute.ENERGY_CARRIER -> enumField(attribute, "Energy carrier", EnergyCarrier.entries.map { it.name })
        LotAttribute.CERTIFICATE_SCHEME -> text(attribute, "Certificate scheme")
        LotAttribute.ISSUING_BODY -> text(attribute, "Issuing body")
        LotAttribute.ISSUING_COUNTRY -> string(attribute, "Issuing country", CriteriaValueSource.COUNTRY)
        LotAttribute.DEVICE_ID -> string(attribute, "Production device", CriteriaValueSource.DEVICE)
        LotAttribute.DEVICE_COUNTRY -> string(attribute, "Device country", CriteriaValueSource.COUNTRY)
        LotAttribute.DEVICE_REGION -> text(attribute, "Device region (bidding zone)")
        LotAttribute.SUPPORT_SCHEME -> text(attribute, "Support scheme")
        LotAttribute.REGISTRY -> string(attribute, "Registry", CriteriaValueSource.REGISTRY)
        LotAttribute.SUPPORT_PRODUCTION -> boolean(attribute, "Production support received")
        LotAttribute.SUPPORT_INVESTMENT -> boolean(attribute, "Investment support received")
        LotAttribute.DEVICE_CAPACITY_KW -> CriteriaField(
            attribute,
            attribute.kind,
            "Device capacity (kW)",
            listOf(CriteriaOperator.NUMBER_RANGE),
            CriteriaValueSource.NUMBER,
        )
        LotAttribute.COMMISSIONING_DATE -> date(attribute, "Commissioning date")
        LotAttribute.ISSUE_DATE -> date(attribute, "Issue date")
        LotAttribute.EXPIRY_DATE -> date(attribute, "Expiry date")
        LotAttribute.ENERGY_SOURCE -> taxonomy(attribute, "Energy source", "ENERGY_SOURCE")
        LotAttribute.TECHNOLOGY -> taxonomy(attribute, "Technology", "TECHNOLOGY")
        LotAttribute.LABELS -> CriteriaField(
            attribute,
            attribute.kind,
            "Labels",
            listOf(CriteriaOperator.LABELS_ANY, CriteriaOperator.LABELS_ALL),
            CriteriaValueSource.TAXONOMY,
            taxonomyScheme = "LABEL",
        )
        LotAttribute.PRODUCTION_PERIOD -> CriteriaField(
            attribute,
            attribute.kind,
            "Production period (vintage)",
            listOf(CriteriaOperator.VINTAGE),
            CriteriaValueSource.DATE,
        )
    }

    private fun string(attribute: LotAttribute, label: String, source: CriteriaValueSource) =
        CriteriaField(attribute, attribute.kind, label, listOf(CriteriaOperator.EQUALS, CriteriaOperator.IN), source)

    private fun text(attribute: LotAttribute, label: String) = string(attribute, label, CriteriaValueSource.TEXT)

    private fun enumField(attribute: LotAttribute, label: String, values: List<String>) =
        CriteriaField(attribute, attribute.kind, label, listOf(CriteriaOperator.EQUALS, CriteriaOperator.IN), CriteriaValueSource.ENUM, values = values)

    private fun boolean(attribute: LotAttribute, label: String) =
        CriteriaField(attribute, attribute.kind, label, listOf(CriteriaOperator.EQUALS), CriteriaValueSource.BOOLEAN, values = listOf("true", "false"))

    private fun date(attribute: LotAttribute, label: String) =
        CriteriaField(attribute, attribute.kind, label, listOf(CriteriaOperator.DATE_RANGE), CriteriaValueSource.DATE)

    private fun taxonomy(attribute: LotAttribute, label: String, scheme: String) =
        CriteriaField(attribute, attribute.kind, label, listOf(CriteriaOperator.DESCENDANT_OF), CriteriaValueSource.TAXONOMY, taxonomyScheme = scheme)

    /** Operators the criteria language accepts for each attribute kind (see the `init` checks of [Criterion]). */
    val operatorsByKind: Map<AttributeKind, Set<CriteriaOperator>> = mapOf(
        AttributeKind.STRING to setOf(CriteriaOperator.EQUALS, CriteriaOperator.IN),
        AttributeKind.BOOLEAN to setOf(CriteriaOperator.EQUALS),
        AttributeKind.NUMERIC to setOf(CriteriaOperator.NUMBER_RANGE),
        AttributeKind.DATE to setOf(CriteriaOperator.DATE_RANGE),
        AttributeKind.TAXONOMY to setOf(CriteriaOperator.DESCENDANT_OF),
        AttributeKind.LABEL_SET to setOf(CriteriaOperator.LABELS_ANY, CriteriaOperator.LABELS_ALL),
        AttributeKind.DATE_RANGE to setOf(CriteriaOperator.VINTAGE),
    )
}
