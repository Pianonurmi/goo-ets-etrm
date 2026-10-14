package com.pianonurmi.etrm.settlement.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// --------------------------------------------------------------------------------------------------------------
// Payments
// --------------------------------------------------------------------------------------------------------------

/** INCOMING pays our receivables; OUTGOING pays our payables. */
enum class PaymentDirection(val settles: SettlementDirection) {
    INCOMING(SettlementDirection.RECEIVABLE),
    OUTGOING(SettlementDirection.PAYABLE),
}

enum class PaymentSource { MANUAL, CSV }

enum class AllocationKind { PAYMENT, CREDIT_NOTE, NETTING }

data class PaymentView(
    val id: UUID,
    val legalEntityId: UUID,
    val counterpartyId: UUID?,
    val direction: PaymentDirection,
    val amount: BigDecimal,
    val currency: String,
    val valueDate: LocalDate,
    val bankReference: String,
    val remittanceInfo: String?,
    val source: PaymentSource,
    /** Cash not yet applied to an invoice. */
    val unappliedAmount: BigDecimal,
    val createdBy: String,
)

data class AllocationView(
    val id: UUID,
    val invoiceId: UUID,
    val kind: AllocationKind,
    val amount: BigDecimal,
    val paymentId: UUID?,
    val creditNoteId: UUID?,
    val nettingStatementId: UUID?,
    val allocatedBy: String,
    val allocatedAt: Instant,
)

/**
 * A bank payment. Recording the same bank reference again returns the earlier payment. The payment is applied to
 * [invoiceIds] in order, else to invoices whose numbers appear in the remittance information as whole tokens; the rest is unapplied.
 * An outgoing payment may refund a sales credit note, an incoming one a purchase credit note.
 */
data class RecordPayment(
    val legalEntityId: UUID,
    val direction: PaymentDirection,
    val amount: BigDecimal,
    val currency: String,
    val valueDate: LocalDate,
    val bankReference: String,
    val counterpartyId: UUID? = null,
    val remittanceInfo: String? = null,
    val invoiceIds: List<UUID> = emptyList(),
)

data class AllocatePayment(val invoiceId: UUID, val amount: BigDecimal? = null)

interface PaymentCommand {
    fun record(command: RecordPayment): PaymentView

    /**
     * Bank statement CSV: `bank_reference,value_date,amount,currency[,counterparty,remittance]`; a negative amount is
     * outgoing. Lines already imported are skipped.
     */
    fun importCsv(legalEntityId: UUID, content: String): List<PaymentView>

    /** Applies unapplied cash to an invoice (up to its outstanding amount). */
    fun allocate(paymentId: UUID, command: AllocatePayment): PaymentView
}

interface PaymentQuery {
    fun payment(id: UUID): PaymentView?

    fun payments(legalEntityId: UUID, counterpartyId: UUID? = null, unappliedOnly: Boolean = false): List<PaymentView>

    fun allocations(invoiceId: UUID): List<AllocationView>
}

// --------------------------------------------------------------------------------------------------------------
// Netting
// --------------------------------------------------------------------------------------------------------------

enum class NettingStatus { DRAFT, AGREED, SETTLED, CANCELLED }

data class NettingStatementView(
    val id: UUID,
    val legalEntityId: UUID,
    val counterpartyId: UUID,
    val currency: String,
    val valueDate: LocalDate,
    val status: NettingStatus,
    val receivableTotal: BigDecimal,
    val payableTotal: BigDecimal,
    /** Positive: the counterparty pays us; negative: we pay them. */
    val netAmount: BigDecimal,
    val invoices: Map<UUID, BigDecimal>,
    val counterpartyRef: String?,
    val settlementPaymentId: UUID?,
    val documentId: UUID?,
    val createdBy: String,
    val agreedBy: String?,
    val version: Int,
)

data class PrepareNetting(val legalEntityId: UUID, val counterpartyId: UUID, val currency: String, val valueDate: LocalDate)

interface NettingCommand {
    /** Nets every issued, unpaid invoice with the counterparty in the currency due on or before the value date. */
    fun prepare(command: PrepareNetting): NettingStatementView

    /** The counterparty agreed the statement; a second person records it. */
    fun agree(statementId: UUID, counterpartyRef: String?): NettingStatementView

    /** Offsets the invoices; the net amount must be paid by [paymentId] (unless it is zero). */
    fun settle(statementId: UUID, paymentId: UUID?): NettingStatementView

    fun cancel(statementId: UUID, reason: String): NettingStatementView
}

interface NettingQuery {
    fun statement(id: UUID): NettingStatementView?

    fun statements(legalEntityId: UUID, counterpartyId: UUID? = null): List<NettingStatementView>
}

// --------------------------------------------------------------------------------------------------------------
// Reports
// --------------------------------------------------------------------------------------------------------------

data class AgeingRow(
    val counterpartyId: UUID,
    val direction: SettlementDirection,
    val currency: String,
    val notDue: BigDecimal,
    val days1to30: BigDecimal,
    val days31to60: BigDecimal,
    val days61to90: BigDecimal,
    val over90: BigDecimal,
) {
    val total: BigDecimal get() = notDue + days1to30 + days31to60 + days61to90 + over90
}

/** What one counterparty owes or is owed, per direction and currency: invoiced and still open, and delivered but not invoiced. */
data class CounterpartyBalance(
    val counterpartyId: UUID,
    val direction: SettlementDirection,
    val currency: String,
    val invoicedOutstanding: BigDecimal,
    val deliveredNotInvoiced: BigDecimal,
) {
    val total: BigDecimal get() = invoicedOutstanding + deliveredNotInvoiced
}

data class CashForecastRow(val date: LocalDate, val currency: String, val incoming: BigDecimal, val outgoing: BigDecimal, val basis: String)

data class VatReportRow(
    val direction: SettlementDirection,
    val treatment: VatTreatment,
    val rate: BigDecimal,
    val net: BigDecimal,
    val vat: BigDecimal,
    val currency: String,
)

data class EcSalesListRow(val customerVatNumber: String, val country: String, val net: BigDecimal, val currency: String)

interface SettlementReports {
    fun ageing(legalEntityId: UUID, asOf: LocalDate): List<AgeingRow>

    /** Open balances per counterparty, gross of VAT, for credit exposure. */
    fun counterpartyBalances(legalEntityId: UUID): List<CounterpartyBalance>

    fun cashForecast(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<CashForecastRow>

    /** Issued invoices by tax point in the period, by direction, treatment and rate. */
    fun vatReport(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<VatReportRow>

    /** Intra-EU B2B sales (cross-border reverse charge) by customer VAT number. */
    fun ecSalesList(legalEntityId: UUID, from: LocalDate, toExclusive: LocalDate): List<EcSalesListRow>
}

// --------------------------------------------------------------------------------------------------------------
// Events
// --------------------------------------------------------------------------------------------------------------

/** Cash moved through the bank (posted against unapplied cash until allocated). */
data class PaymentRecorded(val payment: PaymentView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/**
 * Part of an invoice settled by a payment, a credit note or netting. [allocation] is a positive amount; when [invoice] is
 * itself a credit note (negative total), the payment is a refund and the netting offsets what the counterparty claims.
 * [creditNote] is the credit note that settled [invoice] when the allocation is of that kind.
 */
data class InvoiceSettled(
    val invoice: InvoiceView,
    val allocation: AllocationView,
    val payment: PaymentView?,
    val creditNote: InvoiceView? = null,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata

data class NettingSettled(val statement: NettingStatementView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
