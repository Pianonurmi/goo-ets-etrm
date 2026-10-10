package com.pianonurmi.etrm.ledger.adapter.jooq

import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.AccountingPolicyChange.ACCOUNTING_POLICY_CHANGE
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.MtmPolicy.MTM_POLICY
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.NrvPolicy.NRV_POLICY
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.PeriodEndRun.PERIOD_END_RUN
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.AccountingPolicyChangeRecord
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.PeriodEndRunRecord
import com.pianonurmi.etrm.ledger.api.MtmPolicyView
import com.pianonurmi.etrm.ledger.api.MtmTreatment
import com.pianonurmi.etrm.ledger.api.NrvPolicyView
import com.pianonurmi.etrm.ledger.api.PeriodEndComponent
import com.pianonurmi.etrm.ledger.api.PeriodEndRunView
import com.pianonurmi.etrm.ledger.api.PolicyChangeStatus
import com.pianonurmi.etrm.ledger.api.PolicyChangeView
import com.pianonurmi.etrm.ledger.api.PolicyKind
import com.pianonurmi.etrm.ledger.api.RequestPolicyChange
import com.pianonurmi.etrm.ledger.domain.PeriodEndRepository
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.referencedata.api.BookType
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.util.UUID

@Repository
class JooqPeriodEndRepository(private val dsl: DSLContext) : PeriodEndRepository {
    private val componentsType = object : TypeReference<List<PeriodEndComponent>>() {}
    private val idsType = object : TypeReference<List<UUID>>() {}
    private val stringsType = object : TypeReference<List<String>>() {}

    override fun mtmPolicies(): List<MtmPolicyView> = dsl.selectFrom(MTM_POLICY).orderBy(MTM_POLICY.BOOK_TYPE).fetch {
        MtmPolicyView(it.id, it.legalEntityId, BookType.valueOf(it.bookType), MtmTreatment.valueOf(it.treatment), it.signedOffBy, it.signedOffAt)
    }

    override fun upsertMtmPolicy(policy: MtmPolicyView) {
        val existing = dsl.select(MTM_POLICY.ID).from(MTM_POLICY)
            .where(entity(MTM_POLICY.LEGAL_ENTITY_ID, policy.legalEntityId), MTM_POLICY.BOOK_TYPE.eq(policy.bookType.name))
            .fetchOne(MTM_POLICY.ID)
        if (existing == null) {
            dsl.insertInto(MTM_POLICY)
                .set(MTM_POLICY.ID, policy.id)
                .set(MTM_POLICY.LEGAL_ENTITY_ID, policy.legalEntityId)
                .set(MTM_POLICY.BOOK_TYPE, policy.bookType.name)
                .set(MTM_POLICY.TREATMENT, policy.treatment.name)
                .set(MTM_POLICY.SIGNED_OFF_BY, policy.signedOffBy)
                .set(MTM_POLICY.SIGNED_OFF_AT, policy.signedOffAt)
                .execute()
        } else {
            dsl.update(MTM_POLICY)
                .set(MTM_POLICY.TREATMENT, policy.treatment.name)
                .set(MTM_POLICY.SIGNED_OFF_BY, policy.signedOffBy)
                .set(MTM_POLICY.SIGNED_OFF_AT, policy.signedOffAt)
                .set(MTM_POLICY.UPDATED_AT, Instant.now())
                .where(MTM_POLICY.ID.eq(existing))
                .execute()
        }
    }

    override fun nrvPolicies(): List<NrvPolicyView> = dsl.selectFrom(NRV_POLICY).fetch {
        NrvPolicyView(
            it.id,
            it.legalEntityId,
            it.gooCostToSellPerMwh,
            it.allowanceCostToSell,
            it.expiryDays,
            it.currency,
            it.signedOffBy,
            it.signedOffAt,
        )
    }

    override fun upsertNrvPolicy(policy: NrvPolicyView) {
        val existing = dsl.select(NRV_POLICY.ID).from(NRV_POLICY).where(entity(NRV_POLICY.LEGAL_ENTITY_ID, policy.legalEntityId)).fetchOne(NRV_POLICY.ID)
        if (existing == null) {
            dsl.insertInto(NRV_POLICY)
                .set(NRV_POLICY.ID, policy.id)
                .set(NRV_POLICY.LEGAL_ENTITY_ID, policy.legalEntityId)
                .set(NRV_POLICY.GOO_COST_TO_SELL_PER_MWH, policy.gooCostToSellPerMwh)
                .set(NRV_POLICY.ALLOWANCE_COST_TO_SELL, policy.allowanceCostToSell)
                .set(NRV_POLICY.EXPIRY_DAYS, policy.expiryDays)
                .set(NRV_POLICY.CURRENCY, policy.currency)
                .set(NRV_POLICY.SIGNED_OFF_BY, policy.signedOffBy)
                .set(NRV_POLICY.SIGNED_OFF_AT, policy.signedOffAt)
                .execute()
        } else {
            dsl.update(NRV_POLICY)
                .set(NRV_POLICY.GOO_COST_TO_SELL_PER_MWH, policy.gooCostToSellPerMwh)
                .set(NRV_POLICY.ALLOWANCE_COST_TO_SELL, policy.allowanceCostToSell)
                .set(NRV_POLICY.EXPIRY_DAYS, policy.expiryDays)
                .set(NRV_POLICY.CURRENCY, policy.currency)
                .set(NRV_POLICY.SIGNED_OFF_BY, policy.signedOffBy)
                .set(NRV_POLICY.SIGNED_OFF_AT, policy.signedOffAt)
                .set(NRV_POLICY.UPDATED_AT, Instant.now())
                .where(NRV_POLICY.ID.eq(existing))
                .execute()
        }
    }

    override fun insertChange(change: PolicyChangeView) {
        dsl.insertInto(ACCOUNTING_POLICY_CHANGE)
            .set(ACCOUNTING_POLICY_CHANGE.ID, change.id)
            .set(ACCOUNTING_POLICY_CHANGE.LEGAL_ENTITY_ID, change.legalEntityId)
            .set(ACCOUNTING_POLICY_CHANGE.KIND, change.kind.name)
            .set(ACCOUNTING_POLICY_CHANGE.PAYLOAD, JSONB.valueOf(Json.mapper.writeValueAsString(change.change)))
            .set(ACCOUNTING_POLICY_CHANGE.REASON, change.change.reason)
            .set(ACCOUNTING_POLICY_CHANGE.STATUS, change.status.name)
            .set(ACCOUNTING_POLICY_CHANGE.REQUESTED_BY, change.requestedBy)
            .set(ACCOUNTING_POLICY_CHANGE.REQUESTED_AT, change.requestedAt)
            .execute()
    }

    override fun change(id: UUID, lock: Boolean): PolicyChangeView? = dsl.selectFrom(ACCOUNTING_POLICY_CHANGE)
        .where(ACCOUNTING_POLICY_CHANGE.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toChange)

    override fun changes(status: PolicyChangeStatus?): List<PolicyChangeView> = dsl.selectFrom(ACCOUNTING_POLICY_CHANGE)
        .where(status?.let { ACCOUNTING_POLICY_CHANGE.STATUS.eq(it.name) } ?: DSL.noCondition())
        .orderBy(ACCOUNTING_POLICY_CHANGE.REQUESTED_AT.desc())
        .fetch(::toChange)

    override fun decideChange(id: UUID, status: PolicyChangeStatus, by: String, at: Instant) {
        dsl.update(ACCOUNTING_POLICY_CHANGE)
            .set(ACCOUNTING_POLICY_CHANGE.STATUS, status.name)
            .set(ACCOUNTING_POLICY_CHANGE.DECIDED_BY, by)
            .set(ACCOUNTING_POLICY_CHANGE.DECIDED_AT, at)
            .where(ACCOUNTING_POLICY_CHANGE.ID.eq(id))
            .execute()
    }

    override fun nextRunSeq(legalEntityId: UUID, period: String): Int = (
        dsl.select(DSL.max(PERIOD_END_RUN.SEQ)).from(PERIOD_END_RUN)
            .where(PERIOD_END_RUN.LEGAL_ENTITY_ID.eq(legalEntityId), PERIOD_END_RUN.PERIOD.eq(period))
            .fetchOne(0, Int::class.java) ?: 0
    ) + 1

    override fun insertRun(run: PeriodEndRunView) {
        dsl.insertInto(PERIOD_END_RUN)
            .set(PERIOD_END_RUN.ID, run.id)
            .set(PERIOD_END_RUN.LEGAL_ENTITY_ID, run.legalEntityId)
            .set(PERIOD_END_RUN.PERIOD, run.period)
            .set(PERIOD_END_RUN.SEQ, run.seq)
            .set(PERIOD_END_RUN.VALUATION_RUN_ID, run.valuationRunId)
            .set(PERIOD_END_RUN.COMPONENTS, JSONB.valueOf(Json.mapper.writeValueAsString(run.components)))
            .set(PERIOD_END_RUN.ENTRY_IDS, JSONB.valueOf(Json.mapper.writeValueAsString(run.entryIds)))
            .set(PERIOD_END_RUN.WARNINGS, JSONB.valueOf(Json.mapper.writeValueAsString(run.warnings)))
            .set(PERIOD_END_RUN.RUN_BY, run.runBy)
            .set(PERIOD_END_RUN.RUN_AT, run.runAt)
            .execute()
    }

    override fun runs(legalEntityId: UUID, period: String?): List<PeriodEndRunView> = dsl.selectFrom(PERIOD_END_RUN)
        .where(PERIOD_END_RUN.LEGAL_ENTITY_ID.eq(legalEntityId), period?.let { PERIOD_END_RUN.PERIOD.eq(it) } ?: DSL.noCondition())
        .orderBy(PERIOD_END_RUN.PERIOD, PERIOD_END_RUN.SEQ)
        .fetch(::toRun)

    private fun entity(field: org.jooq.TableField<*, UUID?>, legalEntityId: UUID?) = if (legalEntityId == null) field.isNull else field.eq(legalEntityId)

    private fun toChange(r: AccountingPolicyChangeRecord) = PolicyChangeView(
        r.id,
        r.legalEntityId,
        PolicyKind.valueOf(r.kind),
        Json.mapper.readValue(r.payload.data(), RequestPolicyChange::class.java),
        PolicyChangeStatus.valueOf(r.status),
        r.requestedBy,
        r.requestedAt,
        r.decidedBy,
        r.decidedAt,
    )

    private fun toRun(r: PeriodEndRunRecord) = PeriodEndRunView(
        r.id,
        r.legalEntityId,
        r.period,
        r.seq,
        r.valuationRunId,
        Json.mapper.readValue(r.components.data(), componentsType),
        Json.mapper.readValue(r.entryIds.data(), idsType),
        Json.mapper.readValue(r.warnings.data(), stringsType),
        r.runBy,
        r.runAt,
    )
}
