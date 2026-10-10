package com.pianonurmi.etrm.risk.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.CounterpartyExposure.COUNTERPARTY_EXPOSURE
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.CreditSupport.CREDIT_SUPPORT
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.LimitBreach.LIMIT_BREACH
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.LimitUtilisation.LIMIT_UTILISATION
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.PfeAddon.PFE_ADDON
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.RiskLimit.RISK_LIMIT
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.RiskSeed.RISK_SEED
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.records.CreditSupportRecord
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.records.LimitBreachRecord
import com.pianonurmi.etrm.risk.adapter.jooq.generated.tables.records.RiskLimitRecord
import com.pianonurmi.etrm.risk.api.BreachStatus
import com.pianonurmi.etrm.risk.api.CounterpartyExposureView
import com.pianonurmi.etrm.risk.api.CreditSupportKind
import com.pianonurmi.etrm.risk.api.CreditSupportStatus
import com.pianonurmi.etrm.risk.api.CreditSupportView
import com.pianonurmi.etrm.risk.api.LimitBreachView
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.LimitUtilisationView
import com.pianonurmi.etrm.risk.api.PfeAddOnView
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.UtilisationStatus
import com.pianonurmi.etrm.risk.domain.RiskRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JooqRiskRepository(private val dsl: DSLContext) : RiskRepository {
    override fun insertLimit(limit: RiskLimitView) {
        dsl.insertInto(RISK_LIMIT)
            .set(RISK_LIMIT.ID, limit.id)
            .set(RISK_LIMIT.LEGAL_ENTITY_ID, limit.legalEntityId)
            .set(RISK_LIMIT.LIMIT_TYPE, limit.type.name)
            .set(RISK_LIMIT.SCOPE, JSONB.valueOf(Json.mapper.writeValueAsString(limit.scope)))
            .set(RISK_LIMIT.AMOUNT, limit.amount)
            .set(RISK_LIMIT.CURRENCY, limit.currency)
            .set(RISK_LIMIT.WARNING_PCT, limit.warningPct)
            .set(RISK_LIMIT.VALID_FROM, limit.validFrom)
            .set(RISK_LIMIT.VALID_TO, limit.validTo)
            .set(RISK_LIMIT.STATUS, limit.status.name)
            .set(RISK_LIMIT.REPLACES, limit.replaces)
            .set(RISK_LIMIT.REASON, limit.reason)
            .set(RISK_LIMIT.REQUESTED_BY, limit.requestedBy)
            .set(RISK_LIMIT.REQUESTED_AT, limit.requestedAt)
            .set(RISK_LIMIT.APPROVED_BY, limit.approvedBy)
            .set(RISK_LIMIT.APPROVED_AT, limit.approvedAt)
            .execute()
    }

    override fun limit(id: UUID, lock: Boolean): RiskLimitView? = dsl.selectFrom(RISK_LIMIT)
        .where(RISK_LIMIT.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toLimit)

    override fun limits(legalEntityId: UUID?, includeGroupWide: Boolean, statuses: Set<LimitStatus>?): List<RiskLimitView> = dsl.selectFrom(RISK_LIMIT)
        .where(
            when {
                legalEntityId == null -> DSL.noCondition()
                includeGroupWide -> RISK_LIMIT.LEGAL_ENTITY_ID.eq(legalEntityId).or(RISK_LIMIT.LEGAL_ENTITY_ID.isNull)
                else -> RISK_LIMIT.LEGAL_ENTITY_ID.eq(legalEntityId)
            },
            statuses?.let { RISK_LIMIT.STATUS.`in`(it.map(LimitStatus::name)) } ?: DSL.noCondition(),
        )
        .orderBy(RISK_LIMIT.LIMIT_TYPE, RISK_LIMIT.REQUESTED_AT)
        .fetch(::toLimit)

    override fun setLimitStatus(id: UUID, status: LimitStatus, approvedBy: String?, at: Instant?) {
        dsl.update(RISK_LIMIT)
            .set(RISK_LIMIT.STATUS, status.name)
            .apply { if (approvedBy != null) set(RISK_LIMIT.APPROVED_BY, approvedBy).set(RISK_LIMIT.APPROVED_AT, at) }
            .where(RISK_LIMIT.ID.eq(id))
            .execute()
    }

    override fun insertCreditSupport(support: CreditSupportView) {
        dsl.insertInto(CREDIT_SUPPORT)
            .set(CREDIT_SUPPORT.ID, support.id)
            .set(CREDIT_SUPPORT.LEGAL_ENTITY_ID, support.legalEntityId)
            .set(CREDIT_SUPPORT.COUNTERPARTY_ID, support.counterpartyId)
            .set(CREDIT_SUPPORT.KIND, support.kind.name)
            .set(CREDIT_SUPPORT.AMOUNT, support.amount)
            .set(CREDIT_SUPPORT.CURRENCY, support.currency)
            .set(CREDIT_SUPPORT.VALID_FROM, support.validFrom)
            .set(CREDIT_SUPPORT.VALID_TO, support.validTo)
            .set(CREDIT_SUPPORT.REFERENCE, support.reference)
            .set(CREDIT_SUPPORT.STATUS, support.status.name)
            .set(CREDIT_SUPPORT.REQUESTED_BY, support.requestedBy)
            .set(CREDIT_SUPPORT.REQUESTED_AT, support.requestedAt)
            .execute()
    }

    override fun creditSupport(id: UUID, lock: Boolean): CreditSupportView? = dsl.selectFrom(CREDIT_SUPPORT)
        .where(CREDIT_SUPPORT.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toSupport)

    override fun creditSupportFor(counterpartyId: UUID?): List<CreditSupportView> = dsl.selectFrom(CREDIT_SUPPORT)
        .where(counterpartyId?.let { CREDIT_SUPPORT.COUNTERPARTY_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(CREDIT_SUPPORT.REQUESTED_AT)
        .fetch(::toSupport)

    override fun setCreditSupportStatus(id: UUID, status: CreditSupportStatus, approvedBy: String?, at: Instant?) {
        dsl.update(CREDIT_SUPPORT)
            .set(CREDIT_SUPPORT.STATUS, status.name)
            .apply { if (approvedBy != null) set(CREDIT_SUPPORT.APPROVED_BY, approvedBy).set(CREDIT_SUPPORT.APPROVED_AT, at) }
            .where(CREDIT_SUPPORT.ID.eq(id))
            .execute()
    }

    override fun pfeAddOns(): List<PfeAddOnView> = dsl.selectFrom(PFE_ADDON).fetch {
        PfeAddOnView(ProductFamily.valueOf(it.productFamily), it.maxTenorMonths, it.pct, it.calibrated)
    }

    override fun upsertExposure(exposure: CounterpartyExposureView) {
        dsl.insertInto(COUNTERPARTY_EXPOSURE)
            .set(COUNTERPARTY_EXPOSURE.LEGAL_ENTITY_ID, exposure.legalEntityId)
            .set(COUNTERPARTY_EXPOSURE.COUNTERPARTY_ID, exposure.counterpartyId)
            .set(COUNTERPARTY_EXPOSURE.CURRENCY, exposure.currency)
            .set(COUNTERPARTY_EXPOSURE.MTM, exposure.mtm)
            .set(COUNTERPARTY_EXPOSURE.RECEIVABLES, exposure.receivables)
            .set(COUNTERPARTY_EXPOSURE.PAYABLES_NETTED, exposure.payablesNetted)
            .set(COUNTERPARTY_EXPOSURE.PFE, exposure.pfe)
            .set(COUNTERPARTY_EXPOSURE.COLLATERAL, exposure.collateral)
            .set(COUNTERPARTY_EXPOSURE.EXPOSURE, exposure.exposure)
            .set(COUNTERPARTY_EXPOSURE.VALUATION_RUN_ID, exposure.valuationRunId)
            .set(COUNTERPARTY_EXPOSURE.MEASURED_AT, exposure.measuredAt)
            .onConflict(COUNTERPARTY_EXPOSURE.LEGAL_ENTITY_ID, COUNTERPARTY_EXPOSURE.COUNTERPARTY_ID)
            .doUpdate()
            .set(COUNTERPARTY_EXPOSURE.CURRENCY, exposure.currency)
            .set(COUNTERPARTY_EXPOSURE.MTM, exposure.mtm)
            .set(COUNTERPARTY_EXPOSURE.RECEIVABLES, exposure.receivables)
            .set(COUNTERPARTY_EXPOSURE.PAYABLES_NETTED, exposure.payablesNetted)
            .set(COUNTERPARTY_EXPOSURE.PFE, exposure.pfe)
            .set(COUNTERPARTY_EXPOSURE.COLLATERAL, exposure.collateral)
            .set(COUNTERPARTY_EXPOSURE.EXPOSURE, exposure.exposure)
            .set(COUNTERPARTY_EXPOSURE.VALUATION_RUN_ID, exposure.valuationRunId)
            .set(COUNTERPARTY_EXPOSURE.MEASURED_AT, exposure.measuredAt)
            .execute()
    }

    override fun exposures(legalEntityId: UUID?): List<CounterpartyExposureView> = dsl.selectFrom(COUNTERPARTY_EXPOSURE)
        .where(legalEntityId?.let { COUNTERPARTY_EXPOSURE.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(COUNTERPARTY_EXPOSURE.EXPOSURE.desc())
        .fetch {
            CounterpartyExposureView(
                it.legalEntityId,
                it.counterpartyId,
                it.currency,
                it.mtm,
                it.receivables,
                it.payablesNetted,
                it.pfe,
                it.collateral,
                it.exposure,
                it.valuationRunId,
                it.measuredAt,
            )
        }

    override fun upsertUtilisation(utilisation: LimitUtilisationView) {
        dsl.insertInto(LIMIT_UTILISATION)
            .set(LIMIT_UTILISATION.LIMIT_ID, utilisation.limitId)
            .set(LIMIT_UTILISATION.VALUE, utilisation.value)
            .set(LIMIT_UTILISATION.UTILISATION, utilisation.utilisation)
            .set(LIMIT_UTILISATION.STATUS, utilisation.status.name)
            .set(LIMIT_UTILISATION.DETAIL, utilisation.detail)
            .set(LIMIT_UTILISATION.MEASURED_AT, utilisation.measuredAt)
            .onConflict(LIMIT_UTILISATION.LIMIT_ID)
            .doUpdate()
            .set(LIMIT_UTILISATION.VALUE, utilisation.value)
            .set(LIMIT_UTILISATION.UTILISATION, utilisation.utilisation)
            .set(LIMIT_UTILISATION.STATUS, utilisation.status.name)
            .set(LIMIT_UTILISATION.DETAIL, utilisation.detail)
            .set(LIMIT_UTILISATION.MEASURED_AT, utilisation.measuredAt)
            .execute()
    }

    override fun utilisation(limitId: UUID): LimitUtilisationView? = dsl.selectFrom(LIMIT_UTILISATION).where(LIMIT_UTILISATION.LIMIT_ID.eq(limitId)).fetchOne {
        LimitUtilisationView(it.limitId, it.value, it.utilisation, UtilisationStatus.valueOf(it.status), it.detail, it.measuredAt)
    }

    override fun openBreach(limitId: UUID): LimitBreachView? = dsl.selectFrom(LIMIT_BREACH)
        .where(LIMIT_BREACH.LIMIT_ID.eq(limitId), LIMIT_BREACH.STATUS.eq(BreachStatus.OPEN.name))
        .forUpdate()
        .fetchOne()?.let(::toBreach)

    override fun insertBreach(breach: LimitBreachView) {
        dsl.insertInto(LIMIT_BREACH)
            .set(LIMIT_BREACH.ID, breach.id)
            .set(LIMIT_BREACH.LIMIT_ID, breach.limitId)
            .set(LIMIT_BREACH.OPENED_AT, breach.openedAt)
            .set(LIMIT_BREACH.FIRST_VALUE, breach.firstValue)
            .set(LIMIT_BREACH.PEAK_VALUE, breach.peakValue)
            .set(LIMIT_BREACH.CLOSED_AT, breach.closedAt)
            .set(LIMIT_BREACH.STATUS, breach.status.name)
            .execute()
    }

    override fun updateBreach(breach: LimitBreachView) {
        dsl.update(LIMIT_BREACH)
            .set(LIMIT_BREACH.PEAK_VALUE, breach.peakValue)
            .set(LIMIT_BREACH.CLOSED_AT, breach.closedAt)
            .set(LIMIT_BREACH.STATUS, breach.status.name)
            .where(LIMIT_BREACH.ID.eq(breach.id))
            .execute()
    }

    override fun breaches(limitIds: Set<UUID>, openOnly: Boolean): List<LimitBreachView> = dsl.selectFrom(LIMIT_BREACH)
        .where(LIMIT_BREACH.LIMIT_ID.`in`(limitIds), if (openOnly) LIMIT_BREACH.STATUS.eq(BreachStatus.OPEN.name) else DSL.noCondition())
        .orderBy(LIMIT_BREACH.OPENED_AT.desc())
        .fetch(::toBreach)

    override fun seeded(name: String): Boolean = dsl.fetchExists(RISK_SEED, RISK_SEED.NAME.eq(name))

    override fun markSeeded(name: String) {
        dsl.insertInto(RISK_SEED).set(RISK_SEED.NAME, name).onConflictDoNothing().execute()
    }

    private fun toLimit(r: RiskLimitRecord) = RiskLimitView(
        r.id,
        r.legalEntityId,
        LimitType.valueOf(r.limitType),
        Json.mapper.readValue(r.scope.data(), LimitScope::class.java),
        r.amount,
        r.currency,
        r.warningPct,
        r.validFrom,
        r.validTo,
        LimitStatus.valueOf(r.status),
        r.replaces,
        r.reason,
        r.requestedBy,
        r.requestedAt,
        r.approvedBy,
        r.approvedAt,
    )

    private fun toSupport(r: CreditSupportRecord) = CreditSupportView(
        r.id,
        r.legalEntityId,
        r.counterpartyId,
        CreditSupportKind.valueOf(r.kind),
        r.amount,
        r.currency,
        r.validFrom,
        r.validTo,
        r.reference,
        CreditSupportStatus.valueOf(r.status),
        r.requestedBy,
        r.requestedAt,
        r.approvedBy,
        r.approvedAt,
    )

    private fun toBreach(r: LimitBreachRecord) =
        LimitBreachView(r.id, r.limitId, r.openedAt, r.firstValue, r.peakValue, r.closedAt, BreachStatus.valueOf(r.status))
}
