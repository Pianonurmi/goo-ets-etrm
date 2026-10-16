package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.async.AsyncRunView
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The status of a long operation started with 202 (period end, valuation run, report export), for its requester to poll. */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Async runs", description = "Status and result of long operations that answered 202")
class AsyncRunController(private val runs: AsyncRunner, private val actors: ActorProvider) {
    /** Only the user who started a run reads it; for anyone else it does not exist. */
    @GetMapping("/{id}")
    fun run(
        @PathVariable id: UUID,
    ): AsyncRunView = runs.find(id)?.takeIf { it.requestedBy == actors.current().userId } ?: throw NotFoundException("AsyncRun", id)
}
