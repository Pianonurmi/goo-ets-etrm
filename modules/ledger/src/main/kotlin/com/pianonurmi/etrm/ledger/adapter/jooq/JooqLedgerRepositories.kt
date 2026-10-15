package com.pianonurmi.etrm.ledger.adapter.jooq

import com.pianonurmi.etrm.ledger.adapter.jooq.generated.Sequences.JOURNAL_ENTRY_SEQ
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.AccountingPeriod.ACCOUNTING_PERIOD
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.CommitmentMemo.COMMITMENT_MEMO
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.CommitmentMemoMovement.COMMITMENT_MEMO_MOVEMENT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.CostLayer.COST_LAYER
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.GlAccount.GL_ACCOUNT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.GlExport.GL_EXPORT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.JournalEntry.JOURNAL_ENTRY
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.JournalLine.JOURNAL_LINE
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.LedgerSeed.LEDGER_SEED
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.PostingRule.POSTING_RULE
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.JournalEntryRecord
import com.pianonurmi.etrm.ledger.api.AccountType
import com.pianonurmi.etrm.ledger.api.AccountingPeriodView
import com.pianonurmi.etrm.ledger.api.EntryStatus
import com.pianonurmi.etrm.ledger.api.GlAccountView
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.JournalLineView
import com.pianonurmi.etrm.ledger.api.PeriodStatus
import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.RuleLine
import com.pianonurmi.etrm.ledger.domain.ChainedEntry
import com.pianonurmi.etrm.ledger.domain.CommitmentMemo
import com.pianonurmi.etrm.ledger.domain.CommitmentMemoRepository
import com.pianonurmi.etrm.ledger.domain.CostLayer
import com.pianonurmi.etrm.ledger.domain.CostLayerRepository
import com.pianonurmi.etrm.ledger.domain.GlAccountRepository
import com.pianonurmi.etrm.ledger.domain.GlExportRepository
import com.pianonurmi.etrm.ledger.domain.JournalChain
import com.pianonurmi.etrm.ledger.domain.JournalRepository
import com.pianonurmi.etrm.ledger.domain.LineBalance
import com.pianonurmi.etrm.ledger.domain.MemoMovementKind
import com.pianonurmi.etrm.ledger.domain.PeriodRepository
import com.pianonurmi.etrm.ledger.domain.PostingRuleRepository
import com.pianonurmi.etrm.ledger.domain.SeedRepository
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqGlAccountRepository(private val dsl: DSLContext) : GlAccountRepository {
    override fun accounts(legalEntityId: UUID): List<GlAccountView> = dsl.selectFrom(GL_ACCOUNT)
        .where(GL_ACCOUNT.LEGAL_ENTITY_ID.eq(legalEntityId))
        .orderBy(GL_ACCOUNT.CODE)
        .fetch { GlAccountView(it.id, it.legalEntityId, it.code, it.name, AccountType.valueOf(it.accountType), it.glMapping, it.active) }

    override fun insert(account: GlAccountView) {
        dsl.insertInto(GL_ACCOUNT)
            .set(GL_ACCOUNT.ID, account.id)
            .set(GL_ACCOUNT.LEGAL_ENTITY_ID, account.legalEntityId)
            .set(GL_ACCOUNT.CODE, account.code)
            .set(GL_ACCOUNT.NAME, account.name)
            .set(GL_ACCOUNT.ACCOUNT_TYPE, account.type.name)
            .set(GL_ACCOUNT.GL_MAPPING, account.glMapping)
            .set(GL_ACCOUNT.ACTIVE, account.active)
            .onConflict(GL_ACCOUNT.LEGAL_ENTITY_ID, GL_ACCOUNT.CODE)
            .doNothing()
            .execute()
    }
}

@Repository
class JooqPostingRuleRepository(private val dsl: DSLContext) : PostingRuleRepository {
    private val linesType = object : TypeReference<List<RuleLine>>() {}
    private val conditionsType = object : TypeReference<Map<String, String>>() {}

    override fun all(): List<PostingRuleView> = dsl.selectFrom(POSTING_RULE).orderBy(POSTING_RULE.PRIORITY, POSTING_RULE.RULE_CODE).fetch { r ->
        PostingRuleView(
            r.id,
            r.legalEntityId,
            r.ruleCode,
            r.eventType,
            Json.mapper.readValue(r.conditions.data(), conditionsType),
            Json.mapper.readValue(r.lines.data(), linesType),
            r.description,
            r.priority,
            r.effectiveFrom,
            r.active,
            r.version,
        )
    }

    override fun upsert(rule: PostingRuleView) {
        val existing = dsl.selectFrom(POSTING_RULE)
            .where(POSTING_RULE.RULE_CODE.eq(rule.ruleCode))
            .and(rule.legalEntityId?.let { POSTING_RULE.LEGAL_ENTITY_ID.eq(it) } ?: POSTING_RULE.LEGAL_ENTITY_ID.isNull)
            .fetchOne()
        if (existing == null) {
            dsl.insertInto(POSTING_RULE)
                .set(POSTING_RULE.ID, rule.id)
                .set(POSTING_RULE.LEGAL_ENTITY_ID, rule.legalEntityId)
                .set(POSTING_RULE.RULE_CODE, rule.ruleCode)
                .set(POSTING_RULE.EVENT_TYPE, rule.eventType)
                .set(POSTING_RULE.CONDITIONS, JSONB.jsonb(Json.mapper.writeValueAsString(rule.conditions)))
                .set(POSTING_RULE.LINES, JSONB.jsonb(Json.mapper.writeValueAsString(rule.lines)))
                .set(POSTING_RULE.DESCRIPTION, rule.description)
                .set(POSTING_RULE.PRIORITY, rule.priority)
                .set(POSTING_RULE.EFFECTIVE_FROM, rule.effectiveFrom)
                .set(POSTING_RULE.ACTIVE, rule.active)
                .execute()
        } else {
            dsl.update(POSTING_RULE)
                .set(POSTING_RULE.EVENT_TYPE, rule.eventType)
                .set(POSTING_RULE.CONDITIONS, JSONB.jsonb(Json.mapper.writeValueAsString(rule.conditions)))
                .set(POSTING_RULE.LINES, JSONB.jsonb(Json.mapper.writeValueAsString(rule.lines)))
                .set(POSTING_RULE.DESCRIPTION, rule.description)
                .set(POSTING_RULE.PRIORITY, rule.priority)
                .set(POSTING_RULE.EFFECTIVE_FROM, rule.effectiveFrom)
                .set(POSTING_RULE.ACTIVE, rule.active)
                .set(POSTING_RULE.VERSION, existing.version + 1)
                .set(POSTING_RULE.UPDATED_AT, DSL.currentInstant())
                .where(POSTING_RULE.ID.eq(existing.id))
                .execute()
        }
    }
}

@Repository
class JooqJournalRepository(private val dsl: DSLContext) : JournalRepository {
    private val dimsType = object : TypeReference<Map<String, String?>>() {}

    override fun nextEntryNumber(): Long = dsl.nextval(JOURNAL_ENTRY_SEQ)

    override fun lastHash(legalEntityId: UUID): String? = dsl.select(JOURNAL_ENTRY.HASH)
        .from(JOURNAL_ENTRY)
        .where(JOURNAL_ENTRY.LEGAL_ENTITY_ID.eq(legalEntityId))
        .orderBy(JOURNAL_ENTRY.ENTRY_NUMBER.desc())
        .limit(1)
        .fetchOne(JOURNAL_ENTRY.HASH)

    override fun insert(entry: JournalEntryView, accountIds: Map<String, UUID>) {
        dsl.insertInto(JOURNAL_ENTRY)
            .set(JOURNAL_ENTRY.ID, entry.id)
            .set(JOURNAL_ENTRY.ENTRY_NUMBER, entry.entryNumber)
            .set(JOURNAL_ENTRY.LEGAL_ENTITY_ID, entry.legalEntityId)
            .set(JOURNAL_ENTRY.PERIOD, entry.period)
            .set(JOURNAL_ENTRY.POSTING_DATE, entry.postingDate)
            .set(JOURNAL_ENTRY.EVENT_TYPE, entry.eventType)
            .set(JOURNAL_ENTRY.RULE_CODE, entry.ruleCode)
            .set(JOURNAL_ENTRY.SOURCE_TYPE, entry.sourceType)
            .set(JOURNAL_ENTRY.SOURCE_REF, entry.sourceRef)
            .set(JOURNAL_ENTRY.EVENT_ID, entry.eventId)
            .set(JOURNAL_ENTRY.DESCRIPTION, entry.description)
            .set(JOURNAL_ENTRY.CURRENCY, entry.currency)
            .set(JOURNAL_ENTRY.STATUS, entry.status.name)
            .set(JOURNAL_ENTRY.REVERSAL_OF, entry.reversalOf)
            .set(JOURNAL_ENTRY.CREATED_BY, entry.createdBy)
            .set(JOURNAL_ENTRY.CREATED_AT, entry.createdAt)
            .set(JOURNAL_ENTRY.PREV_HASH, entry.prevHash)
            .set(JOURNAL_ENTRY.HASH, entry.hash)
            .set(JOURNAL_ENTRY.CHAIN_VERSION, JournalChain.CURRENT.toShort())
            .execute()
        // One statement for all lines: the deferred balance check then holds even without an enclosing transaction.
        var lines = dsl.insertInto(
            JOURNAL_LINE,
            JOURNAL_LINE.ID,
            JOURNAL_LINE.ENTRY_ID,
            JOURNAL_LINE.LINE_NO,
            JOURNAL_LINE.ACCOUNT_ID,
            JOURNAL_LINE.DEBIT,
            JOURNAL_LINE.CREDIT,
            JOURNAL_LINE.CURRENCY,
            JOURNAL_LINE.FX_RATE,
            JOURNAL_LINE.BASE_DEBIT,
            JOURNAL_LINE.BASE_CREDIT,
            JOURNAL_LINE.DIMENSIONS,
            JOURNAL_LINE.MEMO,
        )
        entry.lines.forEach { l ->
            lines = lines.values(
                Ids.newId(),
                entry.id,
                l.lineNo,
                accountIds.getValue(l.accountCode),
                l.debit,
                l.credit,
                l.currency,
                l.fxRate,
                l.baseDebit,
                l.baseCredit,
                JSONB.jsonb(Json.mapper.writeValueAsString(l.dimensions)),
                l.memo,
            )
        }
        lines.execute()
    }

    override fun markReversed(entryId: UUID, reversalId: UUID): Boolean = dsl.update(JOURNAL_ENTRY)
        .set(JOURNAL_ENTRY.STATUS, EntryStatus.REVERSED.name)
        .set(JOURNAL_ENTRY.REVERSED_BY, reversalId)
        .where(JOURNAL_ENTRY.ID.eq(entryId), JOURNAL_ENTRY.STATUS.eq(EntryStatus.POSTED.name), JOURNAL_ENTRY.REVERSED_BY.isNull)
        .execute() == 1

    override fun find(id: UUID): JournalEntryView? = dsl.selectFrom(JOURNAL_ENTRY).where(JOURNAL_ENTRY.ID.eq(id)).fetchOne()?.let(::toView)

    override fun findByEvent(eventId: UUID, ruleCode: String): JournalEntryView? =
        dsl.selectFrom(JOURNAL_ENTRY).where(JOURNAL_ENTRY.EVENT_ID.eq(eventId), JOURNAL_ENTRY.RULE_CODE.eq(ruleCode)).fetchOne()?.let(::toView)

    override fun entries(
        legalEntityId: UUID,
        period: String?,
        sourceRef: String?,
        limit: Int,
        accountCode: String?,
        afterEntryNumber: Long?,
    ): List<JournalEntryView> = dsl.selectFrom(JOURNAL_ENTRY)
        .where(JOURNAL_ENTRY.LEGAL_ENTITY_ID.eq(legalEntityId))
        .and(period?.let { JOURNAL_ENTRY.PERIOD.eq(it) } ?: DSL.noCondition())
        .and(sourceRef?.let { JOURNAL_ENTRY.SOURCE_REF.eq(it) } ?: DSL.noCondition())
        .and(afterEntryNumber?.let { JOURNAL_ENTRY.ENTRY_NUMBER.gt(it) } ?: DSL.noCondition())
        .and(
            accountCode?.let { code ->
                DSL.exists(
                    DSL.selectOne().from(JOURNAL_LINE).join(GL_ACCOUNT).on(GL_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
                        .where(JOURNAL_LINE.ENTRY_ID.eq(JOURNAL_ENTRY.ID), GL_ACCOUNT.CODE.eq(code)),
                )
            } ?: DSL.noCondition(),
        )
        .orderBy(JOURNAL_ENTRY.ENTRY_NUMBER)
        .limit(limit)
        .fetch(::toView)

    override fun chain(legalEntityId: UUID): List<ChainedEntry> = dsl.selectFrom(JOURNAL_ENTRY)
        .where(JOURNAL_ENTRY.LEGAL_ENTITY_ID.eq(legalEntityId))
        .orderBy(JOURNAL_ENTRY.ENTRY_NUMBER)
        .fetch { ChainedEntry(toView(it), it.chainVersion.toInt()) }

    override fun lockEntity(legalEntityId: UUID) {
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext({0}))", DSL.`val`("ledger:$legalEntityId"))
    }

    override fun balance(
        legalEntityId: UUID,
        accountCode: String,
        upTo: LocalDate,
        eventType: String?,
        currency: String?,
        dimensions: Map<String, String>,
    ): LineBalance {
        val conditions = mutableListOf<Condition>(
            JOURNAL_ENTRY.LEGAL_ENTITY_ID.eq(legalEntityId),
            GL_ACCOUNT.CODE.eq(accountCode),
            JOURNAL_ENTRY.POSTING_DATE.le(upTo),
        )
        eventType?.let { conditions += JOURNAL_ENTRY.EVENT_TYPE.eq(it) }
        currency?.let { conditions += JOURNAL_LINE.CURRENCY.eq(it) }
        if (dimensions.isNotEmpty()) {
            conditions += DSL.condition("{0} @> {1}", JOURNAL_LINE.DIMENSIONS, DSL.`val`(JSONB.valueOf(Json.mapper.writeValueAsString(dimensions))))
        }
        val record = dsl.select(
            DSL.coalesce(DSL.sum(JOURNAL_LINE.DEBIT), BigDecimal.ZERO),
            DSL.coalesce(DSL.sum(JOURNAL_LINE.CREDIT), BigDecimal.ZERO),
            DSL.coalesce(DSL.sum(JOURNAL_LINE.BASE_DEBIT), BigDecimal.ZERO),
            DSL.coalesce(DSL.sum(JOURNAL_LINE.BASE_CREDIT), BigDecimal.ZERO),
        )
            .from(JOURNAL_LINE)
            .join(JOURNAL_ENTRY).on(JOURNAL_ENTRY.ID.eq(JOURNAL_LINE.ENTRY_ID))
            .join(GL_ACCOUNT).on(GL_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
            .where(conditions)
            .fetchOne()!!
        return LineBalance(record.value1(), record.value2(), record.value3(), record.value4())
    }

    override fun currencies(legalEntityId: UUID, accountCode: String, upTo: LocalDate): Set<String> = dsl.selectDistinct(JOURNAL_LINE.CURRENCY)
        .from(JOURNAL_LINE)
        .join(JOURNAL_ENTRY).on(JOURNAL_ENTRY.ID.eq(JOURNAL_LINE.ENTRY_ID))
        .join(GL_ACCOUNT).on(GL_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
        .where(JOURNAL_ENTRY.LEGAL_ENTITY_ID.eq(legalEntityId), GL_ACCOUNT.CODE.eq(accountCode), JOURNAL_ENTRY.POSTING_DATE.le(upTo))
        .fetchSet(JOURNAL_LINE.CURRENCY)

    private fun toView(r: JournalEntryRecord): JournalEntryView {
        val lines = dsl.select(JOURNAL_LINE.asterisk(), GL_ACCOUNT.CODE, GL_ACCOUNT.NAME)
            .from(JOURNAL_LINE)
            .join(GL_ACCOUNT).on(GL_ACCOUNT.ID.eq(JOURNAL_LINE.ACCOUNT_ID))
            .where(JOURNAL_LINE.ENTRY_ID.eq(r.id))
            .orderBy(JOURNAL_LINE.LINE_NO)
            .fetch { rec ->
                val l = rec.into(JOURNAL_LINE)
                JournalLineView(
                    l.lineNo,
                    rec.get(GL_ACCOUNT.CODE),
                    rec.get(GL_ACCOUNT.NAME),
                    l.debit,
                    l.credit,
                    l.currency,
                    l.fxRate,
                    l.baseDebit,
                    l.baseCredit,
                    Json.mapper.readValue(l.dimensions.data(), dimsType),
                    l.memo,
                )
            }
        return JournalEntryView(
            r.id,
            r.entryNumber,
            r.legalEntityId,
            r.period,
            r.postingDate,
            r.eventType,
            r.ruleCode,
            r.sourceType,
            r.sourceRef,
            r.eventId,
            r.description,
            r.currency,
            EntryStatus.valueOf(r.status),
            r.reversalOf,
            r.reversedBy,
            r.createdBy,
            r.createdAt,
            r.prevHash,
            r.hash,
            lines,
        )
    }
}

@Repository
class JooqPeriodRepository(private val dsl: DSLContext) : PeriodRepository {
    override fun find(legalEntityId: UUID, period: String): AccountingPeriodView? = dsl.selectFrom(ACCOUNTING_PERIOD)
        .where(ACCOUNTING_PERIOD.LEGAL_ENTITY_ID.eq(legalEntityId), ACCOUNTING_PERIOD.PERIOD.eq(period))
        .fetchOne { AccountingPeriodView(it.legalEntityId, it.period, PeriodStatus.valueOf(it.status), it.closedBy, it.closedAt) }

    override fun upsert(period: AccountingPeriodView) {
        dsl.insertInto(ACCOUNTING_PERIOD)
            .set(ACCOUNTING_PERIOD.LEGAL_ENTITY_ID, period.legalEntityId)
            .set(ACCOUNTING_PERIOD.PERIOD, period.period)
            .set(ACCOUNTING_PERIOD.STATUS, period.status.name)
            .set(ACCOUNTING_PERIOD.CLOSED_BY, period.closedBy)
            .set(ACCOUNTING_PERIOD.CLOSED_AT, period.closedAt)
            .onConflict(ACCOUNTING_PERIOD.LEGAL_ENTITY_ID, ACCOUNTING_PERIOD.PERIOD)
            .doUpdate()
            .set(ACCOUNTING_PERIOD.STATUS, period.status.name)
            .set(ACCOUNTING_PERIOD.CLOSED_BY, period.closedBy)
            .set(ACCOUNTING_PERIOD.CLOSED_AT, period.closedAt)
            .execute()
    }

    override fun periods(legalEntityId: UUID): List<AccountingPeriodView> = dsl.selectFrom(ACCOUNTING_PERIOD)
        .where(ACCOUNTING_PERIOD.LEGAL_ENTITY_ID.eq(legalEntityId))
        .orderBy(ACCOUNTING_PERIOD.PERIOD)
        .fetch { AccountingPeriodView(it.legalEntityId, it.period, PeriodStatus.valueOf(it.status), it.closedBy, it.closedAt) }
}

@Repository
class JooqCostLayerRepository(private val dsl: DSLContext) : CostLayerRepository {
    override fun open(legalEntityId: UUID, assetKey: String): List<CostLayer> = dsl.selectFrom(COST_LAYER)
        .where(COST_LAYER.LEGAL_ENTITY_ID.eq(legalEntityId), COST_LAYER.ASSET_KEY.eq(assetKey), COST_LAYER.REMAINING_QUANTITY.gt(BigDecimal.ZERO))
        .orderBy(COST_LAYER.ACQUIRED_ON, COST_LAYER.SEQ)
        .forUpdate()
        .fetch { CostLayer(it.id, it.legalEntityId, it.assetKey, it.acquiredOn, it.quantity, it.remainingQuantity, it.unitCost, it.currency, it.sourceRef) }

    override fun insert(layer: CostLayer) {
        dsl.insertInto(COST_LAYER)
            .set(COST_LAYER.ID, layer.id)
            .set(COST_LAYER.LEGAL_ENTITY_ID, layer.legalEntityId)
            .set(COST_LAYER.ASSET_KEY, layer.assetKey)
            .set(COST_LAYER.ACQUIRED_ON, layer.acquiredOn)
            .set(COST_LAYER.QUANTITY, layer.quantity)
            .set(COST_LAYER.REMAINING_QUANTITY, layer.remainingQuantity)
            .set(COST_LAYER.UNIT_COST, layer.unitCost)
            .set(COST_LAYER.CURRENCY, layer.currency)
            .set(COST_LAYER.SOURCE_REF, layer.sourceRef)
            .execute()
    }

    override fun reduce(layerId: UUID, quantity: BigDecimal) {
        dsl.update(COST_LAYER)
            .set(COST_LAYER.REMAINING_QUANTITY, COST_LAYER.REMAINING_QUANTITY.minus(quantity))
            .where(COST_LAYER.ID.eq(layerId))
            .execute()
    }

    override fun openAll(legalEntityId: UUID, assetKeyPrefix: String): List<CostLayer> = dsl.selectFrom(COST_LAYER)
        .where(COST_LAYER.LEGAL_ENTITY_ID.eq(legalEntityId), COST_LAYER.ASSET_KEY.startsWith(assetKeyPrefix), COST_LAYER.REMAINING_QUANTITY.gt(BigDecimal.ZERO))
        .orderBy(COST_LAYER.ACQUIRED_ON, COST_LAYER.SEQ)
        .fetch { CostLayer(it.id, it.legalEntityId, it.assetKey, it.acquiredOn, it.quantity, it.remainingQuantity, it.unitCost, it.currency, it.sourceRef) }
}

@Repository
class JooqGlExportRepository(private val dsl: DSLContext) : GlExportRepository {
    override fun insert(export: GlExportView) {
        dsl.insertInto(GL_EXPORT)
            .set(GL_EXPORT.ID, export.id)
            .set(GL_EXPORT.LEGAL_ENTITY_ID, export.legalEntityId)
            .set(GL_EXPORT.EXPORT_SEQ, export.exportSeq)
            .set(GL_EXPORT.PERIOD, export.period)
            .set(GL_EXPORT.EXPORTED_AT, export.exportedAt)
            .set(GL_EXPORT.EXPORTED_BY, export.exportedBy)
            .set(GL_EXPORT.ENTRY_COUNT, export.entryCount)
            .set(GL_EXPORT.CONTENT_HASH, export.contentHash)
            .set(GL_EXPORT.CONTENT, export.content)
            .set(GL_EXPORT.KIND, export.kind.name)
            .set(GL_EXPORT.FORMAT, export.format)
            .set(GL_EXPORT.STATUS, export.status.name)
            .execute()
    }

    override fun latest(legalEntityId: UUID, period: String): GlExportView? = dsl.selectFrom(GL_EXPORT)
        .where(GL_EXPORT.LEGAL_ENTITY_ID.eq(legalEntityId), GL_EXPORT.PERIOD.eq(period))
        .orderBy(GL_EXPORT.EXPORTED_AT.desc())
        .limit(1)
        .fetchOne(::toGlExport)

    override fun nextSeq(legalEntityId: UUID): Int = (
        dsl.select(DSL.max(GL_EXPORT.EXPORT_SEQ)).from(GL_EXPORT).where(GL_EXPORT.LEGAL_ENTITY_ID.eq(legalEntityId)).fetchOne(0, Int::class.java) ?: 0
    ) + 1
}

@Repository
class JooqCommitmentMemoRepository(private val dsl: DSLContext) : CommitmentMemoRepository {
    override fun insert(memo: CommitmentMemo, confirmedEventId: UUID): Boolean = dsl.insertInto(COMMITMENT_MEMO)
        .set(COMMITMENT_MEMO.ID, memo.id)
        .set(COMMITMENT_MEMO.LEGAL_ENTITY_ID, memo.legalEntityId)
        .set(COMMITMENT_MEMO.TRADE_ID, memo.tradeId)
        .set(COMMITMENT_MEMO.TRADE_NUMBER, memo.tradeNumber)
        .set(COMMITMENT_MEMO.TRADE_VERSION, memo.tradeVersion)
        .set(COMMITMENT_MEMO.SCHEDULE_ID, memo.scheduleId)
        .set(COMMITMENT_MEMO.SIDE, memo.side)
        .set(COMMITMENT_MEMO.PRODUCT_FAMILY, memo.productFamily)
        .set(COMMITMENT_MEMO.CURRENCY, memo.currency)
        .set(COMMITMENT_MEMO.QUANTITY, memo.quantity)
        .set(COMMITMENT_MEMO.AMOUNT, memo.amount)
        .set(COMMITMENT_MEMO.CONFIRMED_EVENT_ID, confirmedEventId)
        .onConflict(COMMITMENT_MEMO.CONFIRMED_EVENT_ID, COMMITMENT_MEMO.SCHEDULE_ID)
        .doNothing()
        .execute() == 1

    override fun openForSchedule(scheduleId: UUID): List<CommitmentMemo> = select(COMMITMENT_MEMO.SCHEDULE_ID.eq(scheduleId))

    override fun openForTrade(tradeId: UUID): List<CommitmentMemo> = select(COMMITMENT_MEMO.TRADE_ID.eq(tradeId))

    override fun move(eventId: UUID, memoId: UUID, kind: MemoMovementKind, amount: BigDecimal): Boolean = dsl.insertInto(COMMITMENT_MEMO_MOVEMENT)
        .set(COMMITMENT_MEMO_MOVEMENT.EVENT_ID, eventId)
        .set(COMMITMENT_MEMO_MOVEMENT.MEMO_ID, memoId)
        .set(COMMITMENT_MEMO_MOVEMENT.KIND, kind.name)
        .set(COMMITMENT_MEMO_MOVEMENT.AMOUNT, amount)
        .onConflictDoNothing()
        .execute() == 1

    private fun select(condition: Condition): List<CommitmentMemo> {
        val released = DSL.coalesce(
            DSL.field(
                DSL.select(
                    DSL.sum(COMMITMENT_MEMO_MOVEMENT.AMOUNT),
                ).from(COMMITMENT_MEMO_MOVEMENT).where(COMMITMENT_MEMO_MOVEMENT.MEMO_ID.eq(COMMITMENT_MEMO.ID)),
            ),
            BigDecimal.ZERO,
        )
        return dsl.select(COMMITMENT_MEMO.asterisk(), released.`as`("released"))
            .from(COMMITMENT_MEMO)
            .where(condition)
            .orderBy(COMMITMENT_MEMO.CREATED_AT, COMMITMENT_MEMO.ID)
            .fetch { r ->
                val m = r.into(COMMITMENT_MEMO)
                CommitmentMemo(
                    m.id,
                    m.legalEntityId,
                    m.tradeId,
                    m.tradeNumber,
                    m.tradeVersion,
                    m.scheduleId,
                    m.side,
                    m.productFamily,
                    m.currency,
                    m.quantity,
                    m.amount,
                    r.get("released", BigDecimal::class.java),
                )
            }
            .filter { it.open.signum() > 0 }
    }
}

@Repository
class JooqSeedRepository(private val dsl: DSLContext) : SeedRepository {
    override fun version(name: String): Int? = dsl.select(LEDGER_SEED.VERSION).from(LEDGER_SEED).where(LEDGER_SEED.NAME.eq(name)).fetchOne(LEDGER_SEED.VERSION)

    override fun record(name: String, version: Int) {
        dsl.insertInto(LEDGER_SEED)
            .set(LEDGER_SEED.NAME, name)
            .set(LEDGER_SEED.VERSION, version)
            .onConflict(LEDGER_SEED.NAME)
            .doUpdate()
            .set(LEDGER_SEED.VERSION, version)
            .set(LEDGER_SEED.APPLIED_AT, DSL.currentInstant())
            .execute()
    }
}

internal fun toGlExport(r: com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.GlExportRecord) = GlExportView(
    r.id,
    r.legalEntityId,
    r.exportSeq,
    r.period,
    r.exportedAt,
    r.exportedBy,
    r.entryCount,
    r.contentHash,
    r.content,
    com.pianonurmi.etrm.ledger.api.GlExportKind.valueOf(r.kind),
    r.format,
    com.pianonurmi.etrm.ledger.api.GlExportStatus.valueOf(r.status),
    r.statusReason,
    r.acknowledgementRef,
    r.statusChangedBy,
    r.statusChangedAt,
)
