package com.pianonurmi.etrm.clearing.adapter.jooq

import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.MarginCall.MARGIN_CALL
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.records.MarginCallRecord
import com.pianonurmi.etrm.clearing.api.MarginCallStatus
import com.pianonurmi.etrm.clearing.api.MarginCallView
import com.pianonurmi.etrm.clearing.domain.MarginCallRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqMarginCallRepository(private val dsl: DSLContext) : MarginCallRepository {
    override fun insert(call: MarginCallView) {
        dsl.insertInto(MARGIN_CALL)
            .set(MARGIN_CALL.ID, call.id)
            .set(MARGIN_CALL.ACCOUNT_ID, call.accountId)
            .set(MARGIN_CALL.LEGAL_ENTITY_ID, call.legalEntityId)
            .set(MARGIN_CALL.BUSINESS_DATE, call.businessDate)
            .set(MARGIN_CALL.STATEMENT_ID, call.statementId)
            .set(MARGIN_CALL.AMOUNT, call.amount)
            .set(MARGIN_CALL.CURRENCY, call.currency)
            .set(MARGIN_CALL.STATUS, call.status.name)
            .set(MARGIN_CALL.REQUESTED_BY, call.requestedBy)
            .set(MARGIN_CALL.REQUESTED_AT, call.requestedAt)
            .execute()
    }

    override fun update(call: MarginCallView) {
        dsl.update(MARGIN_CALL)
            .set(MARGIN_CALL.STATUS, call.status.name)
            .set(MARGIN_CALL.DECIDED_BY, call.decidedBy)
            .set(MARGIN_CALL.DECIDED_AT, call.decidedAt)
            .set(MARGIN_CALL.REASON, call.reason)
            .where(MARGIN_CALL.ID.eq(call.id))
            .execute()
    }

    override fun openCall(accountId: UUID): MarginCallView? = dsl.selectFrom(MARGIN_CALL)
        .where(MARGIN_CALL.ACCOUNT_ID.eq(accountId), MARGIN_CALL.STATUS.eq(MarginCallStatus.OPEN.name))
        .forUpdate()
        .fetchOne()?.let(::toCall)

    override fun calls(legalEntityId: UUID?, status: MarginCallStatus?): List<MarginCallView> = dsl.selectFrom(MARGIN_CALL)
        .where(legalEntityId?.let { MARGIN_CALL.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition())
        .and(status?.let { MARGIN_CALL.STATUS.eq(it.name) } ?: DSL.noCondition())
        .orderBy(MARGIN_CALL.BUSINESS_DATE.desc(), MARGIN_CALL.REQUESTED_AT.desc())
        .limit(MAX_CALLS)
        .fetch(::toCall)

    override fun call(id: UUID, lock: Boolean): MarginCallView? = dsl.selectFrom(MARGIN_CALL)
        .where(MARGIN_CALL.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toCall)

    private fun toCall(r: MarginCallRecord) = MarginCallView(
        r.id,
        r.accountId,
        r.legalEntityId,
        r.businessDate,
        r.statementId,
        r.amount,
        r.currency,
        MarginCallStatus.valueOf(r.status),
        r.requestedBy,
        r.requestedAt,
        r.decidedBy,
        r.decidedAt,
        r.reason,
    )

    private companion object {
        const val MAX_CALLS = 500
    }
}
