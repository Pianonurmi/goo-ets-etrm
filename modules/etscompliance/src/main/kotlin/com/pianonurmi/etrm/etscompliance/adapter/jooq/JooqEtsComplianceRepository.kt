package com.pianonurmi.etrm.etscompliance.adapter.jooq

import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.ComplianceDeadlineRule.COMPLIANCE_DEADLINE_RULE
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.DeadlineAlert.DEADLINE_ALERT
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.EmissionRecord.EMISSION_RECORD
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.FreeAllocation.FREE_ALLOCATION
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.Installation.INSTALLATION
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.SurrenderRecord.SURRENDER_RECORD
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.SurrenderRequest.SURRENDER_REQUEST
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.records.EmissionRecordRecord
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.records.InstallationRecord
import com.pianonurmi.etrm.etscompliance.adapter.jooq.generated.tables.records.SurrenderRequestRecord
import com.pianonurmi.etrm.etscompliance.api.DeadlineKind
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionRecordView
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.FreeAllocationStatus
import com.pianonurmi.etrm.etscompliance.api.InstallationKind
import com.pianonurmi.etrm.etscompliance.api.InstallationView
import com.pianonurmi.etrm.etscompliance.api.SetFreeAllocation
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestStatus
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestView
import com.pianonurmi.etrm.etscompliance.domain.DeadlineRule
import com.pianonurmi.etrm.etscompliance.domain.EtsComplianceRepository
import com.pianonurmi.etrm.etscompliance.domain.SurrenderRecord
import com.pianonurmi.etrm.platform.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.util.UUID

@Repository
class JooqEtsComplianceRepository(private val dsl: DSLContext) : EtsComplianceRepository {
    private val listType = object : TypeReference<List<String>>() {}

    override fun installations(legalEntityId: UUID?, activeOnly: Boolean): List<InstallationView> = dsl.selectFrom(INSTALLATION)
        .where(legalEntityId?.let { INSTALLATION.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition())
        .and(if (activeOnly) INSTALLATION.ACTIVE.isTrue else DSL.noCondition())
        .orderBy(INSTALLATION.NAME)
        .fetch(::toInstallation)

    override fun installation(id: UUID): InstallationView? = dsl.selectFrom(INSTALLATION).where(INSTALLATION.ID.eq(id)).fetchOne()?.let(::toInstallation)

    override fun installationByAccount(registryAccountId: UUID): InstallationView? =
        dsl.selectFrom(INSTALLATION).where(INSTALLATION.REGISTRY_ACCOUNT_ID.eq(registryAccountId)).fetchOne()?.let(::toInstallation)

    override fun insertInstallation(installation: InstallationView) {
        dsl.insertInto(INSTALLATION)
            .set(INSTALLATION.ID, installation.id)
            .set(INSTALLATION.LEGAL_ENTITY_ID, installation.legalEntityId)
            .set(INSTALLATION.KIND, installation.kind.name)
            .set(INSTALLATION.EUTL_ID, installation.eutlId)
            .set(INSTALLATION.PERMIT_ID, installation.permitId)
            .set(INSTALLATION.NAME, installation.name)
            .set(INSTALLATION.COUNTRY, installation.country)
            .set(INSTALLATION.ACTIVITY_CODE, installation.activityCode)
            .set(INSTALLATION.MONITORING_PLAN_REF, installation.monitoringPlanRef)
            .set(INSTALLATION.MONITORING_PLAN_VERSION, installation.monitoringPlanVersion)
            .set(INSTALLATION.REGISTRY_ACCOUNT_ID, installation.registryAccountId)
            .set(INSTALLATION.ELIGIBLE_PRODUCTS, JSONB.jsonb(Json.mapper.writeValueAsString(installation.eligibleProducts)))
            .set(INSTALLATION.VALID_FROM, installation.validFrom)
            .set(INSTALLATION.VALID_TO, installation.validTo)
            .set(INSTALLATION.ACTIVE, installation.active)
            .execute()
    }

    override fun emissions(installationId: UUID, complianceYear: Int?): List<EmissionRecordView> = dsl.selectFrom(EMISSION_RECORD)
        .where(EMISSION_RECORD.INSTALLATION_ID.eq(installationId))
        .and(complianceYear?.let { EMISSION_RECORD.COMPLIANCE_YEAR.eq(it) } ?: DSL.noCondition())
        .orderBy(EMISSION_RECORD.COMPLIANCE_YEAR, EMISSION_RECORD.RECORDED_AT)
        .fetch(::toEmission)

    override fun emission(id: UUID, lock: Boolean): EmissionRecordView? = dsl.selectFrom(EMISSION_RECORD)
        .where(EMISSION_RECORD.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toEmission)

    override fun insertEmission(record: EmissionRecordView) {
        dsl.insertInto(EMISSION_RECORD)
            .set(EMISSION_RECORD.ID, record.id)
            .set(EMISSION_RECORD.INSTALLATION_ID, record.installationId)
            .set(EMISSION_RECORD.COMPLIANCE_YEAR, record.complianceYear)
            .set(EMISSION_RECORD.KIND, record.kind.name)
            .set(EMISSION_RECORD.PERIOD_MONTH, record.periodMonth)
            .set(EMISSION_RECORD.QUANTITY_TCO2E, record.quantityTco2e)
            .set(EMISSION_RECORD.STATUS, record.status.name)
            .set(EMISSION_RECORD.VERIFIER, record.verifier)
            .set(EMISSION_RECORD.VERIFIED_ON, record.verifiedOn)
            .set(EMISSION_RECORD.DOCUMENT_REF, record.documentRef)
            .set(EMISSION_RECORD.NOTE, record.note)
            .set(EMISSION_RECORD.RECORDED_BY, record.recordedBy)
            .set(EMISSION_RECORD.RECORDED_AT, record.recordedAt)
            .set(EMISSION_RECORD.APPROVED_BY, record.approvedBy)
            .set(EMISSION_RECORD.APPROVED_AT, record.approvedAt)
            .execute()
    }

    override fun setEmissionStatus(id: UUID, status: EmissionStatus, approvedBy: String?, at: Instant?) {
        val update = dsl.update(EMISSION_RECORD).set(EMISSION_RECORD.STATUS, status.name)
        if (approvedBy != null) update.set(EMISSION_RECORD.APPROVED_BY, approvedBy).set(EMISSION_RECORD.APPROVED_AT, at)
        update.where(EMISSION_RECORD.ID.eq(id)).execute()
    }

    override fun freeAllocation(installationId: UUID, year: Int): SetFreeAllocation? = dsl.selectFrom(FREE_ALLOCATION)
        .where(FREE_ALLOCATION.INSTALLATION_ID.eq(installationId), FREE_ALLOCATION.ALLOCATION_YEAR.eq(year))
        .fetchOne { SetFreeAllocation(it.allocatedQty, it.adjustedQty, FreeAllocationStatus.valueOf(it.status), it.note) }

    override fun upsertFreeAllocation(installationId: UUID, year: Int, allocation: SetFreeAllocation, actor: String) {
        dsl.insertInto(FREE_ALLOCATION)
            .set(FREE_ALLOCATION.INSTALLATION_ID, installationId)
            .set(FREE_ALLOCATION.ALLOCATION_YEAR, year)
            .set(FREE_ALLOCATION.ALLOCATED_QTY, allocation.allocated)
            .set(FREE_ALLOCATION.ADJUSTED_QTY, allocation.adjusted)
            .set(FREE_ALLOCATION.STATUS, allocation.status.name)
            .set(FREE_ALLOCATION.NOTE, allocation.note)
            .set(FREE_ALLOCATION.UPDATED_BY, actor)
            .onConflict(FREE_ALLOCATION.INSTALLATION_ID, FREE_ALLOCATION.ALLOCATION_YEAR)
            .doUpdate()
            .set(FREE_ALLOCATION.ALLOCATED_QTY, allocation.allocated)
            .set(FREE_ALLOCATION.ADJUSTED_QTY, allocation.adjusted)
            .set(FREE_ALLOCATION.STATUS, allocation.status.name)
            .set(FREE_ALLOCATION.NOTE, allocation.note)
            .set(FREE_ALLOCATION.UPDATED_BY, actor)
            .set(FREE_ALLOCATION.UPDATED_AT, DSL.currentInstant())
            .execute()
    }

    override fun surrendered(installationId: UUID, complianceYear: Int): Long = dsl.select(DSL.sum(SURRENDER_RECORD.QUANTITY))
        .from(SURRENDER_RECORD)
        .where(SURRENDER_RECORD.INSTALLATION_ID.eq(installationId), SURRENDER_RECORD.COMPLIANCE_YEAR.eq(complianceYear))
        .fetchOne(0, Long::class.java) ?: 0L

    override fun insertSurrender(record: SurrenderRecord): Boolean = dsl.insertInto(SURRENDER_RECORD)
        .set(SURRENDER_RECORD.MOVEMENT_SEQ, record.movementSeq)
        .set(SURRENDER_RECORD.INSTALLATION_ID, record.installationId)
        .set(SURRENDER_RECORD.COMPLIANCE_YEAR, record.complianceYear)
        .set(SURRENDER_RECORD.PRODUCT_CODE, record.productCode)
        .set(SURRENDER_RECORD.QUANTITY, record.quantity)
        .set(SURRENDER_RECORD.ATTRIBUTION, if (record.explicit) "EXPLICIT" else "EARLIEST_OPEN_YEAR")
        .set(SURRENDER_RECORD.SURRENDERED_ON, record.surrenderedOn)
        .onConflictDoNothing()
        .execute() == 1

    override fun insertRequest(request: SurrenderRequestView, idempotencyKey: String) {
        dsl.insertInto(SURRENDER_REQUEST)
            .set(SURRENDER_REQUEST.ID, request.id)
            .set(SURRENDER_REQUEST.INSTALLATION_ID, request.installationId)
            .set(SURRENDER_REQUEST.COMPLIANCE_YEAR, request.complianceYear)
            .set(SURRENDER_REQUEST.PRODUCT_CODE, request.productCode)
            .set(SURRENDER_REQUEST.QUANTITY, request.quantity)
            .set(SURRENDER_REQUEST.INSTRUCTION_ID, request.instructionId)
            .set(SURRENDER_REQUEST.IDEMPOTENCY_KEY, idempotencyKey)
            .set(SURRENDER_REQUEST.STATUS, request.status.name)
            .set(SURRENDER_REQUEST.REQUESTED_BY, request.requestedBy)
            .set(SURRENDER_REQUEST.REQUESTED_AT, request.requestedAt)
            .execute()
    }

    override fun requestByKey(idempotencyKey: String): SurrenderRequestView? =
        dsl.selectFrom(SURRENDER_REQUEST).where(SURRENDER_REQUEST.IDEMPOTENCY_KEY.eq(idempotencyKey)).fetchOne()?.let(::toRequest)

    override fun requestCount(installationId: UUID, complianceYear: Int): Int =
        dsl.fetchCount(SURRENDER_REQUEST, SURRENDER_REQUEST.INSTALLATION_ID.eq(installationId).and(SURRENDER_REQUEST.COMPLIANCE_YEAR.eq(complianceYear)))

    override fun requests(installationId: UUID): List<SurrenderRequestView> = dsl.selectFrom(SURRENDER_REQUEST)
        .where(SURRENDER_REQUEST.INSTALLATION_ID.eq(installationId))
        .orderBy(SURRENDER_REQUEST.REQUESTED_AT)
        .fetch(::toRequest)

    override fun setRequestStatus(instructionId: UUID, status: SurrenderRequestStatus): Boolean = dsl.update(SURRENDER_REQUEST)
        .set(SURRENDER_REQUEST.STATUS, status.name)
        .where(SURRENDER_REQUEST.INSTRUCTION_ID.eq(instructionId))
        .execute() == 1

    override fun deadlineRules(): List<DeadlineRule> = dsl.selectFrom(COMPLIANCE_DEADLINE_RULE).fetch {
        DeadlineRule(DeadlineKind.valueOf(it.code), it.effectiveFromYear, it.yearOffset, it.deadlineMonth, it.deadlineDay)
    }

    override fun recordAlert(installationId: UUID, kind: DeadlineKind, complianceYear: Int, daysBefore: Int, taskSubject: UUID): Boolean =
        dsl.insertInto(DEADLINE_ALERT)
            .set(DEADLINE_ALERT.INSTALLATION_ID, installationId)
            .set(DEADLINE_ALERT.CODE, kind.name)
            .set(DEADLINE_ALERT.COMPLIANCE_YEAR, complianceYear)
            .set(DEADLINE_ALERT.DAYS_BEFORE, daysBefore)
            .set(DEADLINE_ALERT.TASK_SUBJECT, taskSubject)
            .onConflictDoNothing()
            .execute() == 1

    override fun alertSubjects(installationId: UUID, kind: DeadlineKind, complianceYear: Int): List<UUID> = dsl.select(DEADLINE_ALERT.TASK_SUBJECT)
        .from(DEADLINE_ALERT)
        .where(DEADLINE_ALERT.INSTALLATION_ID.eq(installationId), DEADLINE_ALERT.CODE.eq(kind.name), DEADLINE_ALERT.COMPLIANCE_YEAR.eq(complianceYear))
        .fetch(DEADLINE_ALERT.TASK_SUBJECT)

    private fun toInstallation(r: InstallationRecord) = InstallationView(
        r.id,
        r.legalEntityId,
        InstallationKind.valueOf(r.kind),
        r.eutlId,
        r.permitId,
        r.name,
        r.country,
        r.activityCode,
        r.monitoringPlanRef,
        r.monitoringPlanVersion,
        r.registryAccountId,
        Json.mapper.readValue(r.eligibleProducts.data(), listType),
        r.validFrom,
        r.validTo,
        r.active,
        r.version,
    )

    private fun toEmission(r: EmissionRecordRecord) = EmissionRecordView(
        r.id,
        r.installationId,
        r.complianceYear,
        EmissionKind.valueOf(r.kind),
        r.periodMonth,
        r.quantityTco2e,
        EmissionStatus.valueOf(r.status),
        r.verifier,
        r.verifiedOn,
        r.documentRef,
        r.note,
        r.recordedBy,
        r.recordedAt,
        r.approvedBy,
        r.approvedAt,
    )

    private fun toRequest(r: SurrenderRequestRecord) = SurrenderRequestView(
        r.id,
        r.installationId,
        r.complianceYear,
        r.productCode,
        r.quantity,
        r.instructionId,
        SurrenderRequestStatus.valueOf(r.status),
        r.requestedBy,
        r.requestedAt,
    )
}
