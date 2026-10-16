package com.pianonurmi.etrm.inventory.adapter.jooq

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.CertificateLot.CERTIFICATE_LOT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.LotEvent.LOT_EVENT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.LotEventAnchor.LOT_EVENT_ANCHOR
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
import java.time.YearMonth
import java.time.ZoneOffset
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

    /**
     * Walks the journal a month at a time, so each query reads one partition (ADR 0030) instead of sorting every
     * event of every lot at once. It starts at the latest archive cut: events before it may already be archived, and
     * the anchor kept at the cut stands for them. A lot's events are written under its row lock, so within one lot
     * the sequence and the recording time agree, and month by month in sequence order is each lot's own order.
     */
    override fun brokenChains(): List<UUID> {
        val anchors = dsl.select(LOT_EVENT_ANCHOR.LOT_ID, LOT_EVENT_ANCHOR.HASH).from(LOT_EVENT_ANCHOR)
            .fetchGroups(LOT_EVENT_ANCHOR.LOT_ID, LOT_EVENT_ANCHOR.HASH)
        val bounds = dsl.select(DSL.min(LOT_EVENT.RECORDED_AT), DSL.max(LOT_EVENT.RECORDED_AT)).from(LOT_EVENT).fetchOne()
        val first = bounds?.value1() ?: return emptyList()
        val last = bounds.value2() ?: return emptyList()
        val since = dsl.select(DSL.max(LOT_EVENT_ANCHOR.CUT)).from(LOT_EVENT_ANCHOR).fetchOne()?.value1()?.takeIf { it.isAfter(first) } ?: first
        val lastHash = HashMap<UUID, String>()
        val broken = LinkedHashSet<UUID>()
        var month = YearMonth.from(since.atOffset(ZoneOffset.UTC))
        while (!monthStart(month).isAfter(last)) {
            val from = maxOf(monthStart(month), since)
            dsl.selectFrom(LOT_EVENT)
                .where(LOT_EVENT.RECORDED_AT.ge(from), LOT_EVENT.RECORDED_AT.lt(monthStart(month.plusMonths(1))))
                .orderBy(LOT_EVENT.SEQ)
                .fetchLazy()
                .use { cursor ->
                    for (r in cursor) {
                        // A lot first met here starts from nothing, or from the anchor kept at the cut.
                        val expectedPrev = if (lastHash.containsKey(r.lotId)) {
                            lastHash[r.lotId]
                        } else {
                            r.prevHash?.takeIf { anchors[r.lotId]?.contains(it) == true }
                        }
                        val payload = Json.canonical(r.payload.data())
                        val recomputed = HashChain.next(expectedPrev, hashInput(r.lotId, r.eventType, r.effectiveAt, r.actor, payload))
                        if (r.prevHash != expectedPrev || r.hash != recomputed) broken += r.lotId
                        lastHash[r.lotId] = r.hash
                    }
                }
            month = month.plusMonths(1)
        }
        return broken.toList()
    }

    private fun monthStart(month: YearMonth): Instant = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)

    override fun anchor(cut: Instant): Int = dsl.execute(
        """
        INSERT INTO {0} (lot_id, cut, hash)
        SELECT DISTINCT ON (e.lot_id) e.lot_id, {1}, e.hash FROM {2} e
        WHERE e.recorded_at < {1}
        ORDER BY e.lot_id, e.seq DESC
        ON CONFLICT (lot_id, cut) DO NOTHING
        """.trimIndent(),
        LOT_EVENT_ANCHOR,
        DSL.`val`(cut),
        LOT_EVENT,
    )

    private fun hashInput(lotId: UUID, type: String, effectiveAt: Instant, actor: String, payload: String) =
        "$lotId|$type|${DateTimeFormatter.ISO_INSTANT.format(effectiveAt)}|$actor|$payload"
}
