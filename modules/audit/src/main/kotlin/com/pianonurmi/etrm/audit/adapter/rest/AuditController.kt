package com.pianonurmi.etrm.audit.adapter.rest

import com.pianonurmi.etrm.audit.api.AuditEventView
import com.pianonurmi.etrm.audit.api.AuditFilter
import com.pianonurmi.etrm.audit.api.AuditQuery
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Append-only audit trail of domain events and commands")
class AuditController(private val query: AuditQuery) {
    @GetMapping("/events")
    fun events(
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) aggregateType: String?,
        @RequestParam(required = false) aggregateRef: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) afterSeq: Long?,
        @RequestParam(defaultValue = "200") limit: Int,
    ): List<AuditEventView> = query.events(AuditFilter(eventType, actor, aggregateType, aggregateRef, from, to, afterSeq, limit))

    @GetMapping("/events/{eventId}")
    fun event(
        @PathVariable eventId: UUID,
    ): AuditEventView? = query.event(eventId)
}
