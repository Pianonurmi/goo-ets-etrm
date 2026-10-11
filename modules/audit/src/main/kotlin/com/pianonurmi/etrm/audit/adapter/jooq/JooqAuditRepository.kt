package com.pianonurmi.etrm.audit.adapter.jooq

import com.pianonurmi.etrm.audit.adapter.jooq.generated.tables.AuditEvent.AUDIT_EVENT
import com.pianonurmi.etrm.audit.adapter.jooq.generated.tables.records.AuditEventRecord
import com.pianonurmi.etrm.audit.api.AuditEventView
import com.pianonurmi.etrm.audit.api.AuditFilter
import com.pianonurmi.etrm.audit.application.AuditRepository
import com.pianonurmi.etrm.platform.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.util.UUID

@Repository
class JooqAuditRepository(private val dsl: DSLContext) : AuditRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun append(event: AuditEventView): AuditEventView = dsl.insertInto(AUDIT_EVENT)
        .set(AUDIT_EVENT.EVENT_ID, event.eventId)
        .set(AUDIT_EVENT.OCCURRED_AT, event.occurredAt)
        .set(AUDIT_EVENT.ACTOR, event.actor)
        .set(AUDIT_EVENT.EVENT_TYPE, event.eventType)
        .set(AUDIT_EVENT.AGGREGATE_TYPE, event.aggregateType)
        .set(AUDIT_EVENT.AGGREGATE_REF, event.aggregateRef)
        .set(AUDIT_EVENT.CORRELATION_ID, event.correlationId)
        .set(AUDIT_EVENT.PAYLOAD, JSONB.jsonb(Json.mapper.writeValueAsString(event.payload)))
        .onConflict(AUDIT_EVENT.EVENT_ID, AUDIT_EVENT.OCCURRED_AT)
        .doNothing()
        .returning()
        .fetchOne()
        ?.let(::toView)
        ?: byEventId(event.eventId)!!

    override fun query(filter: AuditFilter): List<AuditEventView> = dsl.selectFrom(AUDIT_EVENT)
        .where(
            DSL.and(
                listOfNotNull(
                    filter.eventType?.let { AUDIT_EVENT.EVENT_TYPE.eq(it) },
                    filter.actor?.let { AUDIT_EVENT.ACTOR.eq(it) },
                    filter.aggregateType?.let { AUDIT_EVENT.AGGREGATE_TYPE.eq(it) },
                    filter.aggregateRef?.let { AUDIT_EVENT.AGGREGATE_REF.eq(it) },
                    filter.from?.let { AUDIT_EVENT.OCCURRED_AT.ge(it) },
                    filter.to?.let { AUDIT_EVENT.OCCURRED_AT.lt(it) },
                    filter.afterSeq?.let { AUDIT_EVENT.SEQ.gt(it) },
                ),
            ),
        )
        .orderBy(AUDIT_EVENT.SEQ.desc())
        .limit(filter.limit)
        .fetch(::toView)

    override fun byEventId(eventId: UUID): AuditEventView? = dsl.selectFrom(AUDIT_EVENT).where(AUDIT_EVENT.EVENT_ID.eq(eventId)).fetchOne()?.let(::toView)

    private fun toView(r: AuditEventRecord) = AuditEventView(
        r.seq,
        r.eventId,
        r.occurredAt,
        r.recordedAt,
        r.actor,
        r.eventType,
        r.aggregateType,
        r.aggregateRef,
        r.correlationId,
        Json.mapper.readValue(r.payload.data(), mapType),
    )
}
