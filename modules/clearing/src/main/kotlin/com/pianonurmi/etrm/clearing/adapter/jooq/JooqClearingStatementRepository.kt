package com.pianonurmi.etrm.clearing.adapter.jooq

import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.ClearingAccount.CLEARING_ACCOUNT
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.ClearingBreak.CLEARING_BREAK
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.ClearingStatement.CLEARING_STATEMENT
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.ClearingStatementLine.CLEARING_STATEMENT_LINE
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.records.ClearingBreakRecord
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.records.ClearingStatementRecord
import com.pianonurmi.etrm.clearing.api.ClearingBreakStatus
import com.pianonurmi.etrm.clearing.api.ClearingBreakView
import com.pianonurmi.etrm.clearing.api.ClearingStatementView
import com.pianonurmi.etrm.clearing.api.MarginDifference
import com.pianonurmi.etrm.clearing.api.StatementLineView
import com.pianonurmi.etrm.clearing.domain.ClearingStatementRepository
import com.pianonurmi.etrm.platform.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqClearingStatementRepository(private val dsl: DSLContext) : ClearingStatementRepository {
    private val differencesType = object : TypeReference<List<MarginDifference>>() {}

    override fun insertStatement(statement: ClearingStatementView) {
        dsl.insertInto(CLEARING_STATEMENT)
            .set(CLEARING_STATEMENT.ID, statement.id)
            .set(CLEARING_STATEMENT.ACCOUNT_ID, statement.accountId)
            .set(CLEARING_STATEMENT.LEGAL_ENTITY_ID, statement.legalEntityId)
            .set(CLEARING_STATEMENT.BUSINESS_DATE, statement.businessDate)
            .set(CLEARING_STATEMENT.VERSION, statement.version)
            .set(CLEARING_STATEMENT.CURRENCY, statement.currency)
            .set(CLEARING_STATEMENT.VARIATION_MARGIN, statement.variationMargin)
            .set(CLEARING_STATEMENT.INITIAL_MARGIN, statement.initialMargin)
            .set(CLEARING_STATEMENT.FEES, statement.fees)
            .set(CLEARING_STATEMENT.CASH_BALANCE, statement.cashBalance)
            .set(CLEARING_STATEMENT.CONTENT_HASH, statement.contentHash)
            .set(CLEARING_STATEMENT.FILE_NAME, statement.fileName)
            .set(CLEARING_STATEMENT.IMPORTED_BY, statement.importedBy)
            .set(CLEARING_STATEMENT.IMPORTED_AT, statement.importedAt)
            .execute()
        statement.lines.forEach { l ->
            dsl.insertInto(CLEARING_STATEMENT_LINE)
                .set(CLEARING_STATEMENT_LINE.STATEMENT_ID, statement.id)
                .set(CLEARING_STATEMENT_LINE.CONTRACT_CODE, l.contractCode)
                .set(CLEARING_STATEMENT_LINE.POSITION, l.position)
                .set(CLEARING_STATEMENT_LINE.SETTLEMENT_PRICE, l.settlementPrice)
                .set(CLEARING_STATEMENT_LINE.VARIATION_MARGIN, l.variationMargin)
                .set(CLEARING_STATEMENT_LINE.FEES, l.fees)
                .execute()
        }
    }

    override fun currentStatement(accountId: UUID, businessDate: LocalDate, lock: Boolean): ClearingStatementView? =
        dsl.selectFrom(CLEARING_STATEMENT)
            .where(CLEARING_STATEMENT.ACCOUNT_ID.eq(accountId), CLEARING_STATEMENT.BUSINESS_DATE.eq(businessDate), CLEARING_STATEMENT.SUPERSEDED_AT.isNull)
            .let { if (lock) it.forUpdate() else it }
            .fetchOne()?.let(::toStatement)

    override fun statements(accountId: UUID, from: LocalDate, to: LocalDate): List<ClearingStatementView> = dsl.selectFrom(CLEARING_STATEMENT)
        .where(CLEARING_STATEMENT.ACCOUNT_ID.eq(accountId), CLEARING_STATEMENT.SUPERSEDED_AT.isNull, CLEARING_STATEMENT.BUSINESS_DATE.between(from, to))
        .orderBy(CLEARING_STATEMENT.BUSINESS_DATE.desc())
        .fetch(::toStatement)

    override fun statement(id: UUID): ClearingStatementView? =
        dsl.selectFrom(CLEARING_STATEMENT).where(CLEARING_STATEMENT.ID.eq(id)).fetchOne()?.let(::toStatement)

    override fun nextStatementVersion(accountId: UUID, businessDate: LocalDate): Int = (
        dsl.select(DSL.max(CLEARING_STATEMENT.VERSION)).from(CLEARING_STATEMENT)
            .where(CLEARING_STATEMENT.ACCOUNT_ID.eq(accountId), CLEARING_STATEMENT.BUSINESS_DATE.eq(businessDate))
            .fetchOne(0, Int::class.java) ?: 0
    ) + 1

    override fun supersedeStatement(id: UUID, at: Instant) {
        dsl.update(CLEARING_STATEMENT).set(CLEARING_STATEMENT.SUPERSEDED_AT, at).where(CLEARING_STATEMENT.ID.eq(id)).execute()
    }

    override fun latestStatementDate(accountId: UUID): LocalDate? = dsl.select(DSL.max(CLEARING_STATEMENT.BUSINESS_DATE))
        .from(CLEARING_STATEMENT)
        .where(CLEARING_STATEMENT.ACCOUNT_ID.eq(accountId), CLEARING_STATEMENT.SUPERSEDED_AT.isNull)
        .fetchOne(0, LocalDate::class.java)

    override fun lockAccount(accountId: UUID) {
        dsl.select(CLEARING_ACCOUNT.ID).from(CLEARING_ACCOUNT).where(CLEARING_ACCOUNT.ID.eq(accountId)).forUpdate().fetch()
    }

    override fun setInitialMargin(accountId: UUID, initialMargin: BigDecimal) {
        dsl.update(CLEARING_ACCOUNT).set(CLEARING_ACCOUNT.INITIAL_MARGIN, initialMargin).where(CLEARING_ACCOUNT.ID.eq(accountId)).execute()
    }

    override fun insertBreak(clearingBreak: ClearingBreakView) {
        dsl.insertInto(CLEARING_BREAK)
            .set(CLEARING_BREAK.ID, clearingBreak.id)
            .set(CLEARING_BREAK.ACCOUNT_ID, clearingBreak.accountId)
            .set(CLEARING_BREAK.LEGAL_ENTITY_ID, clearingBreak.legalEntityId)
            .set(CLEARING_BREAK.BUSINESS_DATE, clearingBreak.businessDate)
            .set(CLEARING_BREAK.STATEMENT_ID, clearingBreak.statementId)
            .set(CLEARING_BREAK.RUN_ID, clearingBreak.runId)
            .set(CLEARING_BREAK.STATUS, clearingBreak.status.name)
            .set(CLEARING_BREAK.DIFFERENCES, JSONB.jsonb(Json.mapper.writeValueAsString(clearingBreak.differences)))
            .set(CLEARING_BREAK.OPENED_BY, clearingBreak.openedBy)
            .set(CLEARING_BREAK.OPENED_AT, clearingBreak.openedAt)
            .execute()
    }

    override fun updateBreak(clearingBreak: ClearingBreakView) {
        dsl.update(CLEARING_BREAK)
            .set(CLEARING_BREAK.STATEMENT_ID, clearingBreak.statementId)
            .set(CLEARING_BREAK.RUN_ID, clearingBreak.runId)
            .set(CLEARING_BREAK.STATUS, clearingBreak.status.name)
            .set(CLEARING_BREAK.DIFFERENCES, JSONB.jsonb(Json.mapper.writeValueAsString(clearingBreak.differences)))
            .set(CLEARING_BREAK.CLOSED_BY, clearingBreak.closedBy)
            .set(CLEARING_BREAK.CLOSED_AT, clearingBreak.closedAt)
            .set(CLEARING_BREAK.RESOLUTION, clearingBreak.resolution)
            .where(CLEARING_BREAK.ID.eq(clearingBreak.id))
            .execute()
    }

    override fun openBreak(accountId: UUID, businessDate: LocalDate): ClearingBreakView? = dsl.selectFrom(CLEARING_BREAK)
        .where(CLEARING_BREAK.ACCOUNT_ID.eq(accountId), CLEARING_BREAK.BUSINESS_DATE.eq(businessDate), CLEARING_BREAK.STATUS.eq(ClearingBreakStatus.OPEN.name))
        .forUpdate()
        .fetchOne()?.let(::toBreak)

    override fun breaks(legalEntityId: UUID?, status: ClearingBreakStatus?): List<ClearingBreakView> = dsl.selectFrom(CLEARING_BREAK)
        .where(legalEntityId?.let { CLEARING_BREAK.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition())
        .and(status?.let { CLEARING_BREAK.STATUS.eq(it.name) } ?: DSL.noCondition())
        .orderBy(CLEARING_BREAK.BUSINESS_DATE.desc(), CLEARING_BREAK.OPENED_AT.desc())
        .limit(MAX_BREAKS)
        .fetch(::toBreak)

    override fun clearingBreak(id: UUID, lock: Boolean): ClearingBreakView? = dsl.selectFrom(CLEARING_BREAK)
        .where(CLEARING_BREAK.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toBreak)

    private fun lines(statementId: UUID): List<StatementLineView> = dsl.selectFrom(CLEARING_STATEMENT_LINE)
        .where(CLEARING_STATEMENT_LINE.STATEMENT_ID.eq(statementId))
        .orderBy(CLEARING_STATEMENT_LINE.CONTRACT_CODE)
        .fetch { StatementLineView(it.contractCode, it.position, it.settlementPrice, it.variationMargin, it.fees) }

    private fun toStatement(r: ClearingStatementRecord) = ClearingStatementView(
        r.id,
        r.accountId,
        r.legalEntityId,
        r.businessDate,
        r.version,
        r.currency,
        r.variationMargin,
        r.initialMargin,
        r.fees,
        r.cashBalance,
        lines(r.id),
        r.contentHash,
        r.fileName,
        r.importedBy,
        r.importedAt,
        r.supersededAt,
    )

    private fun toBreak(r: ClearingBreakRecord) = ClearingBreakView(
        r.id,
        r.accountId,
        r.legalEntityId,
        r.businessDate,
        r.statementId,
        r.runId,
        ClearingBreakStatus.valueOf(r.status),
        Json.mapper.readValue(r.differences.data(), differencesType),
        r.openedBy,
        r.openedAt,
        r.closedBy,
        r.closedAt,
        r.resolution,
    )

    private companion object {
        const val MAX_BREAKS = 500
    }
}
