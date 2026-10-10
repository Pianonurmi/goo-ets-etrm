package com.pianonurmi.etrm.registry.adapter.jooq

import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.RegistryInstruction.REGISTRY_INSTRUCTION
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.TransferRoute.TRANSFER_ROUTE
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.records.RegistryInstructionRecord
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import com.pianonurmi.etrm.registry.domain.TransferRouteRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.util.UUID

@Repository
class JooqRegistryInstructionRepository(private val dsl: DSLContext) : RegistryInstructionRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun find(id: UUID): RegistryInstructionView? = dsl.selectFrom(REGISTRY_INSTRUCTION).where(REGISTRY_INSTRUCTION.ID.eq(id)).fetchOne()?.let(::toView)

    override fun lock(id: UUID): RegistryInstructionView? =
        dsl.selectFrom(REGISTRY_INSTRUCTION).where(REGISTRY_INSTRUCTION.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun byKey(key: String): RegistryInstructionView? =
        dsl.selectFrom(REGISTRY_INSTRUCTION).where(REGISTRY_INSTRUCTION.INSTRUCTION_KEY.eq(key)).fetchOne()?.let(::toView)

    override fun insert(instruction: RegistryInstructionView, requestPayload: Map<String, Any?>) {
        dsl.insertInto(REGISTRY_INSTRUCTION)
            .set(REGISTRY_INSTRUCTION.ID, instruction.id)
            .set(REGISTRY_INSTRUCTION.INSTRUCTION_KEY, instruction.instructionKey)
            .set(REGISTRY_INSTRUCTION.INSTRUCTION_TYPE, instruction.type.name)
            .set(REGISTRY_INSTRUCTION.REGISTRY_CODE, instruction.registryCode)
            .set(REGISTRY_INSTRUCTION.HOLDER_ENTITY_ID, instruction.holderEntityId)
            .set(REGISTRY_INSTRUCTION.FROM_ACCOUNT_ID, instruction.fromAccountId)
            .set(REGISTRY_INSTRUCTION.TO_REGISTRY_CODE, instruction.toRegistryCode)
            .set(REGISTRY_INSTRUCTION.TO_ACCOUNT_REF, instruction.toAccountRef)
            .set(REGISTRY_INSTRUCTION.LOT_ID, instruction.lotId)
            .set(REGISTRY_INSTRUCTION.CERT_NO_START, instruction.range.start)
            .set(REGISTRY_INSTRUCTION.CERT_NO_END, instruction.range.end)
            .set(REGISTRY_INSTRUCTION.QUANTITY_MWH, instruction.quantityMwh)
            .set(REGISTRY_INSTRUCTION.CANCELLATION, instruction.cancellation?.let { JSONB.jsonb(Json.mapper.writeValueAsString(it)) })
            .set(REGISTRY_INSTRUCTION.PLAN_REF, instruction.planRef)
            .set(REGISTRY_INSTRUCTION.LINE_REF, instruction.lineRef)
            .set(REGISTRY_INSTRUCTION.OBLIGATION_REF, instruction.obligationRef)
            .set(REGISTRY_INSTRUCTION.STATUS, instruction.status.name)
            .set(REGISTRY_INSTRUCTION.REQUEST_PAYLOAD, JSONB.jsonb(Json.mapper.writeValueAsString(requestPayload)))
            .set(REGISTRY_INSTRUCTION.SUBMITTED_BY, instruction.submittedBy)
            .set(REGISTRY_INSTRUCTION.CREATED_AT, instruction.createdAt)
            .set(REGISTRY_INSTRUCTION.VERSION, instruction.version)
            .execute()
    }

    override fun update(instruction: RegistryInstructionView, responsePayload: Map<String, Any?>?): Boolean = dsl.update(REGISTRY_INSTRUCTION)
        .set(REGISTRY_INSTRUCTION.STATUS, instruction.status.name)
        .set(REGISTRY_INSTRUCTION.EXTERNAL_REF, instruction.externalRef)
        .set(REGISTRY_INSTRUCTION.ATTEMPTS, instruction.attempts)
        .set(REGISTRY_INSTRUCTION.LAST_ERROR, instruction.lastError)
        .set(REGISTRY_INSTRUCTION.CONFIRMED_BY, instruction.confirmedBy)
        .set(REGISTRY_INSTRUCTION.SENT_AT, instruction.sentAt)
        .set(REGISTRY_INSTRUCTION.CONFIRMED_AT, instruction.confirmedAt)
        // Keep the last registry response when a transition carries none (e.g. operator confirmation).
        .set(
            REGISTRY_INSTRUCTION.RESPONSE_PAYLOAD,
            responsePayload?.let { DSL.value(JSONB.jsonb(Json.mapper.writeValueAsString(it))) } ?: REGISTRY_INSTRUCTION.RESPONSE_PAYLOAD,
        )
        .set(REGISTRY_INSTRUCTION.VERSION, instruction.version)
        .where(REGISTRY_INSTRUCTION.ID.eq(instruction.id), REGISTRY_INSTRUCTION.VERSION.eq(instruction.version - 1))
        .execute() == 1

    override fun open(registryCode: String?): List<RegistryInstructionView> = dsl.selectFrom(REGISTRY_INSTRUCTION)
        .where(REGISTRY_INSTRUCTION.STATUS.`in`(InstructionStatus.entries.filter { !it.terminal }.map { it.name }))
        .and(registryCode?.let { REGISTRY_INSTRUCTION.REGISTRY_CODE.eq(it) } ?: DSL.noCondition())
        .orderBy(REGISTRY_INSTRUCTION.CREATED_AT)
        .fetch(::toView)

    override fun forPlan(planRef: UUID): List<RegistryInstructionView> =
        dsl.selectFrom(REGISTRY_INSTRUCTION).where(REGISTRY_INSTRUCTION.PLAN_REF.eq(planRef)).orderBy(REGISTRY_INSTRUCTION.CREATED_AT).fetch(::toView)

    private fun toView(r: RegistryInstructionRecord) = RegistryInstructionView(
        id = r.id,
        instructionKey = r.instructionKey,
        type = InstructionType.valueOf(r.instructionType),
        registryCode = r.registryCode,
        holderEntityId = r.holderEntityId,
        fromAccountId = r.fromAccountId,
        toRegistryCode = r.toRegistryCode,
        toAccountRef = r.toAccountRef,
        lotId = r.lotId,
        range = CertificateRange(r.certNoStart, r.certNoEnd),
        quantityMwh = r.quantityMwh,
        cancellation = r.cancellation?.let { Json.mapper.readValue(it.data(), CancellationDetails::class.java) },
        planRef = r.planRef,
        lineRef = r.lineRef,
        obligationRef = r.obligationRef,
        status = InstructionStatus.valueOf(r.status),
        externalRef = r.externalRef,
        attempts = r.attempts,
        lastError = r.lastError,
        submittedBy = r.submittedBy,
        confirmedBy = r.confirmedBy,
        createdAt = r.createdAt,
        sentAt = r.sentAt,
        confirmedAt = r.confirmedAt,
        version = r.version,
    )
}

@Repository
class JooqTransferRouteRepository(private val dsl: DSLContext) : TransferRouteRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun overrides(): List<TransferRoute> = dsl.selectFrom(TRANSFER_ROUTE).fetch { r ->
        TransferRoute(r.fromRegistryCode, r.toRegistryCode, r.feasible, r.feePerMwh, r.expectedDays, Json.mapper.readValue(r.restrictions.data(), mapType))
    }

    override fun upsert(route: TransferRoute) {
        dsl.insertInto(TRANSFER_ROUTE)
            .set(TRANSFER_ROUTE.FROM_REGISTRY_CODE, route.fromRegistryCode)
            .set(TRANSFER_ROUTE.TO_REGISTRY_CODE, route.toRegistryCode)
            .set(TRANSFER_ROUTE.FEASIBLE, route.feasible)
            .set(TRANSFER_ROUTE.FEE_PER_MWH, route.feePerMwh)
            .set(TRANSFER_ROUTE.EXPECTED_DAYS, route.expectedDays)
            .set(TRANSFER_ROUTE.RESTRICTIONS, JSONB.jsonb(Json.mapper.writeValueAsString(route.restrictions)))
            .onConflict(TRANSFER_ROUTE.FROM_REGISTRY_CODE, TRANSFER_ROUTE.TO_REGISTRY_CODE)
            .doUpdate()
            .set(TRANSFER_ROUTE.FEASIBLE, route.feasible)
            .set(TRANSFER_ROUTE.FEE_PER_MWH, route.feePerMwh)
            .set(TRANSFER_ROUTE.EXPECTED_DAYS, route.expectedDays)
            .set(TRANSFER_ROUTE.RESTRICTIONS, JSONB.jsonb(Json.mapper.writeValueAsString(route.restrictions)))
            .execute()
    }
}
