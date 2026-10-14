package com.pianonurmi.etrm.events

import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Parked event publications (failed too often to be resubmitted automatically), for an administrator. */
@RestController
@RequestMapping("/api/v1/admin/event-publications")
class EventPublicationController(private val maintenance: EventPublicationMaintenance, private val actors: ActorProvider) {
    @GetMapping("/parked")
    fun parked(
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<EventPublicationView> {
        requireAdmin()
        return maintenance.parked(limit.coerceIn(1, MAX_LIMIT))
    }

    /** Sends the publication again once its cause is fixed; it counts as one more attempt. */
    @PostMapping("/{id}/resubmit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun resubmit(
        @PathVariable id: UUID,
    ) {
        requireAdmin()
        if (!maintenance.resubmit(id)) throw NotFoundException("EventPublication", id)
    }

    private fun requireAdmin() {
        if (!actors.current().has(Role.ADMIN)) throw ForbiddenException("Only an administrator can manage event publications")
    }

    private companion object {
        const val MAX_LIMIT = 1000
    }
}
