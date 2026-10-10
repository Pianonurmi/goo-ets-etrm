package com.pianonurmi.etrm.inventory.application

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.CertificateLot.CERTIFICATE_LOT
import com.pianonurmi.etrm.product.api.criteria.CriteriaColumns
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import org.jooq.Field
import java.math.BigDecimal
import java.time.LocalDate

/** Maps criteria attributes to the certificate_lot columns so the product module's compiler stays table-agnostic. */
object LotCriteriaColumns : CriteriaColumns {
    override fun string(attribute: LotAttribute): Field<String?> = when (attribute) {
        LotAttribute.ENERGY_CARRIER -> CERTIFICATE_LOT.ENERGY_CARRIER
        LotAttribute.CERTIFICATE_SCHEME -> CERTIFICATE_LOT.CERTIFICATE_SCHEME
        LotAttribute.ISSUING_BODY -> CERTIFICATE_LOT.ISSUING_BODY
        LotAttribute.ISSUING_COUNTRY -> CERTIFICATE_LOT.ISSUING_COUNTRY
        LotAttribute.DEVICE_ID -> CERTIFICATE_LOT.DEVICE_GSRN
        LotAttribute.DEVICE_COUNTRY -> CERTIFICATE_LOT.DEVICE_COUNTRY
        LotAttribute.DEVICE_REGION -> CERTIFICATE_LOT.DEVICE_REGION
        LotAttribute.SUPPORT_SCHEME -> CERTIFICATE_LOT.SUPPORT_SCHEME_CODE
        LotAttribute.REGISTRY -> CERTIFICATE_LOT.REGISTRY_CODE
        else -> throw IllegalArgumentException("$attribute is not a string attribute")
    }

    override fun bool(attribute: LotAttribute): Field<Boolean?> = when (attribute) {
        LotAttribute.SUPPORT_PRODUCTION -> CERTIFICATE_LOT.SUPPORT_PRODUCTION
        LotAttribute.SUPPORT_INVESTMENT -> CERTIFICATE_LOT.SUPPORT_INVESTMENT
        else -> throw IllegalArgumentException("$attribute is not a boolean attribute")
    }

    override fun number(attribute: LotAttribute): Field<BigDecimal?> = when (attribute) {
        LotAttribute.DEVICE_CAPACITY_KW -> CERTIFICATE_LOT.DEVICE_CAPACITY_KW
        else -> throw IllegalArgumentException("$attribute is not a numeric attribute")
    }

    override fun date(attribute: LotAttribute): Field<LocalDate?> = when (attribute) {
        LotAttribute.COMMISSIONING_DATE -> CERTIFICATE_LOT.DEVICE_COMMISSIONING_DATE
        LotAttribute.ISSUE_DATE -> CERTIFICATE_LOT.ISSUE_DATE
        LotAttribute.EXPIRY_DATE -> CERTIFICATE_LOT.EXPIRY_DATE
        else -> throw IllegalArgumentException("$attribute is not a date attribute")
    }

    override fun taxonomyPath(attribute: LotAttribute): Field<*> = when (attribute) {
        LotAttribute.ENERGY_SOURCE -> CERTIFICATE_LOT.ENERGY_SOURCE_PATH
        LotAttribute.TECHNOLOGY -> CERTIFICATE_LOT.TECHNOLOGY_PATH
        else -> throw IllegalArgumentException("$attribute is not a taxonomy attribute")
    }

    override val labels: Field<*> = CERTIFICATE_LOT.LABELS

    override val productionPeriod: Field<*> = CERTIFICATE_LOT.PRODUCTION_PERIOD
}
