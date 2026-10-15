package com.pianonurmi.etrm.clearing.adapter.jooq

import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.ClearingAccount.CLEARING_ACCOUNT
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.MarginLine.MARGIN_LINE
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.MarginRun.MARGIN_RUN
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.records.ClearingAccountRecord
import com.pianonurmi.etrm.clearing.adapter.jooq.generated.tables.records.MarginRunRecord
import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.MarginLineView
import com.pianonurmi.etrm.clearing.api.MarginRunView
import com.pianonurmi.etrm.clearing.domain.ClearingRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqClearingRepository(private val dsl: DSLContext) : ClearingRepository {
    override fun insertAccount(account: ClearingAccountView) {
        dsl.insertInto(CLEARING_ACCOUNT)
            .set(CLEARING_ACCOUNT.ID, account.id)
            .set(CLEARING_ACCOUNT.CODE, account.code)
            .set(CLEARING_ACCOUNT.LEGAL_ENTITY_ID, account.legalEntityId)
            .set(CLEARING_ACCOUNT.CLEARING_HOUSE_ID, account.clearingHouseId)
            .set(CLEARING_ACCOUNT.CLEARING_MEMBER, account.clearingMember)
            .set(CLEARING_ACCOUNT.CURRENCY, account.currency)
            .set(CLEARING_ACCOUNT.ACTIVE, account.active)
            .set(CLEARING_ACCOUNT.CREATED_BY, account.createdBy)
            .set(CLEARING_ACCOUNT.CREATED_AT, account.createdAt)
            .execute()
    }

    override fun accounts(legalEntityId: UUID?): List<ClearingAccountView> = dsl.selectFrom(CLEARING_ACCOUNT)
        .where(legalEntityId?.let { CLEARING_ACCOUNT.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(CLEARING_ACCOUNT.CODE)
        .fetch(::toAccount)

    override fun account(id: UUID): ClearingAccountView? = dsl.selectFrom(CLEARING_ACCOUNT).where(CLEARING_ACCOUNT.ID.eq(id)).fetchOne()?.let(::toAccount)

    override fun insertRun(run: MarginRunView) {
        dsl.insertInto(MARGIN_RUN)
            .set(MARGIN_RUN.ID, run.id)
            .set(MARGIN_RUN.ACCOUNT_ID, run.accountId)
            .set(MARGIN_RUN.LEGAL_ENTITY_ID, run.legalEntityId)
            .set(MARGIN_RUN.BUSINESS_DATE, run.businessDate)
            .set(MARGIN_RUN.VERSION, run.version)
            .set(MARGIN_RUN.CURRENCY, run.currency)
            .set(MARGIN_RUN.VARIATION_MARGIN, run.variationMargin)
            .set(MARGIN_RUN.INPUTS_HASH, run.inputsHash)
            .set(MARGIN_RUN.COMPUTED_BY, run.computedBy)
            .set(MARGIN_RUN.COMPUTED_AT, run.computedAt)
            .execute()
        run.lines.forEach { l ->
            dsl.insertInto(MARGIN_LINE)
                .set(MARGIN_LINE.RUN_ID, run.id)
                .set(MARGIN_LINE.CONTRACT_CODE, l.contractCode)
                .set(MARGIN_LINE.POSITION_BEFORE, l.positionBefore)
                .set(MARGIN_LINE.TRADED_QUANTITY, l.tradedQuantity)
                .set(MARGIN_LINE.POSITION_AFTER, l.positionAfter)
                .set(MARGIN_LINE.SETTLEMENT_PRICE, l.settlementPrice)
                .set(MARGIN_LINE.PREVIOUS_SETTLEMENT_PRICE, l.previousSettlementPrice)
                .set(MARGIN_LINE.VARIATION_MARGIN, l.variationMargin)
                .execute()
        }
    }

    override fun currentRun(accountId: UUID, businessDate: LocalDate, lock: Boolean): MarginRunView? = dsl.selectFrom(MARGIN_RUN)
        .where(MARGIN_RUN.ACCOUNT_ID.eq(accountId), MARGIN_RUN.BUSINESS_DATE.eq(businessDate), MARGIN_RUN.SUPERSEDED_AT.isNull)
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toRun)

    override fun runs(accountId: UUID, from: LocalDate, to: LocalDate): List<MarginRunView> = dsl.selectFrom(MARGIN_RUN)
        .where(MARGIN_RUN.ACCOUNT_ID.eq(accountId), MARGIN_RUN.SUPERSEDED_AT.isNull, MARGIN_RUN.BUSINESS_DATE.between(from, to))
        .orderBy(MARGIN_RUN.BUSINESS_DATE.desc())
        .fetch(::toRun)

    override fun laterRunDates(accountId: UUID, date: LocalDate): List<LocalDate> = dsl.select(MARGIN_RUN.BUSINESS_DATE).from(MARGIN_RUN)
        .where(MARGIN_RUN.ACCOUNT_ID.eq(accountId), MARGIN_RUN.SUPERSEDED_AT.isNull, MARGIN_RUN.BUSINESS_DATE.gt(date))
        .orderBy(MARGIN_RUN.BUSINESS_DATE.asc())
        .fetch(MARGIN_RUN.BUSINESS_DATE)

    override fun run(id: UUID): MarginRunView? = dsl.selectFrom(MARGIN_RUN).where(MARGIN_RUN.ID.eq(id)).fetchOne()?.let(::toRun)

    override fun nextVersion(accountId: UUID, businessDate: LocalDate): Int = (
        dsl.select(DSL.max(MARGIN_RUN.VERSION)).from(MARGIN_RUN)
            .where(MARGIN_RUN.ACCOUNT_ID.eq(accountId), MARGIN_RUN.BUSINESS_DATE.eq(businessDate))
            .fetchOne(0, Int::class.java) ?: 0
    ) + 1

    override fun supersede(runId: UUID, at: Instant) {
        dsl.update(MARGIN_RUN).set(MARGIN_RUN.SUPERSEDED_AT, at).where(MARGIN_RUN.ID.eq(runId)).execute()
    }

    override fun markStale(runId: UUID, reason: String?) {
        dsl.update(MARGIN_RUN).set(MARGIN_RUN.STALE_REASON, reason).where(MARGIN_RUN.ID.eq(runId)).execute()
    }

    private fun lines(runId: UUID): List<MarginLineView> = dsl.selectFrom(MARGIN_LINE)
        .where(MARGIN_LINE.RUN_ID.eq(runId))
        .orderBy(MARGIN_LINE.CONTRACT_CODE)
        .fetch {
            MarginLineView(
                it.contractCode,
                it.positionBefore,
                it.tradedQuantity,
                it.positionAfter,
                it.settlementPrice,
                it.previousSettlementPrice,
                it.variationMargin,
            )
        }

    private fun toAccount(r: ClearingAccountRecord) = ClearingAccountView(
        r.id,
        r.code,
        r.legalEntityId,
        r.clearingHouseId,
        r.clearingMember,
        r.currency,
        r.active,
        r.initialMargin,
        r.createdBy,
        r.createdAt,
    )

    private fun toRun(r: MarginRunRecord) = MarginRunView(
        r.id,
        r.accountId,
        r.legalEntityId,
        r.businessDate,
        r.version,
        r.currency,
        r.variationMargin,
        lines(r.id),
        r.inputsHash,
        r.computedBy,
        r.computedAt,
        r.supersededAt,
        r.staleReason,
    )
}
