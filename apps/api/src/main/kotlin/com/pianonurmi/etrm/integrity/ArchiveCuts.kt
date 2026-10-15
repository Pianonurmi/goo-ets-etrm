package com.pianonurmi.etrm.integrity

import com.pianonurmi.etrm.audit.api.AuditCommand
import com.pianonurmi.etrm.audit.api.AuditQuery
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

data class ArchiveCutRequest(val cut: Instant, val reason: String)

data class ArchiveCutView(val cut: Instant, val anchoredLots: Int, val declaredBy: String)

/**
 * Declares an archive cut (`docs/operations/data-protection.md`): the audit trail and the lot journal may lose their
 * partitions before it and still verify. Only chains that verify now are cut, so nothing broken is hidden; the lot
 * journal is anchored at the cut first. The partitions themselves are archived and dropped by the database owner.
 */
@Service
class ArchiveCuts(
    private val auditQuery: AuditQuery,
    private val audit: AuditCommand,
    private val lots: LotQuery,
    private val lotCommand: LotCommand,
    private val properties: IntegrityProperties,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) {
    @Transactional
    fun declare(request: ArchiveCutRequest): ArchiveCutView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ADMIN)
        val latest = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().minus(properties.archiveMinAge).atStartOfDay(ZoneOffset.UTC).toInstant()
        if (request.cut.isAfter(latest)) {
            throw ValidationException(
                "An archive cut must be at least ${properties.archiveMinAge} old (etrm.integrity.archive-min-age); the latest allowed is $latest",
            )
        }
        audit.seal(catchUp = true)
        val trail = auditQuery.verifyTrail()
        if (trail.brokenSeqs.isNotEmpty()) throw ConflictException("The audit trail does not verify; investigate before archiving")
        if (lots.verifyJournal().isNotEmpty()) throw ConflictException("The lot journal does not verify; investigate before archiving")
        val anchored = lotCommand.anchorJournal(request.cut)
        audit.declareArchiveCut(request.cut, request.reason)
        return ArchiveCutView(request.cut, anchored, actor.userId)
    }
}

@RestController
@RequestMapping("/api/v1/admin/integrity")
class IntegrityController(private val cuts: ArchiveCuts) {
    @PostMapping("/archive-cuts")
    fun declareArchiveCut(
        @RequestBody body: ArchiveCutRequest,
    ): ArchiveCutView = cuts.declare(body)
}
