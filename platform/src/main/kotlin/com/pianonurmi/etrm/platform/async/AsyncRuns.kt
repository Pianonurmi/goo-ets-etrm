package com.pianonurmi.etrm.platform.async

import com.pianonurmi.etrm.platform.errors.Violation
import java.time.Instant
import java.util.UUID

enum class AsyncRunStatus { RUNNING, SUCCEEDED, FAILED }

/** Why a run failed: the problem the call would have answered had it run in the request (RFC 9457 fields). */
data class AsyncRunProblem(val status: Int, val title: String, val detail: String?, val violations: List<Violation> = emptyList())

/**
 * A long operation started by a request and finished on its own thread (period end, a valuation run, a report export).
 * [resultId] names what the operation produced, read through the operation's own endpoints once [status] is SUCCEEDED.
 */
data class AsyncRunView(
    val id: UUID,
    val kind: String,
    val legalEntityId: UUID?,
    val status: AsyncRunStatus,
    val resultId: UUID?,
    val problem: AsyncRunProblem?,
    val requestedBy: String,
    val requestedAt: Instant,
    val finishedAt: Instant?,
)

/**
 * Runs long operations outside the request (increment 16 M2): the request answers 202 with the run, and the client
 * polls `GET /api/v1/runs/{id}`. The caller checks roles and entity scope before starting, so a refusal is still an
 * immediate 403; what [start]'s work throws later becomes the run's [AsyncRunProblem].
 */
interface AsyncRuns {
    /**
     * Records a running [kind] run and starts [work] on a virtual thread as the current actor, in the request's trace.
     * The work opens its own transactions; its return value is the run's result id.
     */
    fun start(kind: String, legalEntityId: UUID?, work: () -> UUID): AsyncRunView
}
