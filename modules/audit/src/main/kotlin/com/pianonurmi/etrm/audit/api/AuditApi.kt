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
}

/** Explicit audit record for commands that do not produce a domain event (e.g. exports, logins, searches). */
interface AuditCommand {
    fun record(action: String, aggregateType: String?, aggregateRef: String?, details: Map<String, Any?> = emptyMap()): AuditEventView
}
