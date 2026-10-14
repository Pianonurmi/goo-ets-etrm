package com.pianonurmi.etrm.audit.adapter.rest

import com.pianonurmi.etrm.audit.api.AuditEventView
import com.pianonurmi.etrm.audit.api.AuditFilter
import com.pianonurmi.etrm.audit.api.AuditQuery
import com.pianonurmi.etrm.audit.api.AuditTrailCheck
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
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
class AuditController(private val query: AuditQuery, private val actorProvider: ActorProvider) {
    /**
     * The trail spans every entity and module and carries whole events (trades, invoices, beneficiaries): control
     * functions read it, the front office does not, and only someone who sees every legal entity.
     */
    private fun requireReader() {
        val actor = actorProvider.current()
        actor.requireRole(Role.AUDITOR, Role.ACCOUNTANT, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        if (!actor.seesAllEntities) throw ForbiddenException("The audit trail spans every legal entity; ${actor.userId} does not see them all")
    }

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
    ): List<AuditEventView> = requireReader().let {
        query.events(AuditFilter(eventType, actor, aggregateType, aggregateRef, from, to, afterSeq, limit))
    }

    @GetMapping("/events/{eventId}")
    fun event(
        @PathVariable eventId: UUID,
    ): AuditEventView? = requireReader().let { query.event(eventId) }

    /** Checks the seal chain over the trail: rows changed or removed after sealing, and rows left unsealed. */
    @GetMapping("/verify")
    fun verify(): AuditTrailCheck = requireReader().let { query.verifyTrail() }
}
