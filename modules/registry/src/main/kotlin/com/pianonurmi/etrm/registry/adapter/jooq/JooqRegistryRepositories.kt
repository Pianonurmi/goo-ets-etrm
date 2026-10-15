package com.pianonurmi.etrm.registry.adapter.jooq

import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.RegistryInstruction.REGISTRY_INSTRUCTION
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.StatementFormat.STATEMENT_FORMAT
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.TransferRoute.TRANSFER_ROUTE
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.records.RegistryInstructionRecord
import com.pianonurmi.etrm.registry.adapter.jooq.generated.tables.records.StatementFormatRecord
import com.pianonurmi.etrm.registry.api.AssetKind
import com.pianonurmi.etrm.registry.api.InstructionPage
import com.pianonurmi.etrm.registry.api.InstructionSearch
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.StatementFormatView
import com.pianonurmi.etrm.registry.api.StatementKind
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.domain.InstructionCursor
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import com.pianonurmi.etrm.registry.domain.StatementFormatRepository
import com.pianonurmi.etrm.registry.domain.TransferRouteRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.util.UUID

private const val MAX_SEARCH = 500

@Repository
class JooqRegistryInstructionRepository(private val dsl: DSLContext) : RegistryInstructionRepository {
    override fun eraseBeneficiary(beneficiaryId: UUID): Int = dsl.update(REGISTRY_INSTRUCTION)
        .set(REGISTRY_INSTRUCTION.CANCELLATION, DSL.field("{0} - 'beneficiaryName' - 'beneficiaryId'", JSONB::class.java, REGISTRY_INSTRUCTION.CANCELLATION))
        .set(
            REGISTRY_INSTRUCTION.REQUEST_PAYLOAD,
            DSL.field("{0} #- '{cancellation,beneficiaryName}' #- '{cancellation,beneficiaryId}'", JSONB::class.java, REGISTRY_INSTRUCTION.REQUEST_PAYLOAD),
        )
        .where(DSL.field("{0} ->> 'beneficiaryRef'", String::class.java, REGISTRY_INSTRUCTION.CANCELLATION).eq(beneficiaryId.toString()))
        .execute()

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
            .set(REGISTRY_INSTRUCTION.CERT_NO_START, instruction.range?.start)
            .set(REGISTRY_INSTRUCTION.CERT_NO_END, instruction.range?.end)
            .set(REGISTRY_INSTRUCTION.QUANTITY, instruction.quantity)
            .set(REGISTRY_INSTRUCTION.ASSET_KIND, instruction.assetKind.name)
            .set(REGISTRY_INSTRUCTION.UNIT, instruction.unit)
            .set(REGISTRY_INSTRUCTION.PRODUCT_CODE, instruction.productCode)
            .set(REGISTRY_INSTRUCTION.HOLDING_REF, instruction.holdingRef)
            .set(REGISTRY_INSTRUCTION.MOVEMENT_TYPE, instruction.movementType?.name)
            .set(REGISTRY_INSTRUCTION.UNIT_PRICE, instruction.unitPrice)
            .set(REGISTRY_INSTRUCTION.PRICE_CURRENCY, instruction.priceCurrency)
            .set(REGISTRY_INSTRUCTION.COMPLIANCE_YEAR, instruction.complianceYear)
            .set(REGISTRY_INSTRUCTION.EXPECTED_EFFECTIVE_AT, instruction.expectedEffectiveAt)
            .set(REGISTRY_INSTRUCTION.CANCELLATION, instruction.cancellation?.let { JSONB.jsonb(Json.mapper.writeValueAsString(it)) })
            .set(REGISTRY_INSTRUCTION.PLAN_REF, instruction.planRef)
            .set(REGISTRY_INSTRUCTION.LINE_REF, instruction.lineRef)
            .set(REGISTRY_INSTRUCTION.OBLIGATION_REF, instruction.obligationRef)
            .set(REGISTRY_INSTRUCTION.STATUS, instruction.status.name)
            .set(REGISTRY_INSTRUCTION.CHANNEL, instruction.channel)
            .set(REGISTRY_INSTRUCTION.NEXT_ATTEMPT_AT, instruction.nextAttemptAt)
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
        .set(REGISTRY_INSTRUCTION.NEXT_ATTEMPT_AT, instruction.nextAttemptAt)
        .set(REGISTRY_INSTRUCTION.APPROVED_BY, instruction.approvedBy)
        .set(REGISTRY_INSTRUCTION.APPROVED_AT, instruction.approvedAt)
        // A lease only lives while DISPATCHING.
        .set(
            REGISTRY_INSTRUCTION.LEASE_UNTIL,
            if (instruction.status == InstructionStatus.DISPATCHING) REGISTRY_INSTRUCTION.LEASE_UNTIL else DSL.inline(null, REGISTRY_INSTRUCTION.LEASE_UNTIL),
        )
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

    override fun search(search: InstructionSearch): InstructionPage {
        val limit = search.limit.coerceIn(1, MAX_SEARCH)
        val after = search.cursor?.let(InstructionCursor::decode)
        val conditions = listOfNotNull(
            search.statuses?.takeIf { it.isNotEmpty() }?.let { REGISTRY_INSTRUCTION.STATUS.`in`(it.map(InstructionStatus::name)) },
            search.registryCode?.let { REGISTRY_INSTRUCTION.REGISTRY_CODE.eq(it.uppercase()) },
            search.holderEntityId?.let { REGISTRY_INSTRUCTION.HOLDER_ENTITY_ID.eq(it) },
            search.holderEntityIds?.let { REGISTRY_INSTRUCTION.HOLDER_ENTITY_ID.`in`(it) },
            search.assetKind?.let { REGISTRY_INSTRUCTION.ASSET_KIND.eq(it.name) },
            search.from?.let { REGISTRY_INSTRUCTION.CREATED_AT.ge(it) },
            search.to?.let { REGISTRY_INSTRUCTION.CREATED_AT.lt(it) },
            after?.let { DSL.row(REGISTRY_INSTRUCTION.CREATED_AT, REGISTRY_INSTRUCTION.ID).lt(it.createdAt, it.id) },
        )
        val rows = dsl.selectFrom(REGISTRY_INSTRUCTION)
            .where(DSL.and(conditions))
            .orderBy(REGISTRY_INSTRUCTION.CREATED_AT.desc(), REGISTRY_INSTRUCTION.ID.desc())
            .limit(limit + 1)
            .fetch(::toView)
        val items = rows.take(limit)
        val next = if (rows.size > limit) items.last().let { InstructionCursor(it.createdAt, it.id).encode() } else null
        return InstructionPage(items, next)
    }

    override fun forPlan(planRef: UUID): List<RegistryInstructionView> =
        dsl.selectFrom(REGISTRY_INSTRUCTION).where(REGISTRY_INSTRUCTION.PLAN_REF.eq(planRef)).orderBy(REGISTRY_INSTRUCTION.CREATED_AT).fetch(::toView)

    override fun byExternalRef(registryCode: String, externalRef: String): RegistryInstructionView? = dsl.selectFrom(REGISTRY_INSTRUCTION)
        .where(REGISTRY_INSTRUCTION.REGISTRY_CODE.eq(registryCode), REGISTRY_INSTRUCTION.EXTERNAL_REF.eq(externalRef))
        .fetchOne()?.let(::toView)

    override fun claimDue(channels: Set<String>, now: Instant, leaseUntil: Instant, limit: Int): List<UUID> {
        if (channels.isEmpty()) return emptyList()
        val due = dsl.select(REGISTRY_INSTRUCTION.ID).from(REGISTRY_INSTRUCTION)
            .where(
                REGISTRY_INSTRUCTION.STATUS.eq(InstructionStatus.PENDING.name),
                REGISTRY_INSTRUCTION.CHANNEL.`in`(channels),
                REGISTRY_INSTRUCTION.NEXT_ATTEMPT_AT.isNull.or(REGISTRY_INSTRUCTION.NEXT_ATTEMPT_AT.le(now)),
            )
            .orderBy(REGISTRY_INSTRUCTION.NEXT_ATTEMPT_AT.asc().nullsFirst(), REGISTRY_INSTRUCTION.CREATED_AT)
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch(REGISTRY_INSTRUCTION.ID)
        if (due.isEmpty()) return emptyList()
        return dsl.update(REGISTRY_INSTRUCTION)
            .set(REGISTRY_INSTRUCTION.STATUS, InstructionStatus.DISPATCHING.name)
            .set(REGISTRY_INSTRUCTION.LEASE_UNTIL, leaseUntil)
            .set(REGISTRY_INSTRUCTION.VERSION, REGISTRY_INSTRUCTION.VERSION.plus(1))
            .where(REGISTRY_INSTRUCTION.ID.`in`(due))
            .returning(REGISTRY_INSTRUCTION.ID)
            .fetch(REGISTRY_INSTRUCTION.ID)
    }

    override fun expiredLeases(now: Instant, limit: Int): List<UUID> = dsl.select(REGISTRY_INSTRUCTION.ID).from(REGISTRY_INSTRUCTION)
        .where(REGISTRY_INSTRUCTION.STATUS.eq(InstructionStatus.DISPATCHING.name), REGISTRY_INSTRUCTION.LEASE_UNTIL.lt(now))
        .limit(limit)
        .fetch(REGISTRY_INSTRUCTION.ID)

    override fun inStatus(status: InstructionStatus, limit: Int): List<RegistryInstructionView> = dsl.selectFrom(REGISTRY_INSTRUCTION)
        .where(REGISTRY_INSTRUCTION.STATUS.eq(status.name))
        .orderBy(REGISTRY_INSTRUCTION.CREATED_AT)
        .limit(limit)
        .fetch(::toView)

    override fun overdueCandidates(sentBefore: Instant, limit: Int): List<RegistryInstructionView> = dsl.selectFrom(REGISTRY_INSTRUCTION)
        .where(
            REGISTRY_INSTRUCTION.STATUS.`in`(InstructionStatus.entries.filter { it.awaitingRegistry }.map { it.name }),
            REGISTRY_INSTRUCTION.OVERDUE_NOTIFIED_AT.isNull,
            DSL.coalesce(REGISTRY_INSTRUCTION.SENT_AT, REGISTRY_INSTRUCTION.CREATED_AT).lt(sentBefore),
        )
        .orderBy(REGISTRY_INSTRUCTION.CREATED_AT)
        .limit(limit)
        .fetch(::toView)

    override fun markOverdueNotified(id: UUID, at: Instant) {
        dsl.update(REGISTRY_INSTRUCTION).set(REGISTRY_INSTRUCTION.OVERDUE_NOTIFIED_AT, at).where(REGISTRY_INSTRUCTION.ID.eq(id)).execute()
    }

    override fun openCounts(): Map<InstructionStatus, Int> = dsl.select(REGISTRY_INSTRUCTION.STATUS, DSL.count())
        .from(REGISTRY_INSTRUCTION)
        .where(REGISTRY_INSTRUCTION.STATUS.`in`(InstructionStatus.entries.filter { !it.terminal }.map { it.name }))
        .groupBy(REGISTRY_INSTRUCTION.STATUS)
        .fetch()
        .associate { InstructionStatus.valueOf(it.value1()) to it.value2() }

    override fun oldestPendingSince(): Instant? = dsl.select(DSL.min(REGISTRY_INSTRUCTION.CREATED_AT)).from(REGISTRY_INSTRUCTION)
        .where(REGISTRY_INSTRUCTION.STATUS.`in`(InstructionStatus.PENDING.name, InstructionStatus.DISPATCHING.name))
        .fetchOne(0, Instant::class.java)

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
        range = r.certNoStart?.let { start -> r.certNoEnd?.let { end -> CertificateRange(start, end) } },
        quantity = r.quantity,
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
        channel = r.channel,
        nextAttemptAt = r.nextAttemptAt,
        approvedBy = r.approvedBy,
        approvedAt = r.approvedAt,
        assetKind = AssetKind.valueOf(r.assetKind),
        productCode = r.productCode,
        holdingRef = r.holdingRef,
        movementType = r.movementType?.let(AllowanceMovementType::valueOf),
        unitPrice = r.unitPrice,
        priceCurrency = r.priceCurrency,
        complianceYear = r.complianceYear,
        expectedEffectiveAt = r.expectedEffectiveAt,
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

@Repository
class JooqStatementFormatRepository(private val dsl: DSLContext) : StatementFormatRepository {
    private val stringMap = object : TypeReference<Map<String, String>>() {}

    override fun find(registryCode: String, profileCode: String, kind: StatementKind): StatementFormatView? = dsl.selectFrom(STATEMENT_FORMAT)
        .where(STATEMENT_FORMAT.REGISTRY_CODE.eq(registryCode), STATEMENT_FORMAT.PROFILE_CODE.eq(profileCode), STATEMENT_FORMAT.KIND.eq(kind.name))
        .fetchOne()?.let(::toView)

    override fun forRegistry(registryCode: String, kind: StatementKind): List<StatementFormatView> = dsl.selectFrom(STATEMENT_FORMAT)
        .where(STATEMENT_FORMAT.REGISTRY_CODE.eq(registryCode), STATEMENT_FORMAT.KIND.eq(kind.name), STATEMENT_FORMAT.ACTIVE.isTrue)
        .orderBy(STATEMENT_FORMAT.PROFILE_CODE)
        .fetch(::toView)

    override fun all(registryCode: String?): List<StatementFormatView> = dsl.selectFrom(STATEMENT_FORMAT)
        .where(registryCode?.let { STATEMENT_FORMAT.REGISTRY_CODE.eq(it) } ?: DSL.noCondition())
        .orderBy(STATEMENT_FORMAT.REGISTRY_CODE, STATEMENT_FORMAT.KIND, STATEMENT_FORMAT.PROFILE_CODE)
        .fetch(::toView)

    override fun upsert(format: StatementFormatView) {
        dsl.insertInto(STATEMENT_FORMAT)
            .set(STATEMENT_FORMAT.ID, format.id)
            .set(STATEMENT_FORMAT.REGISTRY_CODE, format.registryCode)
            .set(STATEMENT_FORMAT.PROFILE_CODE, format.profileCode)
            .set(STATEMENT_FORMAT.KIND, format.kind.name)
            .set(STATEMENT_FORMAT.DELIMITER, format.delimiter.toString())
            .set(STATEMENT_FORMAT.SKIP_LINES, format.skipLines)
            .set(STATEMENT_FORMAT.DATE_FORMAT, format.dateFormat)
            .set(STATEMENT_FORMAT.COLUMN_MAPPING, JSONB.jsonb(Json.mapper.writeValueAsString(format.columnMapping)))
            .set(STATEMENT_FORMAT.CONSTANTS, JSONB.jsonb(Json.mapper.writeValueAsString(format.constants)))
            .set(STATEMENT_FORMAT.ACTIVE, format.active)
            .set(STATEMENT_FORMAT.VERSION, format.version)
            .set(STATEMENT_FORMAT.UPDATED_BY, format.updatedBy)
            .set(STATEMENT_FORMAT.UPDATED_AT, format.updatedAt)
            .onConflict(STATEMENT_FORMAT.REGISTRY_CODE, STATEMENT_FORMAT.PROFILE_CODE, STATEMENT_FORMAT.KIND)
            .doUpdate()
            .set(STATEMENT_FORMAT.DELIMITER, format.delimiter.toString())
            .set(STATEMENT_FORMAT.SKIP_LINES, format.skipLines)
            .set(STATEMENT_FORMAT.DATE_FORMAT, format.dateFormat)
            .set(STATEMENT_FORMAT.COLUMN_MAPPING, JSONB.jsonb(Json.mapper.writeValueAsString(format.columnMapping)))
            .set(STATEMENT_FORMAT.CONSTANTS, JSONB.jsonb(Json.mapper.writeValueAsString(format.constants)))
            .set(STATEMENT_FORMAT.ACTIVE, format.active)
            .set(STATEMENT_FORMAT.VERSION, STATEMENT_FORMAT.VERSION.plus(1))
            .set(STATEMENT_FORMAT.UPDATED_BY, format.updatedBy)
            .set(STATEMENT_FORMAT.UPDATED_AT, format.updatedAt)
            .execute()
    }

    private fun toView(r: StatementFormatRecord) = StatementFormatView(
        id = r.id,
        registryCode = r.registryCode,
        profileCode = r.profileCode,
        kind = StatementKind.valueOf(r.kind),
        delimiter = r.delimiter.first(),
        skipLines = r.skipLines,
        dateFormat = r.dateFormat,
        columnMapping = Json.mapper.readValue(r.columnMapping.data(), stringMap),
        constants = Json.mapper.readValue(r.constants.data(), stringMap),
        active = r.active,
        version = r.version,
        updatedBy = r.updatedBy,
        updatedAt = r.updatedAt,
    )
}
