package com.pianonurmi.etrm.inventory.adapter.jooq

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.CertificateLot.CERTIFICATE_LOT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.LotEvent.LOT_EVENT
import com.pianonurmi.etrm.inventory.api.LotEventView
import com.pianonurmi.etrm.inventory.domain.LotEventJournal
import com.pianonurmi.etrm.inventory.domain.NewLotEvent
import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.privacy.PersonalData
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

@Repository
class JooqLotEventJournal(private val dsl: DSLContext) : LotEventJournal {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun append(event: NewLotEvent): Long {
        val previous = dsl.select(
            LOT_EVENT.HASH,
        ).from(LOT_EVENT).where(LOT_EVENT.LOT_ID.eq(event.lotId)).orderBy(LOT_EVENT.SEQ.desc()).limit(1).fetchOne(LOT_EVENT.HASH)
        // The journal is hash-chained and cannot forget: no beneficiary name or identifier goes in (S7).
        val payload = Json.canonical(PersonalData.redact(event.payload))
        val effectiveAt = event.effectiveAt.truncatedTo(ChronoUnit.MICROS)
        val hash = HashChain.next(previous, hashInput(event.lotId, event.eventType, effectiveAt, event.actor, payload))
        return dsl.insertInto(LOT_EVENT)
            .set(LOT_EVENT.LOT_ID, event.lotId)
            .set(LOT_EVENT.EVENT_TYPE, event.eventType)
            .set(LOT_EVENT.EFFECTIVE_AT, effectiveAt)
            .set(LOT_EVENT.ACTOR, event.actor)
            .set(LOT_EVENT.CORRELATION_ID, event.correlationId)
            .set(LOT_EVENT.PAYLOAD, JSONB.jsonb(payload))
            .set(LOT_EVENT.PREV_HASH, previous)
            .set(LOT_EVENT.HASH, hash)
            .returning(LOT_EVENT.SEQ)
            .fetchOne()!!
            .seq
    }

    override fun history(lotId: UUID): List<LotEventView> = dsl.selectFrom(LOT_EVENT).where(LOT_EVENT.LOT_ID.eq(lotId)).orderBy(LOT_EVENT.SEQ).fetch { r ->
        LotEventView(
            r.seq,
            r.lotId,
            r.eventType,
            r.recordedAt,
            r.effectiveAt,
            r.actor,
            r.correlationId,
            Json.mapper.readValue(r.payload.data(), mapType),
            r.prevHash,
            r.hash,
        )
    }

    override fun lastSeq(): Long = dsl.select(DSL.coalesce(DSL.max(LOT_EVENT.SEQ), DSL.inline(0L))).from(LOT_EVENT).fetchOne(0, Long::class.java) ?: 0L

    /** One probe of the lot/seq index per lot of the holder; queries the partitioned parent table (ADR 0030). */
    override fun brokenChains(): List<UUID> {
        val broken = mutableListOf<UUID>()
        dsl.selectFrom(LOT_EVENT).orderBy(LOT_EVENT.LOT_ID, LOT_EVENT.SEQ).fetchLazy().use { cursor ->
            var currentLot: UUID? = null
            var expectedPrev: String? = null
            for (r in cursor) {
                if (r.lotId != currentLot) {
                    currentLot = r.lotId
                    expectedPrev = null
                }
                val payload = Json.canonical(r.payload.data())
                val recomputed = HashChain.next(expectedPrev, hashInput(r.lotId, r.eventType, r.effectiveAt, r.actor, payload))
                if (r.prevHash != expectedPrev || r.hash != recomputed) {
                    if (broken.lastOrNull() != r.lotId) broken += r.lotId
                }
                expectedPrev = r.hash
            }
        }
        return broken
    }

    private fun hashInput(lotId: UUID, type: String, effectiveAt: Instant, actor: String, payload: String) =
        "$lotId|$type|${DateTimeFormatter.ISO_INSTANT.format(effectiveAt)}|$actor|$payload"
}
