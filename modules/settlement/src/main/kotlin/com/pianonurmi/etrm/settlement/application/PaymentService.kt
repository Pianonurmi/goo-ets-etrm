package com.pianonurmi.etrm.settlement.application

import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.document.api.RenderPdf
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.settlement.api.AllocatePayment
import com.pianonurmi.etrm.settlement.api.AllocationKind
import com.pianonurmi.etrm.settlement.api.AllocationView
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceSettled
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.NettingCommand
import com.pianonurmi.etrm.settlement.api.NettingQuery
import com.pianonurmi.etrm.settlement.api.NettingSettled
import com.pianonurmi.etrm.settlement.api.NettingStatementView
import com.pianonurmi.etrm.settlement.api.NettingStatus
import com.pianonurmi.etrm.settlement.api.PaymentCommand
import com.pianonurmi.etrm.settlement.api.PaymentDirection
import com.pianonurmi.etrm.settlement.api.PaymentQuery
import com.pianonurmi.etrm.settlement.api.PaymentRecorded
import com.pianonurmi.etrm.settlement.api.PaymentSource
import com.pianonurmi.etrm.settlement.api.PaymentStatus
import com.pianonurmi.etrm.settlement.api.PaymentView
import com.pianonurmi.etrm.settlement.api.PrepareNetting
import com.pianonurmi.etrm.settlement.api.RecordPayment
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.domain.AllocationRepository
import com.pianonurmi.etrm.settlement.domain.Amounts
import com.pianonurmi.etrm.settlement.domain.InvoiceRepository
import com.pianonurmi.etrm.settlement.domain.NettingRepository
import com.pianonurmi.etrm.settlement.domain.PaymentRepository
import com.pianonurmi.etrm.settlement.domain.SettlementItemRepository
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A credit note has a negative total; what is left of it is owed to the counterparty (or by it) and is refunded or netted. */
private val InvoiceView.isCreditNote: Boolean get() = grossTotal.signum() < 0

/** The side of the books that a settled invoice or credit note belongs to, seen from the counterparty's payment. */
private fun PaymentDirection.settles(invoice: InvoiceView): Boolean = (settles == invoice.direction) != invoice.isCreditNote

/** Whole tokens of a text: letters and digits, upper case; invoice numbers match as token sequences ("INV-12" is not in "INV-123"). */
private fun tokens(text: String): List<String> = text.uppercase().split(NON_ALPHANUMERIC).filter { it.isNotEmpty() }

private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

private fun mentions(remittance: List<String>, reference: String): Boolean {
    val wanted = tokens(reference)
    return wanted.isNotEmpty() && remittance.windowed(wanted.size).any { it == wanted }
}

/**
 * Settles invoices: every payment, credit note and netting applies through here, so outstanding amounts, payment
 * status and the trade's SETTLED status follow from the allocations.
 */
@Component
class InvoiceSettlement(
    private val invoices: InvoiceRepository,
    private val allocations: AllocationRepository,
    private val items: SettlementItemRepository,
    private val trades: TradeQuery,
    private val tradeCommand: TradeCommand,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * Applies up to [amount] (a positive magnitude) to the invoice; returns what was applied (zero when nothing is
     * outstanding). A credit note's balance is negative: what is refunded or netted against it is applied the same way.
     */
    fun apply(
        invoiceId: UUID,
        amount: BigDecimal,
        kind: AllocationKind,
        by: String,
        payment: PaymentView? = null,
        creditNote: InvoiceView? = null,
        nettingId: UUID? = null,
    ): BigDecimal {
        val invoice = invoices.find(invoiceId, forUpdate = true) ?: throw NotFoundException("Invoice", invoiceId)
        if (invoice.status != InvoiceStatus.ISSUED) throw IllegalStateTransitionException("Invoice $invoiceId", invoice.status.name, "settled")
        val applied = amount.min(invoice.outstanding.abs())
        if (applied.signum() <= 0) return BigDecimal.ZERO.setScale(2)
        val allocation = AllocationView(Ids.newId(), invoiceId, kind, applied, payment?.id, creditNote?.id, nettingId, by, clock.instant())
        allocations.insert(allocation)
        val paid = if (invoice.isCreditNote) invoice.paidAmount - applied else invoice.paidAmount + applied
        val next = invoice.copy(
            paidAmount = paid,
            paymentStatus = if (paid.abs().compareTo(invoice.grossTotal.abs()) >= 0) PaymentStatus.PAID else PaymentStatus.PARTIALLY_PAID,
        )
        invoices.update(next)
        events.publishEvent(InvoiceSettled(next, allocation, payment, creditNote))
        if (next.paymentStatus == PaymentStatus.PAID && !next.isCreditNote) settleTrades(next)
        return applied
    }

    /** A credit note settles the invoice it reverses; it is itself fully used. */
    fun applyCreditNote(creditNote: InvoiceView, by: String) {
        val original = creditNote.originalInvoiceId ?: return
        val applied = apply(original, creditNote.grossTotal.negate(), AllocationKind.CREDIT_NOTE, by, creditNote = creditNote)
        // What the original no longer needed stays on the credit note, to be refunded or netted.
        val used = applied.negate()
        val current = invoices.find(creditNote.id, forUpdate = true)!!
        val status = when {
            used.compareTo(current.grossTotal) == 0 -> PaymentStatus.PAID
            applied.signum() > 0 -> PaymentStatus.PARTIALLY_PAID
            else -> PaymentStatus.UNPAID
        }
        invoices.update(current.copy(paidAmount = used, paymentStatus = status))
    }

    /** A trade is settled once every one of its deliveries is invoiced and every such invoice is paid. */
    private fun settleTrades(invoice: InvoiceView) {
        val tradeIds = invoice.lines.mapNotNull { it.settlementItemId }.mapNotNull { items.find(it)?.tradeId }.toSet()
        tradeIds.forEach { tradeId ->
            val tradeItems = items.find(SettlementItemFilter(tradeId = tradeId))
            val allPaid = tradeItems.isNotEmpty() && tradeItems.all { item ->
                item.status == SettlementItemStatus.INVOICED &&
                    item.invoiceId?.let { invoices.find(it) }?.let { it.paymentStatus == PaymentStatus.PAID && !it.credited } == true
            }
            if (allPaid && trades.trade(tradeId)?.status == TradeStatus.DELIVERED) tradeCommand.markSettled(tradeId)
        }
    }

    fun allocations(invoiceId: UUID): List<AllocationView> = allocations.forInvoice(invoiceId)
}

@Service
@Transactional
class PaymentService(
    private val payments: PaymentRepository,
    private val invoices: InvoiceRepository,
    private val settlement: InvoiceSettlement,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) : PaymentCommand, PaymentQuery {
    override fun record(command: RecordPayment): PaymentView {
        val actor = actor()
        return record(command, PaymentSource.MANUAL, actor)
    }

    override fun importCsv(legalEntityId: UUID, content: String): List<PaymentView> {
        val actor = actor()
        val rows = Csv.parse(content.trim())
        if (rows.size < 2) throw ValidationException("The bank statement has no lines")
        val header = rows.first().map { it.trim().lowercase() }
        return rows.drop(1).filter { r -> r.any { it.isNotBlank() } }.mapIndexed { i, row ->
            if (row.size != header.size) throw ValidationException("Line ${i + 2} has ${row.size} fields; the header has ${header.size}")
            val line = header.zip(row.map(String::trim)).toMap()
            val signed = BigDecimal(line.getValue("amount"))
            if (signed.signum() == 0) throw ValidationException("Line ${i + 2} has a zero amount")
            val counterparty = line["counterparty"]?.takeIf { it.isNotBlank() }?.let { code ->
                referenceData.counterpartyByCode(code.uppercase())?.id ?: throw ValidationException("Line ${i + 2}: unknown counterparty $code")
            }
            record(
                RecordPayment(
                    legalEntityId = legalEntityId,
                    direction = if (signed.signum() > 0) PaymentDirection.INCOMING else PaymentDirection.OUTGOING,
                    amount = signed.abs(),
                    currency = line.getValue("currency"),
                    valueDate = LocalDate.parse(line.getValue("value_date")),
                    bankReference = line.getValue("bank_reference"),
                    counterpartyId = counterparty,
                    remittanceInfo = line["remittance"]?.takeIf { it.isNotBlank() },
                ),
                PaymentSource.CSV,
                actor,
            )
        }
    }

    override fun allocate(paymentId: UUID, command: AllocatePayment): PaymentView {
        val actor = actor()
        val payment = payments.find(paymentId, forUpdate = true) ?: throw NotFoundException("Payment", paymentId)
        val invoice = invoices.find(command.invoiceId) ?: throw NotFoundException("Invoice", command.invoiceId)
        requireMatches(payment, invoice)
        val wanted = (command.amount ?: payment.unappliedAmount).min(payment.unappliedAmount)
        if (wanted.signum() <= 0) throw ConflictException("Payment ${payment.bankReference} has no unapplied cash")
        val applied = settlement.apply(invoice.id, wanted, AllocationKind.PAYMENT, actor, payment = payment)
        val next = payment.copy(unappliedAmount = payment.unappliedAmount - applied)
        payments.updateUnapplied(payment.id, next.unappliedAmount)
        return next
    }

    @Transactional(readOnly = true)
    override fun payment(id: UUID): PaymentView? = payments.find(id)

    @Transactional(readOnly = true)
    override fun payments(legalEntityId: UUID, counterpartyId: UUID?, unappliedOnly: Boolean): List<PaymentView> =
        payments.list(legalEntityId, counterpartyId, unappliedOnly)

    @Transactional(readOnly = true)
    override fun allocations(invoiceId: UUID): List<AllocationView> = settlement.allocations(invoiceId)

    // ----------------------------------------------------------------------------------------------------------

    private fun record(command: RecordPayment, source: PaymentSource, actor: String): PaymentView {
        val reference = command.bankReference.trim().ifBlank { throw ValidationException("The bank reference is required") }
        payments.byReference(command.legalEntityId, reference)?.let { return it }
        if (command.amount.signum() <= 0) throw ValidationException("The amount must be positive; use the direction for outgoing payments")
        referenceData.requireLegalEntity(command.legalEntityId)
        val currency = command.currency.uppercase()
        val explicit = command.invoiceIds.map { invoices.find(it) ?: throw NotFoundException("Invoice", it) }
        val referenced = if (explicit.isEmpty()) byRemittance(command, currency) else explicit
        val counterparty = command.counterpartyId ?: referenced.map { it.counterpartyId }.distinct().singleOrNull()
        var payment = PaymentView(
            id = Ids.newId(),
            legalEntityId = command.legalEntityId,
            counterpartyId = counterparty,
            direction = command.direction,
            amount = Amounts.money(command.amount),
            currency = currency,
            valueDate = command.valueDate,
            bankReference = reference,
            remittanceInfo = command.remittanceInfo?.trim(),
            source = source,
            unappliedAmount = Amounts.money(command.amount),
            createdBy = actor,
        )
        payments.insert(payment)
        events.publishEvent(PaymentRecorded(payment))
        referenced.forEach { invoice ->
            requireMatches(payment, invoice)
            if (payment.unappliedAmount.signum() > 0) {
                val applied = settlement.apply(invoice.id, payment.unappliedAmount, AllocationKind.PAYMENT, actor, payment = payment)
                payment = payment.copy(unappliedAmount = payment.unappliedAmount - applied)
            }
        }
        payments.updateUnapplied(payment.id, payment.unappliedAmount)
        return payment
    }

    /** Invoice numbers (ours, or the supplier's for outgoing payments) quoted in the remittance information, as whole tokens. */
    private fun byRemittance(command: RecordPayment, currency: String): List<InvoiceView> {
        val text = tokens(command.remittanceInfo ?: return emptyList())
        return invoices.find(InvoiceFilter(legalEntityId = command.legalEntityId, statuses = setOf(InvoiceStatus.ISSUED), limit = 10_000))
            .filter { it.currency == currency && command.direction.settles(it) && it.paymentStatus != PaymentStatus.PAID }
            .filter { inv -> listOfNotNull(inv.number, inv.counterpartyInvoiceRef).any { mentions(text, it) } }
            .sortedBy { it.dueDate }
    }

    private fun requireMatches(payment: PaymentView, invoice: InvoiceView) {
        if (invoice.legalEntityId != payment.legalEntityId || invoice.currency != payment.currency) {
            throw ValidationException("Invoice ${invoice.number} is not in ${payment.currency} for this legal entity")
        }
        if (!payment.direction.settles(invoice)) throw ValidationException("A ${payment.direction} payment cannot settle invoice ${invoice.number}")
        if (payment.counterpartyId != null && payment.counterpartyId != invoice.counterpartyId) {
            throw ValidationException("Invoice ${invoice.number} is with another counterparty")
        }
    }

    private fun actor(): String {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.ACCOUNTANT)
        return actor.userId
    }
}

/**
 * Netting: receivables and payables with one counterparty in one currency are offset; only the net amount moves.
 * Credit notes count on the side that owes the money: a sales credit note is a claim of the counterparty, a purchase
 * credit note one of ours. The counterparty's agreement is recorded by a second person, and settlement needs the net
 * payment. The amounts are those outstanding when the statement was prepared: a payment applied since refuses it.
 */
@Service
@Transactional
class NettingService(
    private val statements: NettingRepository,
    private val invoices: InvoiceRepository,
    private val payments: PaymentRepository,
    private val settlement: InvoiceSettlement,
    private val referenceData: ReferenceDataQuery,
    private val documents: DocumentCommand,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) : NettingCommand, NettingQuery {
    override fun prepare(command: PrepareNetting): NettingStatementView {
        val actor = actor()
        val counterparty = referenceData.requireCounterparty(command.counterpartyId)
        if (!counterparty.nettingAgreed) throw ValidationException("There is no netting agreement with ${counterparty.code}")
        val taken = statements.invoicesInOpenStatements(command.legalEntityId, command.counterpartyId)
        val open = invoices.find(
            InvoiceFilter(command.legalEntityId, command.counterpartyId, statuses = setOf(InvoiceStatus.ISSUED), limit = 10_000),
        ).filter {
            it.currency == command.currency.uppercase() && it.outstanding.signum() != 0 && it.id !in taken &&
                (it.dueDate == null || it.dueDate <= command.valueDate)
        }
        val receivable = open.filter { weClaim(it) }.sumOf { it.outstanding.abs() }
        val payable = open.filterNot { weClaim(it) }.sumOf { it.outstanding.abs() }
        if (receivable.signum() == 0 || payable.signum() == 0) throw ValidationException("Netting needs open invoices in both directions")
        val id = Ids.newId()
        val document = documents.renderPdf(
            RenderPdf(
                kind = "NETTING_STATEMENT",
                ownerType = "NETTING_STATEMENT",
                ownerRef = id,
                legalEntityId = command.legalEntityId,
                title = "Netting statement ${counterparty.code} ${command.currency.uppercase()} ${command.valueDate}",
                fileName = "netting-${counterparty.code}-${command.valueDate}.pdf",
                template = "netting-statement",
                model = mapOf(
                    "counterparty" to counterparty.name,
                    "currency" to command.currency.uppercase(),
                    "valueDate" to command.valueDate.toString(),
                    "lines" to open.map {
                        mapOf(
                            "number" to (it.counterpartyInvoiceRef?.let { ref -> "${it.number} ($ref)" } ?: it.number),
                            "direction" to if (weClaim(it)) "We claim" else "You claim",
                            "dueDate" to it.dueDate.toString(),
                            "amount" to it.outstanding.abs().toPlainString(),
                        )
                    },
                    "receivable" to receivable.toPlainString(),
                    "payable" to payable.toPlainString(),
                    "net" to (receivable - payable).toPlainString(),
                    "payer" to if (receivable > payable) counterparty.name else "us",
                ),
            ),
        )
        val statement = NettingStatementView(
            id = id,
            legalEntityId = command.legalEntityId,
            counterpartyId = command.counterpartyId,
            currency = command.currency.uppercase(),
            valueDate = command.valueDate,
            status = NettingStatus.DRAFT,
            receivableTotal = receivable,
            payableTotal = payable,
            netAmount = receivable - payable,
            invoices = open.associate { it.id to it.outstanding.abs() },
            counterpartyRef = null,
            settlementPaymentId = null,
            documentId = document.id,
            createdBy = actor,
            agreedBy = null,
            version = 1,
        )
        statements.insert(statement)
        return statement
    }

    override fun agree(statementId: UUID, counterpartyRef: String?): NettingStatementView {
        val actor = actor()
        val statement = locked(statementId)
        if (statement.status != NettingStatus.DRAFT) throw IllegalStateTransitionException("Netting statement", statement.status.name, "AGREED")
        SecondPerson.require(actor, statement.createdBy) { "The person who prepared the statement may not record its agreement" }
        requireUnchanged(statement)
        val agreed = statement.copy(status = NettingStatus.AGREED, agreedBy = actor, counterpartyRef = counterpartyRef?.trim(), version = statement.version + 1)
        statements.update(agreed)
        return agreed
    }

    override fun settle(statementId: UUID, paymentId: UUID?): NettingStatementView {
        val actor = actor()
        val statement = locked(statementId)
        if (statement.status != NettingStatus.AGREED) throw IllegalStateTransitionException("Netting statement", statement.status.name, "SETTLED")
        val payment = when {
            statement.netAmount.signum() == 0 -> null
            paymentId == null -> throw ValidationException("The net amount ${statement.currency} ${statement.netAmount.abs()} must be paid first")
            else -> payments.find(paymentId, forUpdate = true) ?: throw NotFoundException("Payment", paymentId)
        }
        if (payment != null) {
            val expected = if (statement.netAmount.signum() > 0) PaymentDirection.INCOMING else PaymentDirection.OUTGOING
            if (payment.direction != expected || payment.currency != statement.currency || payment.unappliedAmount.compareTo(statement.netAmount.abs()) != 0) {
                throw ValidationException("Payment ${payment.bankReference} is not the unapplied ${expected.name.lowercase()} net amount of the statement")
            }
        }
        requireUnchanged(statement)
        // The smaller side is offset in full against the larger; the larger side's remainder is paid by the net payment.
        val offset = statement.receivableTotal.min(statement.payableTotal)
        val bySide = statement.invoices.keys.map { invoices.find(it)!! }.groupBy { weClaim(it) }
        listOf(true, false).forEach { ours ->
            var remaining = offset
            bySide[ours].orEmpty().sortedBy { it.dueDate }.forEach { invoice ->
                if (remaining.signum() > 0) remaining -= settlement.apply(invoice.id, remaining, AllocationKind.NETTING, actor, nettingId = statement.id)
            }
        }
        payment?.let { p ->
            var unapplied = p.unappliedAmount
            // An incoming net payment settles what we claim, an outgoing one what the counterparty claims.
            bySide[p.direction == PaymentDirection.INCOMING].orEmpty().sortedBy { it.dueDate }.forEach { invoice ->
                if (unapplied.signum() > 0) unapplied -= settlement.apply(invoice.id, unapplied, AllocationKind.PAYMENT, actor, payment = p)
            }
            payments.updateUnapplied(p.id, unapplied)
        }
        val settled = statement.copy(status = NettingStatus.SETTLED, settlementPaymentId = payment?.id, version = statement.version + 1)
        statements.update(settled)
        events.publishEvent(NettingSettled(settled))
        return settled
    }

    override fun cancel(statementId: UUID, reason: String): NettingStatementView {
        actor()
        val statement = locked(statementId)
        if (statement.status == NettingStatus.SETTLED || statement.status == NettingStatus.CANCELLED) {
            throw IllegalStateTransitionException("Netting statement", statement.status.name, "CANCELLED")
        }
        val cancelled = statement.copy(status = NettingStatus.CANCELLED, version = statement.version + 1)
        statements.update(cancelled)
        return cancelled
    }

    @Transactional(readOnly = true)
    override fun statement(id: UUID): NettingStatementView? = statements.find(id)

    @Transactional(readOnly = true)
    override fun statements(legalEntityId: UUID, counterpartyId: UUID?): List<NettingStatementView> = statements.list(legalEntityId, counterpartyId)

    /** Whether the invoice or credit note is money the counterparty owes us (a receivable, or a purchase credit note). */
    private fun weClaim(invoice: InvoiceView): Boolean = (invoice.direction == SettlementDirection.RECEIVABLE) != invoice.isCreditNote

    /** The statement settles the amounts it captured; an invoice paid, credited or netted since needs a new statement. */
    private fun requireUnchanged(statement: NettingStatementView) {
        statement.invoices.forEach { (id, captured) ->
            val invoice = invoices.find(id) ?: throw NotFoundException("Invoice", id)
            if (invoice.status != InvoiceStatus.ISSUED || invoice.outstanding.abs().compareTo(captured) != 0) {
                throw ValidationException(
                    "Invoice ${invoice.number} changed after the statement was prepared (${captured.toPlainString()} outstanding then, " +
                        "${invoice.outstanding.abs().toPlainString()} now); cancel the statement and prepare a new one",
                )
            }
        }
    }

    private fun locked(id: UUID): NettingStatementView = statements.find(id, forUpdate = true) ?: throw NotFoundException("NettingStatement", id)

    private fun actor(): String {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.ACCOUNTANT)
        return actor.userId
    }
}
