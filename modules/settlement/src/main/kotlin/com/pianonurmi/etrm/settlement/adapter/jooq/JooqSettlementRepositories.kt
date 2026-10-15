package com.pianonurmi.etrm.settlement.adapter.jooq

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.Invoice.INVOICE
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.InvoiceLine.INVOICE_LINE
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.NumberSeries.NUMBER_SERIES
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.PriceFixing.PRICE_FIXING
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.SettlementItem.SETTLEMENT_ITEM
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.VatRule.VAT_RULE
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.VatStandardRate.VAT_STANDARD_RATE
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.records.InvoiceRecord
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.records.SettlementItemRecord
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.records.VatRuleRecord
import com.pianonurmi.etrm.settlement.api.BankSnapshot
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceLineType
import com.pianonurmi.etrm.settlement.api.InvoiceLineView
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.PartySnapshot
import com.pianonurmi.etrm.settlement.api.PaymentStatus
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.api.VatRuleStatus
import com.pianonurmi.etrm.settlement.api.VatRuleView
import com.pianonurmi.etrm.settlement.api.VatScope
import com.pianonurmi.etrm.settlement.api.VatTreatment
import com.pianonurmi.etrm.settlement.domain.InvoiceRepository
import com.pianonurmi.etrm.settlement.domain.NumberSeriesRepository
import com.pianonurmi.etrm.settlement.domain.PriceFixingView
import com.pianonurmi.etrm.settlement.domain.SettlementItemRepository
import com.pianonurmi.etrm.settlement.domain.StandardRateRepository
import com.pianonurmi.etrm.settlement.domain.VatRuleRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun json(value: Any?): JSONB? = value?.let { JSONB.jsonb(Json.mapper.writeValueAsString(it)) }

@Repository
class JooqVatRuleRepository(private val dsl: DSLContext) : VatRuleRepository {
    override fun all(): List<VatRuleView> = dsl.selectFrom(VAT_RULE).orderBy(VAT_RULE.CODE, VAT_RULE.VERSION).fetch(::toView)

    override fun find(id: UUID, forUpdate: Boolean): VatRuleView? {
        val query = dsl.selectFrom(VAT_RULE).where(VAT_RULE.ID.eq(id))
        return (if (forUpdate) query.forUpdate().fetchOne() else query.fetchOne())?.let(::toView)
    }

    override fun nextVersion(code: String): Int =
        (dsl.select(DSL.max(VAT_RULE.VERSION)).from(VAT_RULE).where(VAT_RULE.CODE.eq(code)).fetchOne(0, Int::class.java) ?: 0) + 1

    override fun insert(rule: VatRuleView) {
        dsl.insertInto(VAT_RULE)
            .set(VAT_RULE.ID, rule.id)
            .set(VAT_RULE.CODE, rule.code)
            .set(VAT_RULE.VERSION, rule.version)
            .set(VAT_RULE.DESCRIPTION, rule.description)
            .set(VAT_RULE.PRODUCT_FAMILY, rule.productFamily)
            .set(VAT_RULE.SUPPLIER_COUNTRY, rule.supplierCountry)
            .set(VAT_RULE.SCOPE, rule.scope.name)
            .set(VAT_RULE.TREATMENT, rule.treatment.name)
            .set(VAT_RULE.RATE, rule.rate)
            .set(VAT_RULE.VALID_FROM, rule.validFrom)
            .set(VAT_RULE.VALID_TO, rule.validTo)
            .set(VAT_RULE.LEGAL_TEXT, rule.legalText)
            .set(VAT_RULE.REQUIRES_SIGN_OFF, rule.requiresSignOff)
            .set(VAT_RULE.STATUS, rule.status.name)
            .set(VAT_RULE.CREATED_BY, rule.createdBy)
            .execute()
    }

    override fun update(rule: VatRuleView) {
        dsl.update(VAT_RULE)
            .set(VAT_RULE.STATUS, rule.status.name)
            .set(VAT_RULE.VALID_TO, rule.validTo)
            .set(VAT_RULE.APPROVED_BY, rule.approvedBy)
            .set(VAT_RULE.APPROVED_AT, if (rule.approvedBy == null) DSL.inline(null, VAT_RULE.APPROVED_AT) else DSL.currentInstant())
            .where(VAT_RULE.ID.eq(rule.id))
            .execute()
    }

    private fun toView(r: VatRuleRecord) = VatRuleView(
        id = r.id,
        code = r.code,
        version = r.version,
        description = r.description,
        productFamily = r.productFamily,
        supplierCountry = r.supplierCountry,
        scope = VatScope.valueOf(r.scope),
        treatment = VatTreatment.valueOf(r.treatment),
        rate = r.rate,
        validFrom = r.validFrom,
        validTo = r.validTo,
        legalText = r.legalText,
        requiresSignOff = r.requiresSignOff,
        status = VatRuleStatus.valueOf(r.status),
        createdBy = r.createdBy,
        approvedBy = r.approvedBy,
    )
}

@Repository
class JooqSettlementItemRepository(private val dsl: DSLContext) : SettlementItemRepository {
    override fun insert(item: SettlementItemView): Boolean = dsl.insertInto(SETTLEMENT_ITEM)
        .set(SETTLEMENT_ITEM.ID, item.id)
        .set(SETTLEMENT_ITEM.DELIVERY_ID, item.deliveryId)
        .set(SETTLEMENT_ITEM.TRADE_ID, item.tradeId)
        .set(SETTLEMENT_ITEM.TRADE_NUMBER, item.tradeNumber)
        .set(SETTLEMENT_ITEM.SCHEDULE_ID, item.scheduleId)
        .set(SETTLEMENT_ITEM.LEGAL_ENTITY_ID, item.legalEntityId)
        .set(SETTLEMENT_ITEM.COUNTERPARTY_ID, item.counterpartyId)
        .set(SETTLEMENT_ITEM.DIRECTION, item.direction.name)
        .set(SETTLEMENT_ITEM.PRODUCT_FAMILY, item.productFamily.name)
        .set(SETTLEMENT_ITEM.DESCRIPTION, item.description)
        .set(SETTLEMENT_ITEM.QUANTITY, item.quantity)
        .set(SETTLEMENT_ITEM.UNIT, item.unit)
        .set(SETTLEMENT_ITEM.CURRENCY, item.currency)
        .set(SETTLEMENT_ITEM.DELIVERED_ON, item.deliveredOn)
        .set(SETTLEMENT_ITEM.REFERENCE, item.reference)
        .set(values(item))
        .onConflict(SETTLEMENT_ITEM.DELIVERY_ID)
        .doNothing()
        .execute() == 1

    override fun update(item: SettlementItemView) {
        dsl.update(SETTLEMENT_ITEM)
            .set(values(item))
            .set(SETTLEMENT_ITEM.VERSION, item.version)
            .set(SETTLEMENT_ITEM.UPDATED_AT, DSL.currentInstant())
            .where(SETTLEMENT_ITEM.ID.eq(item.id))
            .execute()
    }

    override fun find(id: UUID, forUpdate: Boolean): SettlementItemView? {
        val query = dsl.selectFrom(SETTLEMENT_ITEM).where(SETTLEMENT_ITEM.ID.eq(id))
        return (if (forUpdate) query.forUpdate().fetchOne() else query.fetchOne())?.let(::toView)
    }

    override fun find(filter: SettlementItemFilter, forUpdate: Boolean): List<SettlementItemView> {
        val query = dsl.selectFrom(SETTLEMENT_ITEM)
            .where(
                listOfNotNull(
                    filter.legalEntityId?.let(SETTLEMENT_ITEM.LEGAL_ENTITY_ID::eq),
                    filter.counterpartyId?.let(SETTLEMENT_ITEM.COUNTERPARTY_ID::eq),
                    filter.tradeId?.let(SETTLEMENT_ITEM.TRADE_ID::eq),
                    filter.statuses?.let { s -> SETTLEMENT_ITEM.STATUS.`in`(s.map { it.name }) },
                ),
            )
            .orderBy(SETTLEMENT_ITEM.DELIVERED_ON, SETTLEMENT_ITEM.ID)
            .limit(filter.limit)
        return (if (forUpdate) query.forUpdate().fetch() else query.fetch()).map(::toView)
    }

    override fun forSchedule(scheduleId: UUID): List<SettlementItemView> =
        dsl.selectFrom(SETTLEMENT_ITEM).where(SETTLEMENT_ITEM.SCHEDULE_ID.eq(scheduleId)).orderBy(SETTLEMENT_ITEM.CREATED_AT).fetch(::toView)

    override fun priceFixing(scheduleId: UUID): PriceFixingView? = dsl.selectFrom(PRICE_FIXING).where(PRICE_FIXING.SCHEDULE_ID.eq(scheduleId))
        .fetchOne()?.let { PriceFixingView(it.scheduleId, it.tradeId, it.price, it.currency, it.note, it.fixedBy) }

    override fun upsertPriceFixing(fixing: PriceFixingView) {
        dsl.insertInto(PRICE_FIXING)
            .set(PRICE_FIXING.SCHEDULE_ID, fixing.scheduleId)
            .set(PRICE_FIXING.TRADE_ID, fixing.tradeId)
            .set(PRICE_FIXING.PRICE, fixing.price)
            .set(PRICE_FIXING.CURRENCY, fixing.currency)
            .set(PRICE_FIXING.NOTE, fixing.note)
            .set(PRICE_FIXING.FIXED_BY, fixing.fixedBy)
            .onConflict(PRICE_FIXING.SCHEDULE_ID)
            .doUpdate()
            .set(PRICE_FIXING.PRICE, fixing.price)
            .set(PRICE_FIXING.NOTE, fixing.note)
            .set(PRICE_FIXING.FIXED_BY, fixing.fixedBy)
            .set(PRICE_FIXING.FIXED_AT, DSL.currentInstant())
            .execute()
    }

    private fun values(item: SettlementItemView): SettlementItemRecord = SettlementItemRecord().apply {
        unitPrice = item.unitPrice
        netAmount = item.netAmount
        vatRuleCode = item.vatRuleCode
        vatRuleVersion = item.vatRuleVersion
        vatTreatment = item.vatTreatment?.name
        vatRate = item.vatRate
        vatAmount = item.vatAmount
        status = item.status.name
        problem = item.problem
        invoiceId = item.invoiceId
    }

    private fun toView(r: SettlementItemRecord) = SettlementItemView(
        id = r.id,
        deliveryId = r.deliveryId,
        tradeId = r.tradeId,
        tradeNumber = r.tradeNumber,
        scheduleId = r.scheduleId,
        legalEntityId = r.legalEntityId,
        counterpartyId = r.counterpartyId,
        direction = SettlementDirection.valueOf(r.direction),
        productFamily = ProductFamily.valueOf(r.productFamily),
        description = r.description,
        quantity = r.quantity,
        unit = r.unit,
        unitPrice = r.unitPrice,
        currency = r.currency,
        netAmount = r.netAmount,
        deliveredOn = r.deliveredOn,
        vatRuleCode = r.vatRuleCode,
        vatRuleVersion = r.vatRuleVersion,
        vatTreatment = r.vatTreatment?.let(VatTreatment::valueOf),
        vatRate = r.vatRate,
        vatAmount = r.vatAmount,
        status = SettlementItemStatus.valueOf(r.status),
        problem = r.problem,
        invoiceId = r.invoiceId,
        reference = r.reference,
        version = r.version,
    )
}

@Repository
class JooqStandardRateRepository(private val dsl: DSLContext) : StandardRateRepository {
    override fun rate(country: String, date: java.time.LocalDate): java.math.BigDecimal? = dsl.select(VAT_STANDARD_RATE.RATE)
        .from(VAT_STANDARD_RATE)
        .where(
            VAT_STANDARD_RATE.COUNTRY.eq(country.uppercase()),
            VAT_STANDARD_RATE.VALID_FROM.le(date),
            VAT_STANDARD_RATE.VALID_TO.isNull.or(VAT_STANDARD_RATE.VALID_TO.gt(date)),
        )
        .fetchOne(VAT_STANDARD_RATE.RATE)
}

@Repository
class JooqNumberSeriesRepository(private val dsl: DSLContext) : NumberSeriesRepository {
    override fun next(legalEntityId: UUID, series: String, fiscalYear: Int): Long {
        dsl.insertInto(NUMBER_SERIES)
            .set(NUMBER_SERIES.LEGAL_ENTITY_ID, legalEntityId)
            .set(NUMBER_SERIES.SERIES, series)
            .set(NUMBER_SERIES.FISCAL_YEAR, fiscalYear)
            .onConflictDoNothing()
            .execute()
        // UPDATE ... RETURNING locks the row until commit, so concurrent issuers queue and numbers stay consecutive.
        return dsl.update(NUMBER_SERIES)
            .set(NUMBER_SERIES.NEXT_VALUE, NUMBER_SERIES.NEXT_VALUE.plus(1))
            .where(NUMBER_SERIES.LEGAL_ENTITY_ID.eq(legalEntityId), NUMBER_SERIES.SERIES.eq(series), NUMBER_SERIES.FISCAL_YEAR.eq(fiscalYear))
            .returning(NUMBER_SERIES.NEXT_VALUE)
            .fetchSingle()
            .nextValue - 1
    }
}

@Repository
class JooqInvoiceRepository(private val dsl: DSLContext) : InvoiceRepository {
    private val notesType = object : TypeReference<List<String>>() {}

    override fun insert(invoice: InvoiceView) {
        dsl.insertInto(INVOICE)
            .set(INVOICE.ID, invoice.id)
            .set(INVOICE.LEGAL_ENTITY_ID, invoice.legalEntityId)
            .set(INVOICE.COUNTERPARTY_ID, invoice.counterpartyId)
            .set(INVOICE.KIND, invoice.kind.name)
            .set(INVOICE.DIRECTION, invoice.direction.name)
            .set(INVOICE.CURRENCY, invoice.currency)
            .set(INVOICE.CREATED_BY, invoice.createdBy)
            .set(INVOICE.ORIGINAL_INVOICE_ID, invoice.originalInvoiceId)
            .set(header(invoice))
            .execute()
        writeLines(invoice)
    }

    override fun update(invoice: InvoiceView) {
        // Lines can only change while the invoice is a draft; write them before the header leaves DRAFT.
        val current = dsl.select(INVOICE.STATUS).from(INVOICE).where(INVOICE.ID.eq(invoice.id)).fetchOne(INVOICE.STATUS)
        if (current == InvoiceStatus.DRAFT.name) {
            dsl.deleteFrom(INVOICE_LINE).where(INVOICE_LINE.INVOICE_ID.eq(invoice.id)).execute()
            writeLines(invoice)
        }
        dsl.update(INVOICE).set(header(invoice)).where(INVOICE.ID.eq(invoice.id)).execute()
    }

    override fun find(id: UUID, forUpdate: Boolean): InvoiceView? {
        val query = dsl.selectFrom(INVOICE).where(INVOICE.ID.eq(id))
        return (if (forUpdate) query.forUpdate().fetchOne() else query.fetchOne())?.let(::toView)
    }

    override fun findPage(filter: InvoiceFilter, after: Pair<LocalDate, UUID>?): List<InvoiceView> = dsl.selectFrom(INVOICE)
        .where(invoiceConditions(filter))
        .and(after?.let { (date, id) -> DSL.row(INVOICE.INVOICE_DATE, INVOICE.ID).lt(date, id) } ?: DSL.noCondition())
        .orderBy(INVOICE.INVOICE_DATE.desc(), INVOICE.ID.desc())
        .limit(filter.limit + 1)
        .fetch(::toView)

    private fun invoiceConditions(filter: InvoiceFilter) = listOfNotNull(
        filter.legalEntityId?.let(INVOICE.LEGAL_ENTITY_ID::eq),
        filter.legalEntityIds?.let(INVOICE.LEGAL_ENTITY_ID::`in`),
        filter.counterpartyId?.let(INVOICE.COUNTERPARTY_ID::eq),
        filter.kinds?.let { k -> INVOICE.KIND.`in`(k.map { it.name }) },
        filter.statuses?.let { s -> INVOICE.STATUS.`in`(s.map { it.name }) },
        filter.from?.let(INVOICE.INVOICE_DATE::ge),
        filter.toExclusive?.let(INVOICE.INVOICE_DATE::lt),
    )

    override fun find(filter: InvoiceFilter): List<InvoiceView> = dsl.selectFrom(INVOICE)
        .where(invoiceConditions(filter))
        .orderBy(INVOICE.INVOICE_DATE, INVOICE.NUMBER, INVOICE.ID)
        .limit(filter.limit)
        .fetch(::toView)

    private fun header(i: InvoiceView): InvoiceRecord = InvoiceRecord().apply {
        status = i.status.name
        number = i.number
        counterpartyInvoiceRef = i.counterpartyInvoiceRef
        invoiceDate = i.invoiceDate
        taxPointDate = i.taxPointDate
        dueDate = i.dueDate
        netTotal = i.netTotal
        vatTotal = i.vatTotal
        grossTotal = i.grossTotal
        supplier = json(i.supplier)
        customer = json(i.customer)
        bankAccount = json(i.bankAccount)
        notes = json(i.notes)
        documentId = i.documentId
        submittedBy = i.submittedBy
        approvedBy = i.approvedBy
        issuedAt = i.issuedAt
        version = i.version
        paidAmount = i.paidAmount
        paymentStatus = i.paymentStatus.name
        credited = i.credited
    }

    private fun writeLines(invoice: InvoiceView) {
        invoice.lines.forEach { l ->
            dsl.insertInto(INVOICE_LINE)
                .set(INVOICE_LINE.ID, Ids.newId())
                .set(INVOICE_LINE.INVOICE_ID, invoice.id)
                .set(INVOICE_LINE.LINE_NO, l.lineNo)
                .set(INVOICE_LINE.LINE_TYPE, l.lineType.name)
                .set(INVOICE_LINE.SETTLEMENT_ITEM_ID, l.settlementItemId)
                .set(INVOICE_LINE.DESCRIPTION, l.description)
                .set(INVOICE_LINE.QUANTITY, l.quantity)
                .set(INVOICE_LINE.UNIT, l.unit)
                .set(INVOICE_LINE.UNIT_PRICE, l.unitPrice)
                .set(INVOICE_LINE.NET_AMOUNT, l.netAmount)
                .set(INVOICE_LINE.VAT_RULE_CODE, l.vatRuleCode)
                .set(INVOICE_LINE.VAT_TREATMENT, l.vatTreatment.name)
                .set(INVOICE_LINE.VAT_RATE, l.vatRate)
                .set(INVOICE_LINE.VAT_AMOUNT, l.vatAmount)
                .set(INVOICE_LINE.SELF_ASSESSED_VAT, l.selfAssessedVat)
                .execute()
        }
    }

    private fun toView(r: InvoiceRecord): InvoiceView {
        val lines = dsl.selectFrom(INVOICE_LINE).where(INVOICE_LINE.INVOICE_ID.eq(r.id)).orderBy(INVOICE_LINE.LINE_NO).fetch { l ->
            InvoiceLineView(
                lineNo = l.lineNo,
                lineType = InvoiceLineType.valueOf(l.lineType),
                settlementItemId = l.settlementItemId,
                description = l.description,
                quantity = l.quantity,
                unit = l.unit,
                unitPrice = l.unitPrice,
                netAmount = l.netAmount,
                vatRuleCode = l.vatRuleCode,
                vatTreatment = VatTreatment.valueOf(l.vatTreatment),
                vatRate = l.vatRate,
                vatAmount = l.vatAmount,
                selfAssessedVat = l.selfAssessedVat,
            )
        }
        return InvoiceView(
            id = r.id,
            legalEntityId = r.legalEntityId,
            counterpartyId = r.counterpartyId,
            kind = InvoiceKind.valueOf(r.kind),
            status = InvoiceStatus.valueOf(r.status),
            number = r.number,
            counterpartyInvoiceRef = r.counterpartyInvoiceRef,
            currency = r.currency,
            invoiceDate = r.invoiceDate,
            taxPointDate = r.taxPointDate,
            dueDate = r.dueDate,
            netTotal = r.netTotal,
            vatTotal = r.vatTotal,
            grossTotal = r.grossTotal,
            supplier = r.supplier?.let { Json.mapper.readValue(it.data(), PartySnapshot::class.java) },
            customer = r.customer?.let { Json.mapper.readValue(it.data(), PartySnapshot::class.java) },
            bankAccount = r.bankAccount?.let { Json.mapper.readValue(it.data(), BankSnapshot::class.java) },
            notes = Json.mapper.readValue(r.notes.data(), notesType),
            originalInvoiceId = r.originalInvoiceId,
            documentId = r.documentId,
            lines = lines,
            createdBy = r.createdBy,
            submittedBy = r.submittedBy,
            approvedBy = r.approvedBy,
            issuedAt = r.issuedAt,
            version = r.version,
            paidAmount = r.paidAmount,
            paymentStatus = PaymentStatus.valueOf(r.paymentStatus),
            credited = r.credited,
        )
    }
}
