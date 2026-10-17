package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.settlement.api.AllocationKind
import com.pianonurmi.etrm.settlement.api.InvoiceIssued
import com.pianonurmi.etrm.settlement.api.InvoiceSettled
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.PaymentRecorded
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Settlement in the books: invoices move accrued receivables and payables to invoiced ones with VAT, cash goes
 * through unapplied cash until applied, netting through a clearing account, and a receipt or payment at a different
 * exchange rate than the invoice realises an FX result (also on netting and credit notes). Revenue and cost stay with
 * the delivery postings.
 */
@Component
class SettlementPostings(
    private val ledger: LedgerService,
    private val referenceData: ReferenceDataQuery,
    private val unposted: UnpostedEvents,
) : PostingReplay {
    @EventListener
    fun on(event: InvoiceIssued) = guard(event, event.invoice.legalEntityId) { handle(event) }

    private fun handle(event: InvoiceIssued) {
        val invoice = event.invoice
        val credit = invoice.grossTotal.signum() < 0
        ledger.post(
            PostingFacts(
                legalEntityId = invoice.legalEntityId,
                eventType = "InvoiceIssued",
                eventId = event.eventId,
                postingDate = invoice.invoiceDate,
                currency = invoice.currency,
                sourceType = "INVOICE",
                sourceRef = checkNotNull(invoice.number) { "Invoice ${invoice.id} is not numbered" },
                description = "${invoice.kind} ${invoice.number}" + (invoice.counterpartyInvoiceRef?.let { " (supplier $it)" } ?: ""),
                amounts = mapOf(
                    "net" to invoice.netTotal.abs(),
                    "vat" to invoice.vatTotal.abs(),
                    "gross" to invoice.grossTotal.abs(),
                    "selfAssessedVat" to invoice.lines.sumOf { it.selfAssessedVat }.abs(),
                ),
                attributes = mapOf("direction" to invoice.direction.name, "credit" to credit.toString(), "kind" to invoice.kind.name),
                dimensions = mapOf("invoice" to invoice.number, "counterparty" to invoice.counterpartyId.toString()),
            ),
        )
    }

    @EventListener
    fun on(event: PaymentRecorded) = guard(event, event.payment.legalEntityId) { handle(event) }

    private fun handle(event: PaymentRecorded) {
        val p = event.payment
        ledger.post(
            PostingFacts(
                legalEntityId = p.legalEntityId,
                eventType = "PaymentRecorded",
                eventId = event.eventId,
                postingDate = p.valueDate,
                currency = p.currency,
                sourceType = "PAYMENT",
                sourceRef = p.bankReference,
                description = "${p.direction.name.lowercase().replaceFirstChar(Char::uppercase)} payment ${p.bankReference}",
                amounts = mapOf("amount" to p.amount),
                attributes = mapOf("direction" to p.direction.name),
                dimensions = mapOf("payment" to p.bankReference, "counterparty" to p.counterpartyId?.toString()),
            ),
        )
    }

    /**
     * Payments and netting settle invoiced balances, and refunds and netting also settle the unused amount of a credit
     * note (the mirror postings). A credit note was booked when it was issued; applying it to its original only
     * realises the exchange difference between the two dates.
     */
    @EventListener
    fun on(event: InvoiceSettled) = guard(event, event.invoice.legalEntityId) { handle(event) }

    private fun handle(event: InvoiceSettled) {
        val invoice = event.invoice
        val allocation = event.allocation
        if (allocation.kind == AllocationKind.CREDIT_NOTE) {
            event.creditNote?.let { realisedFx(event, it.invoiceDate) }
            return
        }
        val date = event.payment?.valueDate ?: LocalDate.ofInstant(allocation.allocatedAt, ZoneOffset.UTC)
        val creditNote = invoice.grossTotal.signum() < 0
        val kind = when {
            !creditNote -> allocation.kind.name
            allocation.kind == AllocationKind.PAYMENT -> "REFUND"
            else -> "NETTING_CREDIT"
        }
        ledger.post(
            PostingFacts(
                legalEntityId = invoice.legalEntityId,
                eventType = "InvoiceSettled",
                eventId = event.eventId,
                postingDate = date,
                currency = invoice.currency,
                sourceType = "INVOICE",
                sourceRef = checkNotNull(invoice.number) { "Invoice ${invoice.id} is not numbered" },
                description = "${allocation.kind.name.lowercase().replaceFirstChar(Char::uppercase)} on ${invoice.number}",
                amounts = mapOf("amount" to allocation.amount),
                attributes = mapOf("kind" to kind, "direction" to invoice.direction.name),
                dimensions = dimensionsOf(invoice),
            ),
        )
        realisedFx(event, date)
    }

    private fun dimensionsOf(invoice: InvoiceView) = mapOf("invoice" to invoice.number, "counterparty" to invoice.counterpartyId.toString())

    /**
     * The settled part was booked at the invoice date's rate and settled at the other date's (value date, netting date
     * or credit note date): the difference is realised. A credit note, owed the other way round, gains and loses inversely.
     */
    private fun realisedFx(event: InvoiceSettled, paidOn: LocalDate) {
        val invoice = event.invoice
        val base = referenceData.requireLegalEntity(invoice.legalEntityId).baseCurrency.currencyCode
        if (invoice.currency == base) return
        val amount = event.allocation.amount
        val difference = (amount * ledger.rateOn(paidOn, invoice.currency, base) - amount * ledger.rateOn(invoice.invoiceDate, invoice.currency, base))
            .setScale(2, RoundingMode.HALF_UP)
        if (difference.signum() == 0) return
        // A stronger invoice currency is a gain on receivables and a loss on payables, and the reverse on their credit notes.
        val gain = ((difference.signum() > 0) == (invoice.direction == SettlementDirection.RECEIVABLE)) != (invoice.grossTotal.signum() < 0)
        ledger.post(
            PostingFacts(
                legalEntityId = invoice.legalEntityId,
                eventType = "RealisedFx",
                eventId = UUID.nameUUIDFromBytes("${event.eventId}:fx".toByteArray()),
                postingDate = paidOn,
                currency = base,
                sourceType = "INVOICE",
                sourceRef = checkNotNull(invoice.number) { "Invoice ${invoice.id} is not numbered" },
                description = "Realised FX on ${invoice.number} (${invoice.currency} ${amount.toPlainString()})",
                amounts = mapOf((if (gain) "fxGain" else "fxLoss") to difference.abs()),
                attributes = mapOf("direction" to invoice.direction.name),
                // The period end's FX revaluation counts these base-currency lines with the item they settled.
                dimensions = dimensionsOf(invoice) + ("revaluedCurrency" to invoice.currency),
            ),
        )
    }

    override fun replay(event: DomainEvent): Boolean {
        when (event) {
            is InvoiceIssued -> on(event)
            is PaymentRecorded -> on(event)
            is InvoiceSettled -> on(event)
            else -> return false
        }
        return true
    }

    private fun guard(event: DomainEvent, legalEntityId: UUID?, body: () -> Unit) {
        unposted.guard(event, legalEntityId, body = body)
    }
}
