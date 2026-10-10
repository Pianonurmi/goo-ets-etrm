package com.pianonurmi.etrm.settlement.adapter.jooq

import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.NettingStatement.NETTING_STATEMENT
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.NettingStatementInvoice.NETTING_STATEMENT_INVOICE
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.Payment.PAYMENT
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.PaymentAllocation.PAYMENT_ALLOCATION
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.records.NettingStatementRecord
import com.pianonurmi.etrm.settlement.adapter.jooq.generated.tables.records.PaymentRecord
import com.pianonurmi.etrm.settlement.api.AllocationKind
import com.pianonurmi.etrm.settlement.api.AllocationView
import com.pianonurmi.etrm.settlement.api.NettingStatementView
import com.pianonurmi.etrm.settlement.api.NettingStatus
import com.pianonurmi.etrm.settlement.api.PaymentDirection
import com.pianonurmi.etrm.settlement.api.PaymentSource
import com.pianonurmi.etrm.settlement.api.PaymentView
import com.pianonurmi.etrm.settlement.domain.AllocationRepository
import com.pianonurmi.etrm.settlement.domain.NettingRepository
import com.pianonurmi.etrm.settlement.domain.PaymentRepository
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Repository
class JooqPaymentRepository(private val dsl: DSLContext) : PaymentRepository {
    override fun insert(payment: PaymentView) {
        dsl.insertInto(PAYMENT)
            .set(PAYMENT.ID, payment.id)
            .set(PAYMENT.LEGAL_ENTITY_ID, payment.legalEntityId)
            .set(PAYMENT.COUNTERPARTY_ID, payment.counterpartyId)
            .set(PAYMENT.DIRECTION, payment.direction.name)
            .set(PAYMENT.AMOUNT, payment.amount)
            .set(PAYMENT.CURRENCY, payment.currency)
            .set(PAYMENT.VALUE_DATE, payment.valueDate)
            .set(PAYMENT.BANK_REFERENCE, payment.bankReference)
            .set(PAYMENT.REMITTANCE_INFO, payment.remittanceInfo)
            .set(PAYMENT.SOURCE, payment.source.name)
            .set(PAYMENT.UNAPPLIED_AMOUNT, payment.unappliedAmount)
            .set(PAYMENT.CREATED_BY, payment.createdBy)
            .execute()
    }

    override fun find(id: UUID, forUpdate: Boolean): PaymentView? {
        val query = dsl.selectFrom(PAYMENT).where(PAYMENT.ID.eq(id))
        return (if (forUpdate) query.forUpdate().fetchOne() else query.fetchOne())?.let(::toView)
    }

    override fun byReference(legalEntityId: UUID, bankReference: String): PaymentView? = dsl.selectFrom(PAYMENT)
        .where(PAYMENT.LEGAL_ENTITY_ID.eq(legalEntityId), PAYMENT.BANK_REFERENCE.eq(bankReference))
        .fetchOne()?.let(::toView)

    override fun updateUnapplied(id: UUID, unapplied: BigDecimal) {
        dsl.update(PAYMENT).set(PAYMENT.UNAPPLIED_AMOUNT, unapplied).where(PAYMENT.ID.eq(id)).execute()
    }

    override fun list(legalEntityId: UUID, counterpartyId: UUID?, unappliedOnly: Boolean): List<PaymentView> = dsl.selectFrom(PAYMENT)
        .where(
            listOfNotNull(
                PAYMENT.LEGAL_ENTITY_ID.eq(legalEntityId),
                counterpartyId?.let(PAYMENT.COUNTERPARTY_ID::eq),
                if (unappliedOnly) PAYMENT.UNAPPLIED_AMOUNT.gt(BigDecimal.ZERO) else null,
            ),
        )
        .orderBy(PAYMENT.VALUE_DATE, PAYMENT.BANK_REFERENCE)
        .fetch(::toView)

    private fun toView(r: PaymentRecord) = PaymentView(
        r.id,
        r.legalEntityId,
        r.counterpartyId,
        PaymentDirection.valueOf(r.direction),
        r.amount,
        r.currency,
        r.valueDate,
        r.bankReference,
        r.remittanceInfo,
        PaymentSource.valueOf(r.source),
        r.unappliedAmount,
        r.createdBy,
    )
}

@Repository
class JooqAllocationRepository(private val dsl: DSLContext) : AllocationRepository {
    override fun insert(allocation: AllocationView) {
        dsl.insertInto(PAYMENT_ALLOCATION)
            .set(PAYMENT_ALLOCATION.ID, allocation.id)
            .set(PAYMENT_ALLOCATION.INVOICE_ID, allocation.invoiceId)
            .set(PAYMENT_ALLOCATION.KIND, allocation.kind.name)
            .set(PAYMENT_ALLOCATION.AMOUNT, allocation.amount)
            .set(PAYMENT_ALLOCATION.PAYMENT_ID, allocation.paymentId)
            .set(PAYMENT_ALLOCATION.CREDIT_NOTE_ID, allocation.creditNoteId)
            .set(PAYMENT_ALLOCATION.NETTING_STATEMENT_ID, allocation.nettingStatementId)
            .set(PAYMENT_ALLOCATION.ALLOCATED_BY, allocation.allocatedBy)
            .set(PAYMENT_ALLOCATION.ALLOCATED_AT, allocation.allocatedAt)
            .execute()
    }

    override fun forInvoice(invoiceId: UUID): List<AllocationView> = dsl.selectFrom(PAYMENT_ALLOCATION)
        .where(PAYMENT_ALLOCATION.INVOICE_ID.eq(invoiceId))
        .orderBy(PAYMENT_ALLOCATION.ALLOCATED_AT)
        .fetch {
            AllocationView(
                it.id,
                it.invoiceId,
                AllocationKind.valueOf(it.kind),
                it.amount,
                it.paymentId,
                it.creditNoteId,
                it.nettingStatementId,
                it.allocatedBy,
                it.allocatedAt,
            )
        }
}

@Repository
class JooqNettingRepository(private val dsl: DSLContext) : NettingRepository {
    override fun insert(statement: NettingStatementView) {
        dsl.insertInto(NETTING_STATEMENT)
            .set(NETTING_STATEMENT.ID, statement.id)
            .set(NETTING_STATEMENT.LEGAL_ENTITY_ID, statement.legalEntityId)
            .set(NETTING_STATEMENT.COUNTERPARTY_ID, statement.counterpartyId)
            .set(NETTING_STATEMENT.CURRENCY, statement.currency)
            .set(NETTING_STATEMENT.VALUE_DATE, statement.valueDate)
            .set(NETTING_STATEMENT.STATUS, statement.status.name)
            .set(NETTING_STATEMENT.RECEIVABLE_TOTAL, statement.receivableTotal)
            .set(NETTING_STATEMENT.PAYABLE_TOTAL, statement.payableTotal)
            .set(NETTING_STATEMENT.NET_AMOUNT, statement.netAmount)
            .set(NETTING_STATEMENT.DOCUMENT_ID, statement.documentId)
            .set(NETTING_STATEMENT.CREATED_BY, statement.createdBy)
            .execute()
        statement.invoices.forEach { (invoiceId, amount) ->
            dsl.insertInto(NETTING_STATEMENT_INVOICE)
                .set(NETTING_STATEMENT_INVOICE.STATEMENT_ID, statement.id)
                .set(NETTING_STATEMENT_INVOICE.INVOICE_ID, invoiceId)
                .set(NETTING_STATEMENT_INVOICE.AMOUNT, amount)
                .execute()
        }
    }

    override fun update(statement: NettingStatementView) {
        dsl.update(NETTING_STATEMENT)
            .set(NETTING_STATEMENT.STATUS, statement.status.name)
            .set(NETTING_STATEMENT.COUNTERPARTY_REF, statement.counterpartyRef)
            .set(NETTING_STATEMENT.SETTLEMENT_PAYMENT_ID, statement.settlementPaymentId)
            .set(NETTING_STATEMENT.DOCUMENT_ID, statement.documentId)
            .set(NETTING_STATEMENT.AGREED_BY, statement.agreedBy)
            .set(NETTING_STATEMENT.AGREED_AT, if (statement.agreedBy == null) null else Instant.now())
            .set(NETTING_STATEMENT.SETTLED_AT, if (statement.status == NettingStatus.SETTLED) Instant.now() else null)
            .set(NETTING_STATEMENT.VERSION, statement.version)
            .where(NETTING_STATEMENT.ID.eq(statement.id))
            .execute()
    }

    override fun find(id: UUID, forUpdate: Boolean): NettingStatementView? {
        val query = dsl.selectFrom(NETTING_STATEMENT).where(NETTING_STATEMENT.ID.eq(id))
        return (if (forUpdate) query.forUpdate().fetchOne() else query.fetchOne())?.let(::toView)
    }

    override fun list(legalEntityId: UUID, counterpartyId: UUID?): List<NettingStatementView> = dsl.selectFrom(NETTING_STATEMENT)
        .where(listOfNotNull(NETTING_STATEMENT.LEGAL_ENTITY_ID.eq(legalEntityId), counterpartyId?.let(NETTING_STATEMENT.COUNTERPARTY_ID::eq)))
        .orderBy(NETTING_STATEMENT.VALUE_DATE)
        .fetch(::toView)

    override fun invoicesInOpenStatements(legalEntityId: UUID, counterpartyId: UUID): Set<UUID> = dsl.select(NETTING_STATEMENT_INVOICE.INVOICE_ID)
        .from(NETTING_STATEMENT_INVOICE)
        .join(NETTING_STATEMENT).on(NETTING_STATEMENT.ID.eq(NETTING_STATEMENT_INVOICE.STATEMENT_ID))
        .where(
            NETTING_STATEMENT.LEGAL_ENTITY_ID.eq(legalEntityId),
            NETTING_STATEMENT.COUNTERPARTY_ID.eq(counterpartyId),
            NETTING_STATEMENT.STATUS.`in`(NettingStatus.DRAFT.name, NettingStatus.AGREED.name),
        )
        .fetchSet(NETTING_STATEMENT_INVOICE.INVOICE_ID)

    private fun toView(r: NettingStatementRecord) = NettingStatementView(
        id = r.id,
        legalEntityId = r.legalEntityId,
        counterpartyId = r.counterpartyId,
        currency = r.currency,
        valueDate = r.valueDate,
        status = NettingStatus.valueOf(r.status),
        receivableTotal = r.receivableTotal,
        payableTotal = r.payableTotal,
        netAmount = r.netAmount,
        invoices = dsl.selectFrom(NETTING_STATEMENT_INVOICE).where(NETTING_STATEMENT_INVOICE.STATEMENT_ID.eq(r.id))
            .fetch().associate { it.invoiceId to it.amount },
        counterpartyRef = r.counterpartyRef,
        settlementPaymentId = r.settlementPaymentId,
        documentId = r.documentId,
        createdBy = r.createdBy,
        agreedBy = r.agreedBy,
        version = r.version,
    )
}
