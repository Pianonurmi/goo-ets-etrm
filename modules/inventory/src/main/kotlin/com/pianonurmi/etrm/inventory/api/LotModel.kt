package com.pianonurmi.etrm.inventory.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class LotStatus {
    HELD,
    RESERVED,
    IN_TRANSFER,
    TRANSFERRED_OUT,
    CANCELLED,
    EXPIRED,
    WITHDRAWN,
    SPLIT,
    ;

    val live: Boolean get() = this == HELD || this == RESERVED || this == IN_TRANSFER

    companion object {
        val LIVE: Set<LotStatus> = setOf(HELD, RESERVED, IN_TRANSFER)
    }
}

enum class AcquisitionSource { ISSUANCE, TRANSFER_IN, OPENING_BALANCE }

enum class CancellationPurpose { DISCLOSURE, OWN_CONSUMPTION, VOLUNTARY, COMPLIANCE, ON_BEHALF_OF_THIRD_PARTY }

/** Inclusive certificate-number range. */
data class CertificateRange(val start: BigInteger, val end: BigInteger) {
    init {
        require(start <= end) { "Range start $start must not exceed end $end" }
    }

    val quantity: Long get() = (end - start + BigInteger.ONE).longValueExact()

    fun overlaps(other: CertificateRange): Boolean = start <= other.end && other.start <= end

    operator fun contains(other: CertificateRange): Boolean = start <= other.start && other.end <= end

    /** Splits off the first [quantity] certificates: `(head, tail)`. */
    fun splitHead(quantity: Long): Pair<CertificateRange, CertificateRange> {
        require(quantity in 1 until this.quantity) { "Cannot split $quantity of ${this.quantity} certificates" }
        val boundary = start + BigInteger.valueOf(quantity)
        return CertificateRange(start, boundary - BigInteger.ONE) to CertificateRange(boundary, end)
    }

    override fun toString(): String = "$start..$end"

    companion object {
        fun of(start: Long, end: Long) = CertificateRange(BigInteger.valueOf(start), BigInteger.valueOf(end))
    }
}

data class CancellationDetails(
    val purpose: CancellationPurpose,
    val beneficiaryName: String?,
    val beneficiaryId: String?,
    val consumptionCountry: String,
    val consumptionPeriod: LocalDateRange,
    val registryCancellationRef: String? = null,
    val obligationRef: UUID? = null,
)

/** Full read model of a lot; also the [CriteriaSubject] the allocation engine and buckets evaluate. */
data class LotView(
    val id: UUID,
    val holderEntityId: UUID,
    val registryAccountId: UUID,
    val registryCode: String,
    val issuanceRef: String,
    val range: CertificateRange,
    val quantityMwh: Long,
    val energyCarrier: EnergyCarrier,
    val certificateScheme: String,
    val issuingBody: String,
    val issuingCountry: String,
    val deviceId: UUID?,
    val deviceGsrn: String,
    val deviceName: String?,
    val deviceCountry: String,
    val deviceRegion: String?,
    val deviceCommissioningDate: LocalDate?,
    val deviceCapacityKw: BigDecimal?,
    val energySourceCode: String,
    val energySourcePath: String,
    val technologyCode: String?,
    val technologyPath: String?,
    override val productionPeriod: LocalDateRange,
    val issueDate: LocalDate,
    val expiryDate: LocalDate,
    val supportProduction: Boolean?,
    val supportInvestment: Boolean?,
    val supportSchemeCode: String?,
    override val labels: Set<String>,
    val status: LotStatus,
    val rootLotId: UUID,
    val parentLotId: UUID?,
    val acquisitionSource: AcquisitionSource,
    val acquisitionRef: String?,
    val acquisitionDate: LocalDate,
    val costBasisPerMwh: BigDecimal?,
    val costCurrency: String?,
    val reservationRef: UUID?,
    val instructionRef: UUID?,
    val attributes: Map<String, Any?>,
    val cancellation: CancellationDetails?,
    val version: Int,
) : CriteriaSubject {
    override fun string(attribute: LotAttribute): String? = when (attribute) {
        LotAttribute.ENERGY_CARRIER -> energyCarrier.name
        LotAttribute.CERTIFICATE_SCHEME -> certificateScheme
        LotAttribute.ISSUING_BODY -> issuingBody
        LotAttribute.ISSUING_COUNTRY -> issuingCountry
        LotAttribute.DEVICE_ID -> deviceGsrn
        LotAttribute.DEVICE_COUNTRY -> deviceCountry
        LotAttribute.DEVICE_REGION -> deviceRegion
        LotAttribute.SUPPORT_SCHEME -> supportSchemeCode
        LotAttribute.REGISTRY -> registryCode
        else -> null
    }

    override fun bool(attribute: LotAttribute): Boolean? = when (attribute) {
        LotAttribute.SUPPORT_PRODUCTION -> supportProduction
        LotAttribute.SUPPORT_INVESTMENT -> supportInvestment
        else -> null
    }

    override fun number(attribute: LotAttribute): BigDecimal? = if (attribute == LotAttribute.DEVICE_CAPACITY_KW) deviceCapacityKw else null

    override fun date(attribute: LotAttribute): LocalDate? = when (attribute) {
        LotAttribute.COMMISSIONING_DATE -> deviceCommissioningDate
        LotAttribute.ISSUE_DATE -> issueDate
        LotAttribute.EXPIRY_DATE -> expiryDate
        else -> null
    }

    override fun taxonomyPath(attribute: LotAttribute): String? = when (attribute) {
        LotAttribute.ENERGY_SOURCE -> energySourcePath
        LotAttribute.TECHNOLOGY -> technologyPath
        else -> null
    }

    /** Key of the lot class: lots sharing it are interchangeable for every criterion and for scoring. */
    val classKey: String
        get() = listOf(
            holderEntityId,
            registryAccountId,
            deviceGsrn,
            productionPeriod,
            labels.sorted(),
            supportProduction,
            supportInvestment,
            supportSchemeCode,
            expiryDate,
            costBasisPerMwh?.setScale(2, java.math.RoundingMode.HALF_UP),
            costCurrency,
            energySourceCode,
            technologyCode,
        ).joinToString("|")
}

data class LotEventView(
    val seq: Long,
    val lotId: UUID,
    val eventType: String,
    val recordedAt: Instant,
    val effectiveAt: Instant,
    val actor: String,
    val correlationId: String?,
    val payload: Map<String, Any?>,
    val prevHash: String?,
    val hash: String,
)

/** Point-in-time view of live inventory for an entity; `seq` is the journal position it reflects. */
data class LotSnapshot(val holderEntityId: UUID, val seq: Long, val lots: List<LotView>)

data class LotFilter(
    val holderEntityId: UUID? = null,
    val registryAccountId: UUID? = null,
    val statuses: Set<LotStatus>? = null,
    val deviceGsrn: String? = null,
    val expiringBefore: LocalDate? = null,
    val reservationRef: UUID? = null,
    val limit: Int = 500,
    /** Restricts the list to lots held by these legal entities; null means no restriction. */
    val holderEntityIds: Set<UUID>? = null,
)

// --------------------------------------------------------------------------------------------------------------
// Events. Every event carries the lot snapshot after the change so consumers need no round trip.
// --------------------------------------------------------------------------------------------------------------

sealed interface LotEvent : DomainEvent {
    val lot: LotView
}

data class LotReceived(override val lot: LotView, val metadata: EventMetadata = EventMetadata()) : LotEvent, DomainEvent by metadata

data class LotSplit(val parent: LotView, val children: List<LotView>, val metadata: EventMetadata = EventMetadata()) : LotEvent, DomainEvent by metadata {
    override val lot: LotView get() = parent
}

data class LotReserved(override val lot: LotView, val reservationRef: UUID, val metadata: EventMetadata = EventMetadata()) : LotEvent, DomainEvent by metadata

data class LotReleased(override val lot: LotView, val reservationRef: UUID, val metadata: EventMetadata = EventMetadata()) : LotEvent, DomainEvent by metadata

data class LotTransferStarted(override val lot: LotView, val instructionRef: UUID, val metadata: EventMetadata = EventMetadata()) :
    LotEvent, DomainEvent by metadata

data class LotTransferred(
    override val lot: LotView,
    val instructionRef: UUID,
    val externalRef: String?,
    val metadata: EventMetadata = EventMetadata(),
) : LotEvent, DomainEvent by metadata

data class LotTransferFailed(override val lot: LotView, val instructionRef: UUID, val reason: String, val metadata: EventMetadata = EventMetadata()) :
    LotEvent, DomainEvent by metadata

data class LotCancelled(override val lot: LotView, val details: CancellationDetails, val metadata: EventMetadata = EventMetadata()) :
    LotEvent, DomainEvent by metadata

data class LotExpired(override val lot: LotView, val metadata: EventMetadata = EventMetadata()) : LotEvent, DomainEvent by metadata

/**
 * The nightly sweep could not expire a lot that is due (a listener refused, a rate or posting rule is missing). The lot
 * stays as it was; the sweep carries on with the others. Published after the lot's own transaction rolled back.
 */
data class LotExpiryFailed(val lot: LotView, val reason: String, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class LotWithdrawn(override val lot: LotView, val reason: String, val metadata: EventMetadata = EventMetadata()) : LotEvent, DomainEvent by metadata

data class LotCorrected(override val lot: LotView, val changedFields: Set<String>, val metadata: EventMetadata = EventMetadata()) :
    LotEvent, DomainEvent by metadata
