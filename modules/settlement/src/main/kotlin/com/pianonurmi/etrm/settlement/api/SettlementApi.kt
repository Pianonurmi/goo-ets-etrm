package com.pianonurmi.etrm.settlement.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.product.api.ProductFamily
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// --------------------------------------------------------------------------------------------------------------
// VAT rules
// --------------------------------------------------------------------------------------------------------------

/** Where supplier and customer are, seen from the supplier: same country, EU to EU, from outside the EU, to outside the EU. */
enum class VatScope { DOMESTIC, INTRA_EU, IMPORT, EXPORT, ANY }

enum class VatTreatment(val reverseCharge: Boolean) {
    STANDARD(false),
    REVERSE_CHARGE_DOMESTIC(true),
    REVERSE_CHARGE_CROSS_BORDER(true),
    OUT_OF_SCOPE(false),
    EXEMPT(false),
}

enum class VatRuleStatus { DRAFT, APPROVED, RETIRED }

data class VatRuleView(
    val id: UUID,
    val code: String,
    val version: Int,
    val description: String,
    /** A product family, or `*` for any. */
    val productFamily: String,
    /** An ISO country, or `*` for any. */
    val supplierCountry: String,
    val scope: VatScope,
    val treatment: VatTreatment,
    val rate: BigDecimal,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val legalText: String?,
    val requiresSignOff: Boolean,
    val status: VatRuleStatus,
    val createdBy: String,
    val approvedBy: String?,
)

data class ProposeVatRule(
    val code: String,
    val description: String,
    val productFamily: String = "*",
    val supplierCountry: String = "*",
    val scope: VatScope,
    val treatment: VatTreatment,
    val rate: BigDecimal = BigDecimal.ZERO,
    val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    val legalText: String? = null,
)

/** The rule that applies to one supply, or why none does. */
data class VatDetermination(val rule: VatRuleView?, val problem: String?)

interface VatRuleCommand {
    /** A new rule (or a new version of a code) as DRAFT; a second person approves it in the inbox. */
    fun propose(command: ProposeVatRule): VatRuleView

    fun approve(ruleId: UUID, by: String): VatRuleView

    fun retire(ruleId: UUID, reason: String): VatRuleView
}

interface VatRuleQuery {
    fun rules(includeRetired: Boolean = false): List<VatRuleView>

    fun determine(productFamily: ProductFamily, supplierCountry: String, customerCountry: String, taxPointDate: LocalDate): VatDetermination
}

// --------------------------------------------------------------------------------------------------------------
// Settlement items and price fixing
// --------------------------------------------------------------------------------------------------------------

/** RECEIVABLE: we delivered (sold); PAYABLE: we received (bought). */
enum class SettlementDirection { RECEIVABLE, PAYABLE }

enum class SettlementItemStatus { OPEN, NEEDS_ATTENTION, INVOICED, CANCELLED }

data class SettlementItemView(
    val id: UUID,
    val deliveryId: UUID,
    val tradeId: UUID,
    val tradeNumber: String,
    val scheduleId: UUID,
    val legalEntityId: UUID,
    val counterpartyId: UUID,
    val direction: SettlementDirection,
    val productFamily: ProductFamily,
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal?,
    val currency: String,
    val netAmount: BigDecimal?,
    val deliveredOn: LocalDate,
    val vatRuleCode: String?,
    val vatRuleVersion: Int?,
    val vatTreatment: VatTreatment?,
    val vatRate: BigDecimal?,
    val vatAmount: BigDecimal?,
    val status: SettlementItemStatus,
    val problem: String?,
    val invoiceId: UUID?,
    val reference: String?,
    val version: Int,
)

data class FixPrice(val scheduleId: UUID, val price: BigDecimal, val note: String? = null)

data class SettlementItemFilter(
    val legalEntityId: UUID? = null,
    val counterpartyId: UUID? = null,
    val statuses: Set<SettlementItemStatus>? = null,
    val tradeId: UUID? = null,
    val limit: Int = 500,
    /** The entities the reader may see; null for all. */
    val legalEntityIds: Set<UUID>? = null,
)

/** One page of settlement items, latest delivery first (then id); [nextCursor] continues the list. */
data class SettlementItemPage(val items: List<SettlementItemView>, val nextCursor: String?)

interface SettlementItemCommand {
    /** Fixes the price of an index or formula schedule; its items waiting for the price are evaluated again. */
    fun fixPrice(command: FixPrice): List<SettlementItemView>

    /** Evaluates an item that needs attention again (after a VAT rule or VAT number was added). */
    fun reevaluate(itemId: UUID): SettlementItemView
}

interface SettlementItemQuery {
    fun items(filter: SettlementItemFilter): List<SettlementItemView>

    /** Keyset-paged items, latest delivery first; [cursor] is a previous page's `nextCursor`. */
    fun itemPage(filter: SettlementItemFilter, cursor: String?): SettlementItemPage

    fun item(id: UUID): SettlementItemView?
}

// --------------------------------------------------------------------------------------------------------------
// Invoices
// --------------------------------------------------------------------------------------------------------------

enum class InvoiceKind(val direction: SettlementDirection, val series: String) {
    SALES(SettlementDirection.RECEIVABLE, "INV"),
    SELF_BILLED(SettlementDirection.PAYABLE, "SB"),
    PURCHASE(SettlementDirection.PAYABLE, "PI"),
    CREDIT_NOTE(SettlementDirection.RECEIVABLE, "CN"),
    PURCHASE_CREDIT_NOTE(SettlementDirection.PAYABLE, "PC"),
}

enum class InvoiceStatus { DRAFT, PENDING_APPROVAL, ISSUED, CANCELLED }

enum class InvoiceLineType { COMMODITY, FEE, ADJUSTMENT }

/** Supplier or customer as printed on the invoice, frozen at issue. */
data class PartySnapshot(
    val name: String,
    val address: String?,
    val country: String,
    val vatNumber: String?,
    val lei: String?,
)

data class BankSnapshot(val accountName: String, val iban: String, val bic: String)

data class InvoiceLineView(
    val lineNo: Int,
    val lineType: InvoiceLineType,
    val settlementItemId: UUID?,
    val description: String,
    val quantity: BigDecimal?,
    val unit: String?,
    val unitPrice: BigDecimal?,
    val netAmount: BigDecimal,
    val vatRuleCode: String?,
    val vatTreatment: VatTreatment,
    val vatRate: BigDecimal,
    val vatAmount: BigDecimal,
    /** Reverse-charged purchases: the VAT we account for ourselves (output and input) at our standard rate. */
    val selfAssessedVat: BigDecimal = BigDecimal.ZERO,
)

data class InvoiceView(
    val id: UUID,
    val legalEntityId: UUID,
    val counterpartyId: UUID,
    val kind: InvoiceKind,
    val status: InvoiceStatus,
    val number: String?,
    val counterpartyInvoiceRef: String?,
    val currency: String,
    val invoiceDate: LocalDate,
    val taxPointDate: LocalDate,
    val dueDate: LocalDate?,
    val netTotal: BigDecimal,
    val vatTotal: BigDecimal,
    val grossTotal: BigDecimal,
    val supplier: PartySnapshot?,
    val customer: PartySnapshot?,
    val bankAccount: BankSnapshot?,
    /** Legal notes printed on the invoice: reverse charge, self-billing. */
    val notes: List<String>,
    val originalInvoiceId: UUID?,
    val documentId: UUID?,
    val lines: List<InvoiceLineView>,
    val createdBy: String,
    val submittedBy: String?,
    val approvedBy: String?,
    val issuedAt: Instant?,
    val version: Int,
    val paidAmount: BigDecimal = BigDecimal.ZERO,
    val paymentStatus: PaymentStatus = PaymentStatus.UNPAID,
    /** A credit note reversed this invoice. */
    val credited: Boolean = false,
) {
    val direction: SettlementDirection get() = kind.direction

    /** What is still to be paid (negative for a credit note not yet applied). */
    val outstanding: BigDecimal get() = grossTotal - paidAmount
}

enum class PaymentStatus { UNPAID, PARTIALLY_PAID, PAID }

/** Drafts sales (and self-billed) invoices for the open items delivered up to [upTo]. */
data class InvoiceRun(
    val legalEntityId: UUID,
    val upTo: LocalDate,
    val counterpartyId: UUID? = null,
    val invoiceDate: LocalDate? = null,
)

/** A supplier's invoice, matched to our open payable items within the tolerance. */
data class RegisterPurchaseInvoice(
    val legalEntityId: UUID,
    val counterpartyId: UUID,
    val supplierInvoiceRef: String,
    val invoiceDate: LocalDate,
    val currency: String,
    val netAmount: BigDecimal,
    val vatAmount: BigDecimal,
    /** The items it invoices; by default every open payable item of the supplier in that currency. */
    val itemIds: List<UUID>? = null,
)

data class InvoiceFilter(
    val legalEntityId: UUID? = null,
    val counterpartyId: UUID? = null,
    val kinds: Set<InvoiceKind>? = null,
    val statuses: Set<InvoiceStatus>? = null,
    val from: LocalDate? = null,
    val toExclusive: LocalDate? = null,
    val limit: Int = 500,
    /** The entities a paged list is limited to (the reader's scope); null for all. */
    val legalEntityIds: Set<UUID>? = null,
)

/** One page of invoices, newest first (invoice date, then id); [nextCursor] continues the list. */
data class InvoicePage(val items: List<InvoiceView>, val nextCursor: String?)

interface InvoiceCommand {
    fun run(command: InvoiceRun): List<InvoiceView>

    fun registerPurchaseInvoice(command: RegisterPurchaseInvoice): InvoiceView

    /** Sends a draft for a second person's approval (`INVOICE_APPROVAL`); approval issues it. */
    fun submit(invoiceId: UUID): InvoiceView

    /** Discards a draft; its items are open again. */
    fun discard(invoiceId: UUID, reason: String): InvoiceView

    /** Drafts a credit note reversing an issued invoice in full; issuing it settles the original and reopens its items. */
    fun creditNote(invoiceId: UUID, reason: String): InvoiceView
}

interface InvoiceQuery {
    fun invoice(id: UUID): InvoiceView?

    fun invoices(filter: InvoiceFilter): List<InvoiceView>

    /** Keyset-paged invoices, newest first; [cursor] is a previous page's `nextCursor`. */
    fun invoicePage(filter: InvoiceFilter, cursor: String?): InvoicePage

    fun export(filter: InvoiceFilter): String
}

// --------------------------------------------------------------------------------------------------------------
// Events
// --------------------------------------------------------------------------------------------------------------

data class SettlementItemCreated(val item: SettlementItemView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class SettlementItemNeedsAttention(val item: SettlementItemView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class InvoiceSubmitted(val invoice: InvoiceView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/** An invoice was issued (or a purchase invoice registered): numbered, frozen, and ready to post and collect. */
data class InvoiceIssued(val invoice: InvoiceView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class InvoiceDiscarded(val invoice: InvoiceView, val reason: String, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class VatRuleProposed(val rule: VatRuleView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class VatRuleApproved(val rule: VatRuleView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
