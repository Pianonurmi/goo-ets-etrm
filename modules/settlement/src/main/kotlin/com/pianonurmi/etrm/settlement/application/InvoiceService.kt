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
import com.pianonurmi.etrm.platform.paging.Cursor
import com.pianonurmi.etrm.platform.paging.page
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.referencedata.api.PartyDetailsQuery
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.settlement.api.BankSnapshot
import com.pianonurmi.etrm.settlement.api.InvoiceCommand
import com.pianonurmi.etrm.settlement.api.InvoiceDiscarded
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceIssued
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceLineType
import com.pianonurmi.etrm.settlement.api.InvoiceLineView
import com.pianonurmi.etrm.settlement.api.InvoicePage
import com.pianonurmi.etrm.settlement.api.InvoiceQuery
import com.pianonurmi.etrm.settlement.api.InvoiceRun
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.InvoiceSubmitted
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.PartySnapshot
import com.pianonurmi.etrm.settlement.api.PaymentStatus
import com.pianonurmi.etrm.settlement.api.RegisterPurchaseInvoice
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.api.VatTreatment
import com.pianonurmi.etrm.settlement.domain.Amounts
import com.pianonurmi.etrm.settlement.domain.InvoiceRepository
import com.pianonurmi.etrm.settlement.domain.NumberSeriesRepository
import com.pianonurmi.etrm.settlement.domain.SettlementItemRepository
import com.pianonurmi.etrm.settlement.domain.StandardRateRepository
import com.pianonurmi.etrm.settlement.domain.VatRuleRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Invoices: drafted by an invoice run (sales and self-billed) or registered from a supplier's invoice, approved by a
 * second person and issued with a gap-free number, frozen party and bank details, due date and a PDF.
 */
@Service
@Transactional
@Suppress("LongParameterList") // invoices draw on items, numbering, VAT, parties, documents and rates
class InvoiceService(
    private val repository: InvoiceRepository,
    private val items: SettlementItemRepository,
    private val numbers: NumberSeriesRepository,
    private val vatRules: VatRuleRepository,
    private val referenceData: ReferenceDataQuery,
    private val parties: PartyDetailsQuery,
    private val documents: DocumentCommand,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val settlement: InvoiceSettlement,
    private val standardRates: StandardRateRepository,
) : InvoiceCommand, InvoiceQuery {
    /** Today in the entity's time zone: an invoice is dated where it is issued, not in UTC. */
    private fun today(legalEntityId: UUID): LocalDate =
        LocalDate.now(clock.withZone(ZoneId.of(referenceData.requireLegalEntity(legalEntityId).timeZone)))

    override fun run(command: InvoiceRun): List<InvoiceView> {
        actor(Role.BACK_OFFICE, Role.ACCOUNTANT)
        val open = items.find(
            SettlementItemFilter(command.legalEntityId, command.counterpartyId, setOf(SettlementItemStatus.OPEN), limit = MAX_ITEMS),
            forUpdate = true,
        ).filter { it.deliveredOn <= command.upTo }
        val invoiceDate = command.invoiceDate ?: today(command.legalEntityId)
        return open.groupBy { Group(it.counterpartyId, it.currency, it.direction, it.vatTreatment!!) }
            .mapNotNull { (group, groupItems) ->
                val kind = when (group.direction) {
                    SettlementDirection.RECEIVABLE -> InvoiceKind.SALES
                    // Purchases are invoiced by the supplier, unless we agreed to self-bill them.
                    SettlementDirection.PAYABLE -> if (referenceData.requireCounterparty(group.counterpartyId).selfBilling) InvoiceKind.SELF_BILLED else null
                } ?: return@mapNotNull null
                draft(kind, command.legalEntityId, group.counterpartyId, group.currency, invoiceDate, groupItems, null, emptyList())
            }
    }

    override fun registerPurchaseInvoice(command: RegisterPurchaseInvoice): InvoiceView {
        actor(Role.BACK_OFFICE, Role.ACCOUNTANT)
        val reference = command.supplierInvoiceRef.trim().ifBlank { throw ValidationException("The supplier's invoice number is required") }
        val duplicate = repository.find(InvoiceFilter(command.legalEntityId, command.counterpartyId, setOf(InvoiceKind.PURCHASE)))
            .any { it.counterpartyInvoiceRef == reference && it.status != InvoiceStatus.CANCELLED }
        if (duplicate) throw ConflictException("Supplier invoice $reference is already registered")
        val currency = command.currency.uppercase()
        val candidates = if (command.itemIds != null) {
            command.itemIds.map { items.find(it, forUpdate = true) ?: throw NotFoundException("SettlementItem", it) }
        } else {
            items.find(
                SettlementItemFilter(command.legalEntityId, command.counterpartyId, setOf(SettlementItemStatus.OPEN), limit = MAX_ITEMS),
                forUpdate = true,
            )
                .filter { it.direction == SettlementDirection.PAYABLE && it.currency == currency }
        }
        if (candidates.isEmpty()) throw ValidationException("No open purchase deliveries from this supplier in $currency")
        val fits = { item: SettlementItemView ->
            item.status == SettlementItemStatus.OPEN && item.direction == SettlementDirection.PAYABLE && item.currency == currency
        }
        candidates.filterNot { fits(it) && it.counterpartyId == command.counterpartyId && it.legalEntityId == command.legalEntityId }.forEach {
            throw ValidationException("Item ${it.id} is not an open purchase from this supplier in $currency")
        }
        val expectedNet = candidates.sumOf { it.netAmount!! }
        val expectedVat = candidates.sumOf { it.vatAmount!! }
        val netDifference = Amounts.money(command.netAmount) - expectedNet
        val vatDifference = Amounts.money(command.vatAmount) - expectedVat
        if (netDifference.abs() > PURCHASE_TOLERANCE || vatDifference.abs() > PURCHASE_TOLERANCE) {
            throw ValidationException(
                "Supplier invoice $reference ($currency ${command.netAmount} + VAT ${command.vatAmount}) does not match the deliveries " +
                    "($expectedNet + VAT $expectedVat) within $PURCHASE_TOLERANCE",
            )
        }
        // Rounding differences within the tolerance are booked as an adjustment line, so totals equal the supplier's.
        val adjustment = if (netDifference.signum() != 0 || vatDifference.signum() != 0) {
            val treatment = candidates.first().vatTreatment!!
            listOf(
                InvoiceLineView(
                    0,
                    InvoiceLineType.ADJUSTMENT,
                    null,
                    "Rounding difference",
                    null,
                    null,
                    null,
                    netDifference,
                    null,
                    treatment,
                    BigDecimal.ZERO,
                    vatDifference,
                ),
            )
        } else {
            emptyList()
        }
        return draft(InvoiceKind.PURCHASE, command.legalEntityId, command.counterpartyId, currency, command.invoiceDate, candidates, reference, adjustment)
    }

    override fun submit(invoiceId: UUID): InvoiceView {
        val actor = actor(Role.BACK_OFFICE, Role.ACCOUNTANT)
        val invoice = locked(invoiceId)
        if (invoice.status != InvoiceStatus.DRAFT) throw IllegalStateTransitionException("Invoice $invoiceId", invoice.status.name, "PENDING_APPROVAL")
        val submitted = invoice.copy(status = InvoiceStatus.PENDING_APPROVAL, submittedBy = actor, version = invoice.version + 1)
        repository.update(submitted)
        events.publishEvent(InvoiceSubmitted(submitted))
        return submitted
    }

    /** Second person's approval: numbers, freezes and issues the invoice in this transaction. */
    fun approve(invoiceId: UUID, by: String): InvoiceView {
        val invoice = locked(invoiceId)
        if (invoice.status != InvoiceStatus.PENDING_APPROVAL) throw IllegalStateTransitionException("Invoice $invoiceId", invoice.status.name, "ISSUED")
        SecondPerson.require(by, invoice.submittedBy) { "An invoice cannot be approved by the person who submitted it" }
        val entity = referenceData.requireLegalEntity(invoice.legalEntityId)
        val counterparty = referenceData.requireCounterparty(invoice.counterpartyId)
        val us = snapshot(PartyType.LEGAL_ENTITY, entity.id, entity.name, entity.country, entity.vatNumber, entity.lei, invoice.taxPointDate)
        val them =
            snapshot(
                PartyType.COUNTERPARTY,
                counterparty.id,
                counterparty.name,
                counterparty.country,
                counterparty.vatNumber,
                counterparty.lei,
                invoice.taxPointDate,
            )
        val receivable = invoice.direction == SettlementDirection.RECEIVABLE
        val bank = if (receivable && invoice.grossTotal.signum() > 0) {
            parties.bankAccountFor(PartyType.LEGAL_ENTITY, entity.id, invoice.currency)
                ?: throw ValidationException("Legal entity ${entity.code} has no active ${invoice.currency} bank account to be paid into")
        } else {
            parties.bankAccountFor(PartyType.COUNTERPARTY, counterparty.id, invoice.currency)
                ?: if (receivable) parties.bankAccountFor(PartyType.LEGAL_ENTITY, entity.id, invoice.currency) else null
        }
        val number = "${invoice.kind.series}-${invoice.invoiceDate.year}-%06d".format(numbers.next(entity.id, invoice.kind.series, invoice.invoiceDate.year))
        val dueDate = referenceData.addBusinessDays(CALENDAR, invoice.invoiceDate.plusDays(counterparty.paymentTermsDays.toLong() - 1), 1)
        val issued = invoice.copy(
            number = number,
            dueDate = dueDate,
            supplier = if (receivable) us else them,
            customer = if (receivable) them else us,
            bankAccount = bank?.let { BankSnapshot(it.accountName, it.iban, it.bic) },
            notes = invoice.notes + notes(invoice),
        )
        val document = if (invoice.kind == InvoiceKind.PURCHASE || invoice.kind == InvoiceKind.PURCHASE_CREDIT_NOTE) {
            null
        } else {
            documents.renderPdf(
                RenderPdf(
                    kind = "INVOICE",
                    ownerType = "INVOICE",
                    ownerRef = invoice.id,
                    legalEntityId = invoice.legalEntityId,
                    title = "${invoice.kind.title()} $number",
                    fileName = "$number.pdf",
                    template = "invoice",
                    model = pdfModel(issued),
                ),
            )
        }
        val final = issued.copy(
            status = InvoiceStatus.ISSUED,
            approvedBy = by,
            issuedAt = clock.instant(),
            documentId = document?.id,
            version = invoice.version + 1,
        )
        repository.update(final)
        events.publishEvent(InvoiceIssued(final))
        if (final.originalInvoiceId != null) reverse(final, by)
        return repository.find(final.id)!!
    }

    /** An issued credit note settles the original and makes its deliveries invoiceable again. */
    private fun reverse(creditNote: InvoiceView, by: String) {
        val original = locked(creditNote.originalInvoiceId!!)
        repository.update(original.copy(credited = true))
        original.lines.mapNotNull { it.settlementItemId }.forEach { id ->
            val item = items.find(id, forUpdate = true)!!
            if (item.invoiceId == original.id) items.update(item.copy(status = SettlementItemStatus.OPEN, invoiceId = null, version = item.version + 1))
        }
        settlement.applyCreditNote(creditNote, by)
    }

    /** The approver sends it back: the invoice is a draft again. */
    fun reject(invoiceId: UUID): InvoiceView {
        val invoice = locked(invoiceId)
        if (invoice.status != InvoiceStatus.PENDING_APPROVAL) throw IllegalStateTransitionException("Invoice $invoiceId", invoice.status.name, "DRAFT")
        val draft = invoice.copy(status = InvoiceStatus.DRAFT, submittedBy = null, version = invoice.version + 1)
        repository.update(draft)
        return draft
    }

    override fun discard(invoiceId: UUID, reason: String): InvoiceView {
        actor(Role.BACK_OFFICE, Role.ACCOUNTANT)
        val invoice = locked(invoiceId)
        if (invoice.status != InvoiceStatus.DRAFT) throw IllegalStateTransitionException("Invoice $invoiceId", invoice.status.name, "CANCELLED")
        invoice.lines.mapNotNull { it.settlementItemId }.forEach { id ->
            val item = items.find(id, forUpdate = true)!!
            items.update(item.copy(status = SettlementItemStatus.OPEN, invoiceId = null, version = item.version + 1))
        }
        val discarded = invoice.copy(status = InvoiceStatus.CANCELLED, version = invoice.version + 1)
        repository.update(discarded)
        events.publishEvent(InvoiceDiscarded(discarded, reason))
        return discarded
    }

    override fun creditNote(invoiceId: UUID, reason: String): InvoiceView {
        actor(Role.BACK_OFFICE, Role.ACCOUNTANT)
        if (reason.isBlank()) throw ValidationException("A credit note needs a reason")
        val original = locked(invoiceId)
        if (original.status != InvoiceStatus.ISSUED || original.originalInvoiceId != null) {
            throw IllegalStateTransitionException("Invoice ${original.number}", original.status.name, "credited")
        }
        if (original.credited) throw ConflictException("Invoice ${original.number} is already credited")
        val pending = repository.find(
            InvoiceFilter(original.legalEntityId, original.counterpartyId, statuses = setOf(InvoiceStatus.DRAFT, InvoiceStatus.PENDING_APPROVAL)),
        )
            .any { it.originalInvoiceId == original.id }
        if (pending) throw ConflictException("A credit note for ${original.number} is already being prepared")
        val kind = when (original.kind) {
            InvoiceKind.SALES -> InvoiceKind.CREDIT_NOTE
            InvoiceKind.SELF_BILLED -> InvoiceKind.SELF_BILLED
            InvoiceKind.PURCHASE -> InvoiceKind.PURCHASE_CREDIT_NOTE
            else -> throw ValidationException("${original.kind} cannot be credited")
        }
        val lines = original.lines.map {
            it.copy(
                netAmount = it.netAmount.negate(),
                vatAmount = it.vatAmount.negate(),
                selfAssessedVat = it.selfAssessedVat.negate(),
                quantity = it.quantity?.negate(),
            )
        }
        val creditNote = original.copy(
            id = Ids.newId(),
            kind = kind,
            status = InvoiceStatus.DRAFT,
            number = null,
            counterpartyInvoiceRef = null,
            invoiceDate = today(original.legalEntityId),
            dueDate = null,
            netTotal = original.netTotal.negate(),
            vatTotal = original.vatTotal.negate(),
            grossTotal = original.grossTotal.negate(),
            supplier = null,
            customer = null,
            bankAccount = null,
            notes = listOf("Credit note for invoice ${original.number}: ${reason.trim()}"),
            originalInvoiceId = original.id,
            documentId = null,
            lines = lines,
            createdBy = actorProvider.current().userId,
            submittedBy = null,
            approvedBy = null,
            issuedAt = null,
            version = 1,
            paidAmount = BigDecimal.ZERO,
            paymentStatus = PaymentStatus.UNPAID,
            credited = false,
        )
        repository.insert(creditNote)
        return creditNote
    }

    @Transactional(readOnly = true)
    override fun invoice(id: UUID): InvoiceView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun invoices(filter: InvoiceFilter): List<InvoiceView> = repository.find(filter)

    @Transactional(readOnly = true)
    override fun invoicePage(filter: InvoiceFilter, cursor: String?): InvoicePage {
        if (filter.limit !in 1..MAX_PAGE) throw ValidationException("limit must be 1..$MAX_PAGE")
        val after = cursor?.let { Cursor.decode(it, 2) { (date, id) -> LocalDate.parse(date) to UUID.fromString(id) } }
        val (items, next) = repository.findPage(filter, after).page(filter.limit) { Cursor.encode(it.invoiceDate, it.id) }
        return InvoicePage(items, next)
    }

    @Transactional(readOnly = true)
    override fun export(filter: InvoiceFilter): String = Csv.writeForSpreadsheet(
        listOf("number", "kind", "status", "invoice_date", "tax_point_date", "due_date", "counterparty", "supplier_ref", "currency", "net", "vat", "gross"),
        repository.find(filter).map {
            listOf(
                it.number,
                it.kind.name,
                it.status.name,
                it.invoiceDate,
                it.taxPointDate,
                it.dueDate,
                referenceData.counterparty(it.counterpartyId)?.code,
                it.counterpartyInvoiceRef,
                it.currency,
                it.netTotal.toPlainString(),
                it.vatTotal.toPlainString(),
                it.grossTotal.toPlainString(),
            )
        },
    )

    // ----------------------------------------------------------------------------------------------------------

    private data class Group(val counterpartyId: UUID, val currency: String, val direction: SettlementDirection, val treatment: VatTreatment)

    @Suppress("LongParameterList") // the invoice header fields, written once
    private fun draft(
        kind: InvoiceKind,
        legalEntityId: UUID,
        counterpartyId: UUID,
        currency: String,
        invoiceDate: LocalDate,
        source: List<SettlementItemView>,
        supplierRef: String?,
        extraLines: List<InvoiceLineView>,
    ): InvoiceView {
        val id = Ids.newId()
        val ourCountry = referenceData.requireLegalEntity(legalEntityId).country
        val lines = source.mapIndexed { i, item ->
            InvoiceLineView(
                lineNo = i + 1,
                lineType = InvoiceLineType.COMMODITY,
                settlementItemId = item.id,
                description = item.description,
                quantity = item.quantity,
                unit = item.unit,
                unitPrice = item.unitPrice,
                netAmount = item.netAmount!!,
                vatRuleCode = item.vatRuleCode,
                vatTreatment = item.vatTreatment!!,
                vatRate = item.vatRate!!,
                vatAmount = item.vatAmount!!,
                selfAssessedVat = selfAssessed(kind, item, ourCountry),
            )
        } + extraLines.mapIndexed { i, l -> l.copy(lineNo = source.size + i + 1) }
        val net = lines.sumOf { it.netAmount }
        val vat = lines.sumOf { it.vatAmount }
        val invoice = InvoiceView(
            id = id,
            legalEntityId = legalEntityId,
            counterpartyId = counterpartyId,
            kind = kind,
            status = InvoiceStatus.DRAFT,
            number = null,
            counterpartyInvoiceRef = supplierRef,
            currency = currency,
            invoiceDate = invoiceDate,
            taxPointDate = source.maxOf { it.deliveredOn },
            dueDate = null,
            netTotal = net,
            vatTotal = vat,
            grossTotal = net + vat,
            supplier = null,
            customer = null,
            bankAccount = null,
            notes = emptyList(),
            originalInvoiceId = null,
            documentId = null,
            lines = lines,
            createdBy = actorProvider.current().userId,
            submittedBy = null,
            approvedBy = null,
            issuedAt = null,
            version = 1,
        )
        repository.insert(invoice)
        source.forEach { items.update(it.copy(status = SettlementItemStatus.INVOICED, invoiceId = id, version = it.version + 1)) }
        return invoice
    }

    /** We are the customer on payables: a reverse-charged line makes us account for VAT at our standard rate. */
    private fun selfAssessed(kind: InvoiceKind, item: SettlementItemView, ourCountry: String): BigDecimal {
        if (kind.direction != SettlementDirection.PAYABLE || item.vatTreatment?.reverseCharge != true) return BigDecimal.ZERO
        val rate = standardRates.rate(ourCountry, item.deliveredOn)
            ?: throw ValidationException("No standard VAT rate for $ourCountry on ${item.deliveredOn}; reverse-charged purchases need it")
        return Amounts.vat(item.netAmount!!, rate)
    }

    private fun snapshot(type: PartyType, id: UUID, name: String, country: String, vatNumber: String?, lei: String?, date: LocalDate) = PartySnapshot(
        name = name,
        address = parties.billingAddress(type, id)?.text,
        country = country,
        vatNumber = parties.vatRegistration(type, id, country, date)?.vatNumber ?: vatNumber,
        lei = lei,
    )

    /** The wording the VAT Directive requires on the invoice (Art. 226): reverse charge, exemption, self-billing. */
    private fun notes(invoice: InvoiceView): List<String> {
        val rules = vatRules.all()
        val legal = invoice.lines.filter { it.vatTreatment != VatTreatment.STANDARD }.mapNotNull { line ->
            val text = rules.firstOrNull { it.code == line.vatRuleCode }?.legalText
            when {
                line.vatTreatment.reverseCharge -> "Reverse charge" + (text?.let { ": $it" } ?: "")
                else -> text
            }
        }
        val selfBilling = if (invoice.kind == InvoiceKind.SELF_BILLED) listOf("Self-billing") else emptyList()
        return (selfBilling + legal).distinct()
    }

    private fun pdfModel(invoice: InvoiceView): Map<String, Any?> = mapOf(
        "title" to "${invoice.kind.title()} ${invoice.number}",
        "number" to invoice.number,
        "invoiceDate" to invoice.invoiceDate.toString(),
        "taxPointDate" to invoice.taxPointDate.toString(),
        "dueDate" to invoice.dueDate.toString(),
        "supplier" to party(invoice.supplier!!),
        "customer" to party(invoice.customer!!),
        "currency" to invoice.currency,
        "lines" to invoice.lines.map {
            mapOf(
                "lineNo" to it.lineNo,
                "description" to it.description,
                "quantity" to (it.quantity?.stripTrailingZeros()?.toPlainString() ?: ""),
                "unit" to (it.unit ?: ""),
                "unitPrice" to (it.unitPrice?.stripTrailingZeros()?.toPlainString() ?: ""),
                "net" to it.netAmount.toPlainString(),
                "vatRate" to it.vatRate.stripTrailingZeros().toPlainString(),
                "vat" to it.vatAmount.toPlainString(),
            )
        },
        "net" to invoice.netTotal.toPlainString(),
        "vat" to invoice.vatTotal.toPlainString(),
        "gross" to invoice.grossTotal.toPlainString(),
        "notes" to invoice.notes,
        "bank" to invoice.bankAccount?.let { "${it.accountName}, IBAN ${it.iban}, BIC ${it.bic}" },
        "supplierRef" to invoice.counterpartyInvoiceRef,
    )

    private fun party(p: PartySnapshot): Map<String, Any?> =
        mapOf("name" to p.name, "address" to (p.address ?: p.country), "vatNumber" to (p.vatNumber ?: "—"), "lei" to (p.lei ?: "—"))

    private fun InvoiceKind.title(): String = when (this) {
        InvoiceKind.SALES -> "Invoice"
        InvoiceKind.SELF_BILLED -> "Self-billed invoice"
        InvoiceKind.PURCHASE -> "Purchase invoice"
        InvoiceKind.CREDIT_NOTE -> "Credit note"
        InvoiceKind.PURCHASE_CREDIT_NOTE -> "Purchase credit note"
    }

    private fun locked(id: UUID): InvoiceView = repository.find(id, forUpdate = true) ?: throw NotFoundException("Invoice", id)

    private fun actor(vararg roles: Role): String {
        val actor = actorProvider.current()
        actor.requireRole(*roles)
        return actor.userId
    }

    private companion object {
        const val CALENDAR = "TARGET2"
        const val MAX_ITEMS = 5_000
        val PURCHASE_TOLERANCE = BigDecimal("0.05")
    }
}

private const val MAX_PAGE = 500
