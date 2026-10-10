package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.settlement.api.AllocationKind
import com.pianonurmi.etrm.settlement.api.InvoiceIssued
import com.pianonurmi.etrm.settlement.api.InvoiceSettled
import com.pianonurmi.etrm.settlement.api.PaymentRecorded
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.PriceType
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Settlement in the books: invoices move accrued receivables and payables to invoiced ones with VAT, cash goes
 * through unapplied cash until applied, netting through a clearing account, and a receipt or payment at a different
 * exchange rate than the invoice realises an FX result. Revenue and cost stay with the delivery postings.
 */
@Component
class SettlementPostings(private val ledger: LedgerService, private val referenceData: ReferenceDataQuery) {
    /** A delivery releases its share of the trade's commitment memo. */
    @EventListener
    fun on(event: DeliveryRecorded) {
        val o = event.obligation
        if (o.priceType != PriceType.FIXED) return
        ledger.post(
            PostingFacts(
                legalEntityId = o.legalEntityId,
                eventType = "DeliveryRecorded",
                eventId = event.eventId,
                postingDate = event.deliveredOn,
                currency = o.priceCurrency.currencyCode,
                sourceType = "DELIVERY",
                sourceRef = event.deliveryId.toString(),
                description = "Delivered ${event.quantity.toPlainString()} on ${o.tradeNumber}",
                amounts = mapOf("deliveredValue" to (event.quantity * o.price).setScale(2, RoundingMode.HALF_UP)),
                attributes = mapOf("side" to o.side.name, "productFamily" to o.productFamily.name),
                dimensions = mapOf("trade" to o.tradeNumber, "counterparty" to o.counterpartyId.toString()),
            ),
        )
    }

    @EventListener
    fun on(event: InvoiceIssued) {
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
                sourceRef = invoice.number!!,
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
    fun on(event: PaymentRecorded) {
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

    /** Payments and netting settle invoiced balances; credit notes were already booked when they were issued. */
    @EventListener
    fun on(event: InvoiceSettled) {
        val invoice = event.invoice
        val allocation = event.allocation
        if (allocation.kind == AllocationKind.CREDIT_NOTE) return
        val date = event.payment?.valueDate ?: LocalDate.ofInstant(allocation.allocatedAt, ZoneOffset.UTC)
        val dimensions = mapOf("invoice" to invoice.number, "counterparty" to invoice.counterpartyId.toString())
        ledger.post(
            PostingFacts(
                legalEntityId = invoice.legalEntityId,
                eventType = "InvoiceSettled",
                eventId = event.eventId,
                postingDate = date,
                currency = invoice.currency,
                sourceType = "INVOICE",
                sourceRef = invoice.number!!,
                description = "${allocation.kind.name.lowercase().replaceFirstChar(Char::uppercase)} on ${invoice.number}",
                amounts = mapOf("amount" to allocation.amount),
                attributes = mapOf("kind" to allocation.kind.name, "direction" to invoice.direction.name),
                dimensions = dimensions,
            ),
        )
        if (allocation.kind == AllocationKind.PAYMENT) realisedFx(event, date, dimensions)
    }

    /** The settled part was booked at the invoice date's rate and paid at the value date's: the difference is realised. */
    private fun realisedFx(event: InvoiceSettled, paidOn: LocalDate, dimensions: Map<String, String?>) {
        val invoice = event.invoice
        val base = referenceData.requireLegalEntity(invoice.legalEntityId).baseCurrency.currencyCode
        if (invoice.currency == base) return
        val amount = event.allocation.amount
        val difference = (amount * ledger.rateOn(paidOn, invoice.currency, base) - amount * ledger.rateOn(invoice.invoiceDate, invoice.currency, base))
            .setScale(2, RoundingMode.HALF_UP)
        if (difference.signum() == 0) return
        // A stronger invoice currency is a gain on receivables and a loss on payables.
        val gain = (difference.signum() > 0) == (invoice.direction == SettlementDirection.RECEIVABLE)
        ledger.post(
            PostingFacts(
                legalEntityId = invoice.legalEntityId,
                eventType = "RealisedFx",
                eventId = UUID.nameUUIDFromBytes("${event.eventId}:fx".toByteArray()),
                postingDate = paidOn,
                currency = base,
                sourceType = "INVOICE",
                sourceRef = invoice.number!!,
                description = "Realised FX on ${invoice.number} (${invoice.currency} ${amount.toPlainString()})",
                amounts = mapOf((if (gain) "fxGain" else "fxLoss") to difference.abs()),
                attributes = mapOf("direction" to invoice.direction.name),
                dimensions = dimensions,
            ),
        )
    }
}
