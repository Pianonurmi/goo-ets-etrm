package com.pianonurmi.etrm.ledger.adapter.jooq

import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.UnpostedEvent.UNPOSTED_EVENT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.UnpostedEventRecord
import com.pianonurmi.etrm.ledger.domain.UnpostedEvent
import com.pianonurmi.etrm.ledger.domain.UnpostedEventRepository
import com.pianonurmi.etrm.ledger.domain.UnpostedKind
import com.pianonurmi.etrm.ledger.domain.UnpostedStatus
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqUnpostedEventRepository(private val dsl: DSLContext) : UnpostedEventRepository {
    override fun record(event: UnpostedEvent): UnpostedEvent {
        val existing = event.eventId?.let { eventId ->
            dsl.selectFrom(UNPOSTED_EVENT)
                .where(
                    UNPOSTED_EVENT.EVENT_ID.eq(eventId),
                    UNPOSTED_EVENT.EVENT_TYPE.eq(event.eventType),
                    if (event.ruleCode == null) UNPOSTED_EVENT.RULE_CODE.isNull else UNPOSTED_EVENT.RULE_CODE.eq(event.ruleCode),
                    UNPOSTED_EVENT.STATUS.eq(UnpostedStatus.OPEN.name),
                )
                .forUpdate()
                .fetchOne()
        }
        if (existing != null) {
            return dsl.update(UNPOSTED_EVENT)
                .set(UNPOSTED_EVENT.ATTEMPTS, UNPOSTED_EVENT.ATTEMPTS.plus(1))
                .set(UNPOSTED_EVENT.REASON, event.reason)
                .set(UNPOSTED_EVENT.KIND, event.kind.name)
                .set(UNPOSTED_EVENT.LAST_ATTEMPT_AT, DSL.currentInstant())
                .where(UNPOSTED_EVENT.ID.eq(existing.id))
                .returning()
                .fetchOne()!!
                .let(::toDomain)
        }
        return dsl.insertInto(UNPOSTED_EVENT)
            .set(UNPOSTED_EVENT.ID, event.id)
            .set(UNPOSTED_EVENT.LEGAL_ENTITY_ID, event.legalEntityId)
            .set(UNPOSTED_EVENT.EVENT_ID, event.eventId)
            .set(UNPOSTED_EVENT.EVENT_TYPE, event.eventType)
            .set(UNPOSTED_EVENT.RULE_CODE, event.ruleCode)
            .set(UNPOSTED_EVENT.KIND, event.kind.name)
            .set(UNPOSTED_EVENT.REASON, event.reason)
            .set(UNPOSTED_EVENT.EVENT_CLASS, event.eventClass)
            .set(UNPOSTED_EVENT.EVENT_JSON, event.eventJson?.let(JSONB::jsonb))
            .set(UNPOSTED_EVENT.FACTS_JSON, event.factsJson?.let(JSONB::jsonb))
            .set(UNPOSTED_EVENT.REPLAYABLE, event.replayable)
            .returning()
            .fetchOne()!!
            .let(::toDomain)
    }

    override fun find(id: UUID): UnpostedEvent? = dsl.selectFrom(UNPOSTED_EVENT).where(UNPOSTED_EVENT.ID.eq(id)).fetchOne()?.let(::toDomain)

    override fun open(legalEntityId: UUID?, limit: Int): List<UnpostedEvent> = dsl.selectFrom(UNPOSTED_EVENT)
        .where(UNPOSTED_EVENT.STATUS.eq(UnpostedStatus.OPEN.name))
        .and(legalEntityId?.let { UNPOSTED_EVENT.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(UNPOSTED_EVENT.CREATED_AT)
        .limit(limit)
        .fetch(::toDomain)

    override fun replayable(limit: Int): List<UnpostedEvent> = dsl.selectFrom(UNPOSTED_EVENT)
        .where(UNPOSTED_EVENT.STATUS.eq(UnpostedStatus.OPEN.name), UNPOSTED_EVENT.REPLAYABLE.isTrue, UNPOSTED_EVENT.EVENT_JSON.isNotNull)
        .orderBy(UNPOSTED_EVENT.LAST_ATTEMPT_AT)
        .limit(limit)
        .fetch(::toDomain)

    override fun resolve(id: UUID, status: UnpostedStatus, by: String, resolution: String?): Boolean = dsl.update(UNPOSTED_EVENT)
        .set(UNPOSTED_EVENT.STATUS, status.name)
        .set(UNPOSTED_EVENT.RESOLVED_AT, DSL.currentInstant())
        .set(UNPOSTED_EVENT.RESOLVED_BY, by)
        .set(UNPOSTED_EVENT.RESOLUTION, resolution)
        .where(UNPOSTED_EVENT.ID.eq(id), UNPOSTED_EVENT.STATUS.eq(UnpostedStatus.OPEN.name))
        .execute() == 1

    override fun openCount(): Long = dsl.fetchCount(UNPOSTED_EVENT, UNPOSTED_EVENT.STATUS.eq(UnpostedStatus.OPEN.name)).toLong()

    private fun toDomain(r: UnpostedEventRecord) = UnpostedEvent(
        id = r.id,
        legalEntityId = r.legalEntityId,
        eventId = r.eventId,
        eventType = r.eventType,
        ruleCode = r.ruleCode,
        kind = UnpostedKind.valueOf(r.kind),
        reason = r.reason,
        eventClass = r.eventClass,
        eventJson = r.eventJson?.data(),
        factsJson = r.factsJson?.data(),
        replayable = r.replayable,
        status = UnpostedStatus.valueOf(r.status),
        attempts = r.attempts,
        createdAt = r.createdAt,
        lastAttemptAt = r.lastAttemptAt,
    )
}
