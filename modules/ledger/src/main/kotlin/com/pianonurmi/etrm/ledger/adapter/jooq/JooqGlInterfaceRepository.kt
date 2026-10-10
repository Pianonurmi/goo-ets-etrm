package com.pianonurmi.etrm.ledger.adapter.jooq

import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.GlAccount.GL_ACCOUNT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.GlBalance.GL_BALANCE
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.GlExport.GL_EXPORT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.GlExportEntry.GL_EXPORT_ENTRY
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.GlExportFormat.GL_EXPORT_FORMAT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.JournalEntry.JOURNAL_ENTRY
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.JournalLine.JOURNAL_LINE
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.PostingRuleChange.POSTING_RULE_CHANGE
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.PostingRuleHistory.POSTING_RULE_HISTORY
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.PostingRuleChangeRecord
import com.pianonurmi.etrm.ledger.api.AccountType
import com.pianonurmi.etrm.ledger.api.GlBalanceLine
import com.pianonurmi.etrm.ledger.api.GlExportFormatView
import com.pianonurmi.etrm.ledger.api.GlExportStatus
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.PostingRuleChangeView
import com.pianonurmi.etrm.ledger.api.PostingRuleVersionView
import com.pianonurmi.etrm.ledger.api.RuleChangeStatus
import com.pianonurmi.etrm.ledger.api.UpsertPostingRule
import com.pianonurmi.etrm.ledger.domain.GlExportLayout
import com.pianonurmi.etrm.ledger.domain.GlInterfaceRepository
import com.pianonurmi.etrm.platform.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqGlInterfaceRepository(private val dsl: DSLContext) : GlInterfaceRepository {
    override fun formats(): List<GlExportFormatView> = dsl.selectFrom(GL_EXPORT_FORMAT).orderBy(GL_EXPORT_FORMAT.CODE).fetch {
        GlExportFormatView(it.code, it.name, it.verified, it.active)
    }

    override fun layout(code: String): GlExportLayout? = dsl.selectFrom(GL_EXPORT_FORMAT)
        .where(GL_EXPORT_FORMAT.CODE.eq(code), GL_EXPORT_FORMAT.ACTIVE.isTrue)
        .fetchOne()?.let { Json.mapper.readValue(it.layout.data(), GlExportLayout::class.java) }

    override fun exportedEntryIds(legalEntityId: UUID, period: String): Set<UUID> = dsl.select(GL_EXPORT_ENTRY.ENTRY_ID)
        .from(GL_EXPORT_ENTRY)
        .join(GL_EXPORT).on(GL_EXPORT.ID.eq(GL_EXPORT_ENTRY.EXPORT_ID))
        .where(GL_EXPORT.LEGAL_ENTITY_ID.eq(legalEntityId), GL_EXPORT.PERIOD.eq(period), GL_EXPORT_ENTRY.ACTIVE.isTrue)
        .fetchSet(GL_EXPORT_ENTRY.ENTRY_ID)

    override fun insertEntries(exportId: UUID, entryIds: Collection<UUID>) {
        if (entryIds.isEmpty()) return
        dsl.batch(entryIds.map { dsl.insertInto(GL_EXPORT_ENTRY).set(GL_EXPORT_ENTRY.EXPORT_ID, exportId).set(GL_EXPORT_ENTRY.ENTRY_ID, it) }).execute()
    }

    override fun releaseEntries(exportId: UUID) {
        dsl.update(GL_EXPORT_ENTRY).set(GL_EXPORT_ENTRY.ACTIVE, false).where(GL_EXPORT_ENTRY.EXPORT_ID.eq(exportId)).execute()
    }

    override fun find(id: UUID, lock: Boolean): GlExportView? = dsl.selectFrom(GL_EXPORT)
        .where(GL_EXPORT.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne(::toGlExport)

    override fun exports(legalEntityId: UUID, period: String?): List<GlExportView> = dsl.selectFrom(GL_EXPORT)
        .where(GL_EXPORT.LEGAL_ENTITY_ID.eq(legalEntityId), period?.let { GL_EXPORT.PERIOD.eq(it) } ?: DSL.noCondition())
        .orderBy(GL_EXPORT.EXPORT_SEQ)
        .fetch(::toGlExport)

    override fun setStatus(id: UUID, status: GlExportStatus, reason: String?, acknowledgementRef: String?, by: String, at: Instant) {
        dsl.update(GL_EXPORT)
            .set(GL_EXPORT.STATUS, status.name)
            .set(GL_EXPORT.STATUS_REASON, reason)
            .set(GL_EXPORT.ACKNOWLEDGEMENT_REF, acknowledgementRef)
            .set(GL_EXPORT.STATUS_CHANGED_BY, by)
            .set(GL_EXPORT.STATUS_CHANGED_AT, at)
            .where(GL_EXPORT.ID.eq(id))
            .execute()
    }

    override fun subLedgerBalances(legalEntityId: UUID, upTo: LocalDate, exportedOnly: Boolean): Map<String, BigDecimal> {
        val balance = DSL.sum(JOURNAL_LINE.BASE_DEBIT.minus(JOURNAL_LINE.BASE_CREDIT))
        val exported = DSL.exists(
            DSL.selectOne().from(GL_EXPORT_ENTRY).where(GL_EXPORT_ENTRY.ENTRY_ID.eq(JOURNAL_ENTRY.ID), GL_EXPORT_ENTRY.ACTIVE.isTrue),
        )
        return dsl.select(GL_ACCOUNT.GL_MAPPING, balance)
            .from(JOURNAL_LINE)
            .join(JOURNAL_ENTRY).on(JOURNAL_ENTRY.ID.eq(JOURNAL_LINE.ENTRY_ID))
            .join(GL_ACCOUNT).on(GL_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
            .where(
                JOURNAL_ENTRY.LEGAL_ENTITY_ID.eq(legalEntityId),
                JOURNAL_ENTRY.POSTING_DATE.le(upTo),
                GL_ACCOUNT.ACCOUNT_TYPE.ne(AccountType.MEMO.name),
                GL_ACCOUNT.GL_MAPPING.isNotNull,
                if (exportedOnly) exported else DSL.noCondition(),
            )
            .groupBy(GL_ACCOUNT.GL_MAPPING)
            .fetch()
            .associate { it.value1() to (it.value2() ?: BigDecimal.ZERO) }
    }

    override fun replaceGlBalances(legalEntityId: UUID, period: String, balances: List<GlBalanceLine>, by: String) {
        dsl.deleteFrom(GL_BALANCE).where(GL_BALANCE.LEGAL_ENTITY_ID.eq(legalEntityId), GL_BALANCE.PERIOD.eq(period)).execute()
        balances.forEach {
            dsl.insertInto(GL_BALANCE)
                .set(GL_BALANCE.LEGAL_ENTITY_ID, legalEntityId)
                .set(GL_BALANCE.PERIOD, period)
                .set(GL_BALANCE.GL_ACCOUNT, it.glAccount)
                .set(GL_BALANCE.BALANCE, it.balance)
                .set(GL_BALANCE.CURRENCY, it.currency)
                .set(GL_BALANCE.IMPORTED_BY, by)
                .execute()
        }
    }

    override fun glBalances(legalEntityId: UUID, period: String): Map<String, BigDecimal> = dsl.selectFrom(GL_BALANCE)
        .where(GL_BALANCE.LEGAL_ENTITY_ID.eq(legalEntityId), GL_BALANCE.PERIOD.eq(period))
        .fetch()
        .associate { it.glAccount to it.balance }

    override fun insertRuleChange(change: PostingRuleChangeView) {
        dsl.insertInto(POSTING_RULE_CHANGE)
            .set(POSTING_RULE_CHANGE.ID, change.id)
            .set(POSTING_RULE_CHANGE.LEGAL_ENTITY_ID, change.legalEntityId)
            .set(POSTING_RULE_CHANGE.RULE_CODE, change.ruleCode)
            .set(POSTING_RULE_CHANGE.PAYLOAD, JSONB.valueOf(Json.mapper.writeValueAsString(change.change)))
            .set(POSTING_RULE_CHANGE.STATUS, change.status.name)
            .set(POSTING_RULE_CHANGE.REQUESTED_BY, change.requestedBy)
            .set(POSTING_RULE_CHANGE.REQUESTED_AT, change.requestedAt)
            .execute()
    }

    override fun ruleChange(id: UUID, lock: Boolean): PostingRuleChangeView? = dsl.selectFrom(POSTING_RULE_CHANGE)
        .where(POSTING_RULE_CHANGE.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toChange)

    override fun ruleChanges(status: RuleChangeStatus?): List<PostingRuleChangeView> = dsl.selectFrom(POSTING_RULE_CHANGE)
        .where(status?.let { POSTING_RULE_CHANGE.STATUS.eq(it.name) } ?: DSL.noCondition())
        .orderBy(POSTING_RULE_CHANGE.REQUESTED_AT.desc())
        .fetch(::toChange)

    override fun decideRuleChange(id: UUID, status: RuleChangeStatus, by: String, at: Instant) {
        dsl.update(POSTING_RULE_CHANGE)
            .set(POSTING_RULE_CHANGE.STATUS, status.name)
            .set(POSTING_RULE_CHANGE.DECIDED_BY, by)
            .set(POSTING_RULE_CHANGE.DECIDED_AT, at)
            .where(POSTING_RULE_CHANGE.ID.eq(id))
            .execute()
    }

    override fun insertRuleVersion(version: PostingRuleVersionView) {
        dsl.insertInto(POSTING_RULE_HISTORY)
            .set(POSTING_RULE_HISTORY.RULE_CODE, version.ruleCode)
            .set(POSTING_RULE_HISTORY.LEGAL_ENTITY_ID, version.legalEntityId)
            .set(POSTING_RULE_HISTORY.VERSION, version.version)
            .set(POSTING_RULE_HISTORY.PAYLOAD, JSONB.valueOf(Json.mapper.writeValueAsString(version.rule)))
            .set(POSTING_RULE_HISTORY.CHANGE_ID, version.changeId)
            .set(POSTING_RULE_HISTORY.RECORDED_AT, version.recordedAt)
            .execute()
    }

    override fun ruleHistory(ruleCode: String): List<PostingRuleVersionView> = dsl.selectFrom(POSTING_RULE_HISTORY)
        .where(POSTING_RULE_HISTORY.RULE_CODE.eq(ruleCode))
        .orderBy(POSTING_RULE_HISTORY.LEGAL_ENTITY_ID, POSTING_RULE_HISTORY.VERSION)
        .fetch {
            PostingRuleVersionView(
                it.ruleCode,
                it.legalEntityId,
                it.version,
                Json.mapper.readValue(it.payload.data(), UpsertPostingRule::class.java),
                it.changeId,
                it.recordedAt,
            )
        }

    private fun toChange(r: PostingRuleChangeRecord) = PostingRuleChangeView(
        r.id,
        r.legalEntityId,
        r.ruleCode,
        Json.mapper.readValue(r.payload.data(), UpsertPostingRule::class.java),
        RuleChangeStatus.valueOf(r.status),
        r.requestedBy,
        r.requestedAt,
        r.decidedBy,
        r.decidedAt,
    )
}
