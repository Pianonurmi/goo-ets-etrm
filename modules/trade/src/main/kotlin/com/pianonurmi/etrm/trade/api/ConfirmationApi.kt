package com.pianonurmi.etrm.trade.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.product.api.ProductFamily
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The economic terms of a trade as both parties confirm them (after EFET practice): parties, product, quantity,
 * price, amount, delivery and payment dates, registry accounts and, for guarantees of origin, the product attributes.
 * Parties are named as buyer and seller, so the terms read the same from either side.
 */
data class ConfirmationTerms(
    /** Our trade number, when the counterparty quotes it. */
    val tradeRef: String? = null,
    /** The counterparty's own trade id. */
    val counterpartyTradeRef: String? = null,
    val tradeDate: LocalDate,
    val productFamily: ProductFamily,
    val masterAgreement: String? = null,
    val legs: List<LegTerms>,
)

data class PartyTerms(val name: String? = null, val lei: String? = null)

data class LegTerms(
    val legNo: Int = 1,
    val seller: PartyTerms,
    val buyer: PartyTerms,
    /** Energy carrier for guarantees of origin, product code for allowances. */
    val product: String,
    val quantity: BigDecimal,
    val unit: String,
    val price: BigDecimal,
    val currency: String,
    val amount: BigDecimal,
    val deliveries: List<DeliveryTerms>,
    /** `REGISTRY:account` of the delivering and the receiving party. */
    val sellerAccount: String? = null,
    val buyerAccount: String? = null,
    /** Product attributes (guarantees of origin): energy source, technology, country or region, vintage, support, label. */
    val attributes: Map<String, String> = emptyMap(),
)

data class DeliveryTerms(val deliveryDate: LocalDate, val quantity: BigDecimal, val paymentDate: LocalDate? = null)

enum class ConfirmationDirection { OURS, THEIRS }

enum class ConfirmationSource { GENERATED, JSON, CSV, MANUAL }

/**
 * Ours: ISSUED, then SUPERSEDED by an amendment. Theirs: RECEIVED (no confirmation of ours yet), MATCHED or
 * MISMATCHED; a mismatch becomes OVERRIDDEN (accepted by a second person) or DISPUTED; any of them SUPERSEDED by a
 * newer confirmation from the counterparty or an amendment of the trade.
 */
enum class ConfirmationStatus { ISSUED, SUPERSEDED, RECEIVED, MATCHED, MISMATCHED, OVERRIDDEN, DISPUTED }

enum class DifferenceSeverity { MISMATCH, NOTE }

data class FieldDifference(val field: String, val ours: String?, val theirs: String?, val severity: DifferenceSeverity = DifferenceSeverity.MISMATCH)

data class ConfirmationView(
    val id: UUID,
    val tradeId: UUID,
    val tradeNumber: String,
    val tradeVersion: Int,
    val direction: ConfirmationDirection,
    val source: ConfirmationSource,
    val reference: String?,
    val terms: ConfirmationTerms,
    val status: ConfirmationStatus,
    val differences: List<FieldDifference>,
    /** For theirs: the confirmation of ours it was matched against. */
    val matchedAgainst: UUID?,
    /** For ours: the rendered PDF. */
    val documentId: UUID?,
    val createdBy: String,
    val createdAt: Instant,
    val decidedBy: String?,
    val note: String?,
    /** The trade's counterparty and legal entity, for queues and filters. */
    val counterpartyId: UUID? = null,
    val legalEntityId: UUID? = null,
)

/** A confirmation received from the counterparty (JSON or manual entry). */
data class ReceiveConfirmation(
    val terms: ConfirmationTerms,
    /** The counterparty's confirmation id. */
    val reference: String,
    /** Needed when [ConfirmationTerms.tradeRef] is not given: the trade is found by the counterparty's trade id. */
    val counterpartyId: UUID? = null,
    val source: ConfirmationSource = ConfirmationSource.JSON,
)

interface ConfirmationCommand {
    /** Issues our confirmation of the trade's current version (JSON terms and PDF); idempotent per version. */
    fun issue(tradeId: UUID): ConfirmationView

    /** Stores the counterparty's confirmation and matches it against ours. */
    fun receive(command: ReceiveConfirmation): ConfirmationView

    /** CSV import: one line per delivery; lines with the same confirmation reference form one confirmation. */
    fun receiveCsv(counterpartyId: UUID, content: String): List<ConfirmationView>
}

/** Counterparty confirmations to work on, newest first. */
data class ConfirmationFilter(
    val statuses: Set<ConfirmationStatus> = setOf(ConfirmationStatus.RECEIVED, ConfirmationStatus.MISMATCHED, ConfirmationStatus.DISPUTED),
    val counterpartyId: UUID? = null,
    val legalEntityId: UUID? = null,
    val limit: Int = 200,
    /** Restricts the list to these legal entities; set by the service from the user's scope. */
    val legalEntityIds: Set<UUID>? = null,
)

interface ConfirmationQuery {
    fun confirmation(id: UUID): ConfirmationView?

    /** Counterparty confirmations (direction THEIRS) in the user's entities. */
    fun queue(filter: ConfirmationFilter): List<ConfirmationView>

    fun forTrade(tradeId: UUID): List<ConfirmationView>
}

data class ConfirmationMatched(val confirmation: ConfirmationView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class ConfirmationMismatched(val confirmation: ConfirmationView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/** A received confirmation left the open states (superseded, overridden or disputed). */
data class ConfirmationClosed(val confirmation: ConfirmationView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
