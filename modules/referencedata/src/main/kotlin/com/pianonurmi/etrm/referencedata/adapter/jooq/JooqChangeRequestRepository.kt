package com.pianonurmi.etrm.referencedata.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.ChangeRequest.CHANGE_REQUEST
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.ChangeRequestRecord
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestStatus
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.domain.ChangeRequestRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.util.UUID

@Repository
class JooqChangeRequestRepository(private val dsl: DSLContext) : ChangeRequestRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun insert(request: ChangeRequestView) {
        dsl.insertInto(CHANGE_REQUEST)
            .set(CHANGE_REQUEST.ID, request.id)
            .set(CHANGE_REQUEST.ENTITY_TYPE, request.entityType.name)
            .set(CHANGE_REQUEST.ENTITY_KEY, request.entityKey)
            .set(CHANGE_REQUEST.LEGAL_ENTITY_ID, request.legalEntityId)
            .set(CHANGE_REQUEST.CHANGE, JSONB.jsonb(Json.mapper.writeValueAsString(request.change)))
            .set(CHANGE_REQUEST.EXPECTED_VERSION, request.expectedVersion)
            .set(CHANGE_REQUEST.SUMMARY, request.summary)
            .set(CHANGE_REQUEST.REASON, request.reason)
            .set(CHANGE_REQUEST.STATUS, request.status.name)
            .set(CHANGE_REQUEST.REQUESTED_BY, request.requestedBy)
            .set(CHANGE_REQUEST.REQUESTED_AT, request.requestedAt)
            .set(CHANGE_REQUEST.VERSION, request.version)
            .execute()
    }

    override fun find(id: UUID): ChangeRequestView? = dsl.selectFrom(CHANGE_REQUEST).where(CHANGE_REQUEST.ID.eq(id)).fetchOne()?.let(::toView)

    override fun lock(id: UUID): ChangeRequestView? = dsl.selectFrom(CHANGE_REQUEST).where(CHANGE_REQUEST.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun pendingFor(entityType: ChangeEntityType, entityKey: String): ChangeRequestView? = dsl.selectFrom(CHANGE_REQUEST)
        .where(
            CHANGE_REQUEST.ENTITY_TYPE.eq(entityType.name),
            CHANGE_REQUEST.ENTITY_KEY.eq(entityKey),
            CHANGE_REQUEST.STATUS.eq(ChangeRequestStatus.PENDING.name),
        )
        .fetchOne()?.let(::toView)

    override fun list(status: ChangeRequestStatus?, entityType: ChangeEntityType?): List<ChangeRequestView> = dsl.selectFrom(CHANGE_REQUEST)
        .where(
            status?.let { CHANGE_REQUEST.STATUS.eq(it.name) } ?: DSL.noCondition(),
            entityType?.let { CHANGE_REQUEST.ENTITY_TYPE.eq(it.name) } ?: DSL.noCondition(),
        )
        .orderBy(CHANGE_REQUEST.REQUESTED_AT.desc())
        .limit(500)
        .fetch(::toView)

    override fun update(request: ChangeRequestView): Boolean = dsl.update(CHANGE_REQUEST)
        .set(CHANGE_REQUEST.STATUS, request.status.name)
        .set(CHANGE_REQUEST.DECIDED_BY, request.decidedBy)
        .set(CHANGE_REQUEST.DECIDED_AT, request.decidedAt)
        .set(CHANGE_REQUEST.DECISION_NOTE, request.decisionNote)
        .set(CHANGE_REQUEST.VERSION, request.version)
        .where(CHANGE_REQUEST.ID.eq(request.id), CHANGE_REQUEST.VERSION.eq(request.version - 1))
        .execute() == 1

    private fun toView(r: ChangeRequestRecord) = ChangeRequestView(
        id = r.id,
        entityType = ChangeEntityType.valueOf(r.entityType),
        entityKey = r.entityKey,
        legalEntityId = r.legalEntityId,
        change = Json.mapper.readValue(r.change.data(), mapType),
        expectedVersion = r.expectedVersion,
        summary = r.summary,
        reason = r.reason,
        status = ChangeRequestStatus.valueOf(r.status),
        requestedBy = r.requestedBy,
        requestedAt = r.requestedAt,
        decidedBy = r.decidedBy,
        decidedAt = r.decidedAt,
        decisionNote = r.decisionNote,
        version = r.version,
    )
}
