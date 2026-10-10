package com.pianonurmi.etrm.trade.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.BucketMatchKind
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.referencedata.api.ObligationsOpenAt
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

enum class Side {
    BUY,
    SELL,
    ;

    fun opposite(): Side = if (this == BUY) SELL else BUY
}

enum class PriceType { FIXED, INDEX, FORMULA }

enum class TradeStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    CONFIRMED,
    PARTIALLY_DELIVERED,
    DELIVERED,
    SETTLED,
    CLOSED,
    CANCELLED,
    DISPUTED,
    ;

    /** Statuses in which the trade is a firm commitment that creates positions and obligations. */
    val firm: Boolean get() = this in setOf(CONFIRMED, PARTIALLY_DELIVERED, DELIVERED, SETTLED, CLOSED)

    val open: Boolean get() = this !in setOf(CLOSED, CANCELLED)
}

enum class DeliveryStatus { PENDING, PARTIALLY_DELIVERED, DELIVERED, CANCELLED }

data class DeliveryScheduleView(
    val id: UUID,
    val scheduleNo: Int,
    val deliveryWindow: LocalDateRange,
    val deliveryDate: LocalDate,
    val quantity: BigDecimal,
    val deliveredQuantity: BigDecimal,
    val paymentDate: LocalDate?,
    val status: DeliveryStatus,
) {
    val remainingQuantity: BigDecimal get() = quantity - deliveredQuantity
}

data class TradeLegView(
    val id: UUID,
    val legNo: Int,
    val side: Side,
    val energyCarrier: EnergyCarrier?,
    val allowanceProductCode: String?,
    val criteriaId: UUID?,
    val criterion: Criterion?,
    val criteriaSummary: String?,
    val bucketCode: String?,
    val quantity: BigDecimal,
    val unit: QuantityUnit,
    val price: BigDecimal,
    val priceCurrency: Currency,
    val priceType: PriceType,
    val priceIndex: String?,
    val deliveryAccountId: UUID?,
    val counterpartyAccountRef: String?,
    val deliveries: List<DeliveryScheduleView>,
    /** The counterparty's registered account (reference data); [counterpartyAccountRef] mirrors it as text. */
    val counterpartyAccountId: UUID? = null,
    /** How [bucketCode] was found: the bucket's own criteria, or a bucket that provably contains the leg's criteria. */
    val bucketMatch: BucketMatchKind? = null,
    /** For exchange futures: the listed contract (product catalogue). */
    val futuresContractCode: String? = null,
    /** Unique product identifier (ISO 4914) for OTC derivatives. */
    val upi: String? = null,
    /** ISIN of the instrument when it has one (futures, venue-traded contracts). */
    val isin: String? = null,
) {
    val notional: BigDecimal get() = quantity * price
}

data class TradeView(
    val id: UUID,
    val tradeNumber: String,
    val legalEntityId: UUID,
    val bookId: UUID,
    val counterpartyId: UUID,
    val productFamily: ProductFamily,
    val instrumentType: InstrumentType,
    val tradeDate: LocalDate,
    val trader: String,
    val brokerId: UUID?,
    val masterAgreementRef: String?,
    val externalRef: String?,
    val status: TradeStatus,
    val version: Int,
    val intercompanyLinkId: UUID?,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val confirmedAt: Instant?,
    val confirmationRef: String?,
    val createdBy: String,
    val legs: List<TradeLegView>,
    /** The legal entity's policy when the trade was captured. */
    val obligationsOpenAt: ObligationsOpenAt = ObligationsOpenAt.CONFIRMED,
    val regulatory: RegulatoryTerms = RegulatoryTerms(),
) {
    val notional: BigDecimal get() = legs.sumOf { it.notional }

    /** Whether the trade is an EMIR-reportable derivative (see [RegulatoryScope]). */
    val emirReportable: Boolean get() = RegulatoryScope.reportable(this)

    /** Whether the trade's deliveries are obligations: once firm, or once approved where the entity's policy says so. */
    val obligationsOpen: Boolean get() = status.firm || (status == TradeStatus.APPROVED && obligationsOpenAt == ObligationsOpenAt.APPROVED)
}

data class TradeVersionView(
    val version: Int,
    val recordedAt: Instant,
    val supersededAt: Instant?,
    val recordedBy: String,
    val reason: String?,
    val status: TradeStatus,
    val trade: TradeView,
)

// --------------------------------------------------------------------------------------------------------------
// Commands
// --------------------------------------------------------------------------------------------------------------

data class CaptureDelivery(
    val deliveryWindow: LocalDateRange,
    /** Contractual delivery date (transfer cut-off) within the window. */
    val deliveryDate: LocalDate,
    val quantity: BigDecimal,
    val paymentDate: LocalDate? = null,
)

data class CaptureLeg(
    val side: Side,
    val energyCarrier: EnergyCarrier? = null,
    val allowanceProductCode: String? = null,
    /** Required for GoO legs: the delivery terms. */
    val criterion: Criterion? = null,
    val quantity: BigDecimal,
    val unit: QuantityUnit,
    val price: BigDecimal,
    val priceCurrency: String = "EUR",
    val priceType: PriceType = PriceType.FIXED,
    val priceIndex: String? = null,
    /** Our registry account for delivery or receipt. */
    val deliveryAccountId: UUID? = null,
    /** The counterparty's registered account; preferred over [counterpartyAccountRef]. */
    val counterpartyAccountId: UUID? = null,
    /** `REGISTRY_CODE:account`; resolved to a registered account when one exists, otherwise kept as free text. */
    val counterpartyAccountRef: String? = null,
    val deliveries: List<CaptureDelivery>,
    val futuresContractCode: String? = null,
    val upi: String? = null,
    val isin: String? = null,
)

data class CaptureTrade(
    val legalEntityId: UUID,
    val bookId: UUID,
    val counterpartyId: UUID,
    val productFamily: ProductFamily,
    val instrumentType: InstrumentType,
    val tradeDate: LocalDate,
    val trader: String? = null,
    val brokerId: UUID? = null,
    val masterAgreementRef: String? = null,
    val externalRef: String? = null,
    val legs: List<CaptureLeg>,
    /** For intercompany trades: the book in the counterparty legal entity that receives the mirrored trade. */
    val mirrorBookId: UUID? = null,
    val reason: String? = null,
    val regulatory: RegulatoryTerms? = null,
)

data class AmendTrade(
    val expectedVersion: Int,
    val reason: String,
    val tradeDate: LocalDate? = null,
    val brokerId: UUID? = null,
    val masterAgreementRef: String? = null,
    val externalRef: String? = null,
    val legs: List<CaptureLeg>? = null,
    /** New regulatory terms; a UTI once assigned is kept for the life of the trade. */
    val regulatory: RegulatoryTerms? = null,
)

data class ConfirmTrade(val confirmationRef: String, val method: String = "EMAIL", val confirmedAt: Instant? = null)

/**
 * Records a physical delivery against a schedule. [idempotencyKey] identifies the delivery at its source (for example
 * `instruction:<id>`); recording the same key twice is a no-op, so several sources may report one delivery safely.
 */
data class RecordDelivery(
    val scheduleId: UUID,
    val quantity: BigDecimal,
    val deliveredOn: LocalDate,
    val reference: String? = null,
    val idempotencyKey: String? = null,
)

interface TradeCommand {
    fun capture(command: CaptureTrade): TradeView

    fun amend(tradeId: UUID, command: AmendTrade): TradeView

    /** Four-eyes approval; the approver must differ from the trader and the person who captured the trade. */
    fun approve(tradeId: UUID): TradeView

    fun reject(tradeId: UUID, reason: String): TradeView

    fun confirm(tradeId: UUID, command: ConfirmTrade): TradeView

    fun cancel(tradeId: UUID, reason: String): TradeView

    fun recordDelivery(tradeId: UUID, command: RecordDelivery): TradeView

    fun markSettled(tradeId: UUID): TradeView
}

data class TradeFilter(
    val legalEntityId: UUID? = null,
    val bookId: UUID? = null,
    val counterpartyId: UUID? = null,
    val statuses: Set<TradeStatus>? = null,
    val productFamily: ProductFamily? = null,
    val tradeDateFrom: LocalDate? = null,
    val tradeDateTo: LocalDate? = null,
    val limit: Int = 200,
)

/** A firm sell-side (or buy-side) delivery that other modules act on; sourced from confirmed trades. */
data class DeliveryObligationView(
    val scheduleId: UUID,
    val tradeId: UUID,
    val tradeNumber: String,
    val legId: UUID,
    val legalEntityId: UUID,
    val bookId: UUID,
    val counterpartyId: UUID,
    val side: Side,
    val productFamily: ProductFamily,
    val energyCarrier: EnergyCarrier?,
    val allowanceProductCode: String?,
    val criteriaId: UUID?,
    val criterion: Criterion?,
    val quantity: BigDecimal,
    val deliveredQuantity: BigDecimal,
    val deliveryWindow: LocalDateRange,
    val deliveryDate: LocalDate,
    val deliveryAccountId: UUID?,
    val counterpartyAccountRef: String?,
    val price: BigDecimal,
    val priceCurrency: Currency,
    val priceType: PriceType,
    val status: DeliveryStatus,
    val counterpartyAccountId: UUID? = null,
    /** For INDEX legs: the market data price index; [price] is then the spread added to the fixing. */
    val priceIndex: String? = null,
) {
    val remainingQuantity: BigDecimal get() = quantity - deliveredQuantity
}

interface TradeQuery {
    fun trade(id: UUID): TradeView?

    fun requireTrade(id: UUID): TradeView

    fun byNumber(tradeNumber: String): TradeView?

    fun trades(filter: TradeFilter): List<TradeView>

    fun versions(tradeId: UUID): List<TradeVersionView>

    /** Open delivery obligations from firm trades. */
    fun openObligations(legalEntityId: UUID, side: Side? = null, productFamily: ProductFamily? = null): List<DeliveryObligationView>

    fun obligation(scheduleId: UUID): DeliveryObligationView?
}

// --------------------------------------------------------------------------------------------------------------
// Events
// --------------------------------------------------------------------------------------------------------------

sealed interface TradeEvent : DomainEvent {
    val trade: TradeView
}

data class TradeCaptured(override val trade: TradeView, val metadata: EventMetadata = EventMetadata()) : TradeEvent, DomainEvent by metadata

data class TradeAmended(override val trade: TradeView, val previousVersion: Int, val metadata: EventMetadata = EventMetadata()) :
    TradeEvent, DomainEvent by metadata

data class TradeApproved(override val trade: TradeView, val metadata: EventMetadata = EventMetadata()) : TradeEvent, DomainEvent by metadata

/** The trade became firm: positions, obligations and commitment postings start here. */
data class TradeConfirmed(override val trade: TradeView, val metadata: EventMetadata = EventMetadata()) : TradeEvent, DomainEvent by metadata

data class TradeCancelled(override val trade: TradeView, val reason: String, val metadata: EventMetadata = EventMetadata()) :
    TradeEvent, DomainEvent by metadata

data class DeliveryRecorded(
    override val trade: TradeView,
    /** Stable id of the delivery record; downstream modules key on it. */
    val deliveryId: UUID,
    val obligation: DeliveryObligationView,
    val quantity: BigDecimal,
    val deliveredOn: LocalDate,
    val reference: String?,
    val metadata: EventMetadata = EventMetadata(),
) : TradeEvent, DomainEvent by metadata

data class TradeSettled(override val trade: TradeView, val metadata: EventMetadata = EventMetadata()) : TradeEvent, DomainEvent by metadata

/** Obligations open when a trade is confirmed, change on amendment or partial delivery and close when fulfilled or cancelled. */
data class DeliveryObligationOpened(val obligation: DeliveryObligationView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class DeliveryObligationChanged(val obligation: DeliveryObligationView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class DeliveryObligationClosed(val obligation: DeliveryObligationView, val reason: String, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

/** Who generated the unique transaction identifier. */
enum class UtiSource { OWN, COUNTERPARTY, VENUE }

enum class ClearingStatus { NOT_CLEARED, CLEARED, INTEND_TO_CLEAR }

/** MiFIR trading capacity: dealing on own account, matched principal, any other capacity. */
enum class TradeCapacity { DEAL, MTCH, AOTC }

/** Who reports the trade to a trade repository under EMIR. */
enum class ReportingResponsibility { SELF, COUNTERPARTY, BOTH }

/** Data that EMIR and MiFID II reporting of allowance derivatives need; validated on capture and amendment. */
data class RegulatoryTerms(
    /** ISO 23897: the generating entity's LEI followed by up to 32 characters. */
    val uti: String? = null,
    val utiSource: UtiSource? = null,
    val executionTimestamp: Instant? = null,
    /** ISO 10383 MIC of the venue, or `XOFF` for a trade concluded off venue. */
    val venueMic: String? = null,
    val clearingStatus: ClearingStatus = ClearingStatus.NOT_CLEARED,
    /** The central counterparty when cleared. */
    val ccpCounterpartyId: UUID? = null,
    val tradeCapacity: TradeCapacity? = null,
    val reportingResponsibility: ReportingResponsibility? = null,
)

/**
 * Which trades are reportable derivatives under EMIR: forwards, futures, swaps and options on emission allowances.
 * Allowance forwards delivering entirely within two working days of the trade date are spot transactions, not
 * derivatives. GoO trades are not financial instruments. To be confirmed by compliance for each entity.
 */
object RegulatoryScope {
    private val derivatives = setOf(InstrumentType.FORWARD, InstrumentType.FUTURE, InstrumentType.SWAP, InstrumentType.OPTION)

    fun reportable(trade: TradeView): Boolean {
        if (trade.productFamily != ProductFamily.EMISSION_ALLOWANCE || trade.instrumentType !in derivatives) return false
        if (trade.instrumentType != InstrumentType.FORWARD) return true
        val spotLimit = workingDaysAfter(trade.tradeDate, SPOT_DAYS)
        return trade.legs.flatMap { it.deliveries }.any { it.deliveryDate.isAfter(spotLimit) }
    }

    private fun workingDaysAfter(date: LocalDate, days: Int): LocalDate {
        var d = date
        var left = days
        while (left > 0) {
            d = d.plusDays(1)
            if (d.dayOfWeek.value <= 5) left--
        }
        return d
    }

    private const val SPOT_DAYS = 2
}
