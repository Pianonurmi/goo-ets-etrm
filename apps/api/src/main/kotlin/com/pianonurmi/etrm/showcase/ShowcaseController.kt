package com.pianonurmi.etrm.showcase

import com.pianonurmi.etrm.platform.errors.NotFoundException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class ResetRequest(val reason: String)

data class ResetAccepted(val requestedAt: Instant, val message: String)

/** The administrator's reset of the public demo; outside the showcase profile there is nothing to reset (404). */
@RestController
@RequestMapping("/api/v1/showcase")
class ShowcaseController(private val reset: ObjectProvider<ShowcaseResetService>) {
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reset(
        @RequestBody body: ResetRequest,
    ): ResetAccepted {
        val service = reset.ifAvailable ?: throw NotFoundException("Showcase", "reset")
        val requestedAt = service.request(body.reason)
        return ResetAccepted(requestedAt, "The demo restarts and is rebuilt; this takes a few minutes")
    }
}
