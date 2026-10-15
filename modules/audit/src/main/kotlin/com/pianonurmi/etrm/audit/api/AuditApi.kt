package com.pianonurmi.etrm.audit.api

import java.time.Instant
import java.util.UUID

data class AuditEventView(
    val seq: Long,
    val eventId: UUID,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val actor: String,
    val eventType: String,
    val aggregateType: String?,
    val aggregateRef: String?,
    val correlationId: String?,
    val payload: Map<String, Any?>,
)

data class AuditFilter(
    val eventType: String? = null,
    val actor: String? = null,
    val aggregateType: String? = null,
    val aggregateRef: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val afterSeq: Long? = null,
    val limit: Int = 200,
)

interface AuditQuery {
    fun events(filter: AuditFilter): List<AuditEventView>

    fun event(eventId: UUID): AuditEventView?

    /** Checks the seal chain over the audit trail (review finding S6). */
    fun verifyTrail(): AuditTrailCheck

    /** The latest archive cut: rows that occurred before it may have been archived and dropped. */
    fun archiveCut(): Instant?
}

/**
 * Result of checking the audit trail: [brokenSeqs] are audit rows whose seal does not verify (changed, removed, or
 * a broken link); [unsealed] counts rows older than the sealing delay that have no seal yet.
 */
data class AuditTrailCheck(val brokenSeqs: List<Long>, val unsealed: Long, val head: String?) {
    val ok: Boolean get() = brokenSeqs.isEmpty() && unsealed == 0L
}

/** Explicit audit record for commands that do not produce a domain event (e.g. exports, logins, searches). */
interface AuditCommand {
    fun record(action: String, aggregateType: String?, aggregateRef: String?, details: Map<String, Any?> = emptyMap()): AuditEventView

    /**
     * Seals committed audit rows that have no seal yet and returns how many; a job does this every minute. [catchUp]
     * also looks beyond the last two days, for rows left unsealed while the job did not run.
     */
    fun seal(catchUp: Boolean = false): Int

    /**
     * Declares that audit rows which occurred before [cut] (the first instant of a UTC month) may be archived and
     * dropped; the seal chain then verifies without them. Cuts only move forward.
     */
    fun declareArchiveCut(cut: Instant, reason: String)
}
