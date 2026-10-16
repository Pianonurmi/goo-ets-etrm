package com.pianonurmi.etrm.registry.domain

import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.registry.api.InstructionPage
import com.pianonurmi.etrm.registry.api.InstructionSearch
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.RegistryCircuitView
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.StatementFormatView
import com.pianonurmi.etrm.registry.api.StatementKind
import com.pianonurmi.etrm.registry.api.TransferRoute
import java.time.Instant
import java.util.UUID

/** Result of asking a registry gateway to execute (or report on) an instruction. */
sealed interface GatewayResult {
    /** The registry accepted and completed the instruction synchronously. */
    data class Confirmed(val externalRef: String?, val response: Map<String, Any?> = emptyMap()) : GatewayResult

    /** The instruction was handed over (API accepted, file written, task created for an operator); confirmation follows. */
    data class Sent(val externalRef: String?, val response: Map<String, Any?> = emptyMap()) : GatewayResult

    /** The registry refused the instruction; nothing happened there. */
    data class Failed(val reason: String, val response: Map<String, Any?> = emptyMap()) : GatewayResult

    /** The call failed before reaching the registry (connection refused, 503 before acceptance); safe to try again. */
    data class Retry(val reason: String) : GatewayResult

    /** The call may have reached the registry (timeout, broken connection); never resend, ask for the status instead. */
    data class Unknown(val reason: String) : GatewayResult
}

/** Executes instructions for one integration channel. */
interface RegistryGateway {
    /** The integration mode served (`SIMULATOR`, `MANUAL`, `FILE`, `API`). */
    val channel: String

    /**
     * True when execution involves no external I/O and cannot fail transiently (simulator, manual hand-over): such
     * instructions are executed in the submitting transaction. Others go through the outbox dispatcher.
     */
    val inline: Boolean

    /** Registry codes this gateway serves within its channel; the default serves every registry. */
    fun supports(registryCode: String): Boolean = true

    fun execute(instruction: RegistryInstructionView): GatewayResult
}

/** Optional capability: ask the registry what happened to an instruction (by our instruction key as client reference). */
interface InstructionStatusSource {
    /** Returns the registry's view, or null when the registry does not know the instruction or cannot be asked now. */
    fun status(instruction: RegistryInstructionView): GatewayResult?
}

/** Instruction state machine. */
class RegistryInstruction(state: RegistryInstructionView) {
    var state: RegistryInstructionView = state
        private set

    fun approved(by: String, at: Instant): RegistryInstructionView {
        require(InstructionStatus.PENDING_APPROVAL)
        return transition(state.copy(status = InstructionStatus.PENDING, approvedBy = by, approvedAt = at, nextAttemptAt = at))
    }

    fun claimed(): RegistryInstructionView {
        require(InstructionStatus.PENDING)
        return transition(state.copy(status = InstructionStatus.DISPATCHING))
    }

    fun sent(externalRef: String?, at: Instant): RegistryInstructionView {
        require(InstructionStatus.PENDING, InstructionStatus.DISPATCHING, InstructionStatus.UNKNOWN)
        return transition(
            state.copy(
                status = InstructionStatus.SENT,
                externalRef = externalRef ?: state.externalRef,
                sentAt = state.sentAt ?: at,
                attempts = if (state.status == InstructionStatus.UNKNOWN) state.attempts else state.attempts + 1,
                lastError = null,
                nextAttemptAt = null,
            ),
        )
    }

    fun acknowledged(externalRef: String?): RegistryInstructionView {
        require(InstructionStatus.SENT, InstructionStatus.UNKNOWN)
        return transition(state.copy(status = InstructionStatus.ACKNOWLEDGED, externalRef = externalRef ?: state.externalRef))
    }

    /** A transient failure before the registry was reached: back to PENDING with a later attempt time. */
    fun retryLater(reason: String, at: Instant): RegistryInstructionView {
        require(InstructionStatus.DISPATCHING)
        return transition(state.copy(status = InstructionStatus.PENDING, attempts = state.attempts + 1, lastError = reason, nextAttemptAt = at))
    }

    fun unknown(reason: String): RegistryInstructionView {
        require(InstructionStatus.DISPATCHING, InstructionStatus.SENT, InstructionStatus.ACKNOWLEDGED)
        val attempts = if (state.status == InstructionStatus.DISPATCHING) state.attempts + 1 else state.attempts
        return transition(state.copy(status = InstructionStatus.UNKNOWN, lastError = reason, attempts = attempts, nextAttemptAt = null))
    }

    fun confirmed(externalRef: String?, by: String, at: Instant): RegistryInstructionView {
        require(InstructionStatus.PENDING, InstructionStatus.DISPATCHING, InstructionStatus.SENT, InstructionStatus.ACKNOWLEDGED, InstructionStatus.UNKNOWN)
        val executedNow = state.status == InstructionStatus.PENDING || state.status == InstructionStatus.DISPATCHING
        return transition(
            state.copy(
                status = InstructionStatus.CONFIRMED,
                externalRef = externalRef ?: state.externalRef,
                confirmedBy = by,
                confirmedAt = at,
                attempts = if (executedNow) state.attempts + 1 else state.attempts,
                nextAttemptAt = null,
            ),
        )
    }

    /** `attempts` counts gateway executions: a failure reported for an already sent instruction adds none. */
    fun failed(reason: String): RegistryInstructionView {
        require(InstructionStatus.PENDING, InstructionStatus.DISPATCHING, InstructionStatus.SENT, InstructionStatus.ACKNOWLEDGED, InstructionStatus.UNKNOWN)
        val executedNow = state.status == InstructionStatus.PENDING || state.status == InstructionStatus.DISPATCHING
        val attempts = if (executedNow) state.attempts + 1 else state.attempts
        return transition(state.copy(status = InstructionStatus.FAILED, lastError = reason, attempts = attempts, nextAttemptAt = null))
    }

    /** A manual retry of a failed or unknown instruction goes back through approval. */
    fun resubmitted(needsApproval: Boolean, at: Instant): RegistryInstructionView {
        require(InstructionStatus.FAILED, InstructionStatus.UNKNOWN)
        return transition(
            state.copy(
                status = if (needsApproval) InstructionStatus.PENDING_APPROVAL else InstructionStatus.PENDING,
                approvedBy = null,
                approvedAt = null,
                nextAttemptAt = if (needsApproval) null else at,
            ),
        )
    }

    fun cancelled(reason: String): RegistryInstructionView {
        require(InstructionStatus.PENDING_APPROVAL, InstructionStatus.PENDING, InstructionStatus.FAILED)
        return transition(state.copy(status = InstructionStatus.CANCELLED, lastError = reason, nextAttemptAt = null))
    }

    private fun transition(next: RegistryInstructionView): RegistryInstructionView {
        state = next.copy(version = state.version + 1)
        return state
    }

    private fun require(vararg allowed: InstructionStatus) {
        if (state.status !in allowed) {
            throw IllegalStateTransitionException(
                "Instruction ${state.instructionKey}",
                state.status.name,
                "one of ${allowed.joinToString()}",
            )
        }
    }
}

interface RegistryInstructionRepository {
    fun find(id: UUID): RegistryInstructionView?

    /** Erases the beneficiary's name and identifier from the instructions' cancellation and request; returns the count. */
    fun eraseBeneficiary(beneficiaryId: UUID): Int

    fun lock(id: UUID): RegistryInstructionView?

    fun byKey(key: String): RegistryInstructionView?

    fun byExternalRef(registryCode: String, externalRef: String): RegistryInstructionView?

    fun insert(instruction: RegistryInstructionView, requestPayload: Map<String, Any?>)

    fun update(instruction: RegistryInstructionView, responsePayload: Map<String, Any?>?): Boolean

    fun open(registryCode: String?): List<RegistryInstructionView>

    fun search(search: InstructionSearch): InstructionPage

    fun forPlan(planRef: UUID): List<RegistryInstructionView>

    /**
     * Atomically moves due PENDING instructions of [channels] to DISPATCHING with a lease; returns the claimed ids.
     * [excludedRegistries] are left out; with [onlyRegistry], only that registry's instructions are claimed.
     */
    fun claimDue(
        channels: Set<String>,
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
        excludedRegistries: Set<String> = emptySet(),
        onlyRegistry: String? = null,
    ): List<UUID>

    /** DISPATCHING instructions whose lease expired (the dispatcher died mid-call). */
    fun expiredLeases(now: Instant, limit: Int): List<UUID>

    fun inStatus(status: InstructionStatus, limit: Int): List<RegistryInstructionView>

    /** Instructions with the registry since before [sentBefore] and not yet reported overdue. */
    fun overdueCandidates(sentBefore: Instant, limit: Int): List<RegistryInstructionView>

    fun markOverdueNotified(id: UUID, at: Instant)

    /** Open instructions per status, for metrics. */
    fun openCounts(): Map<InstructionStatus, Int>

    /** Creation time of the oldest instruction waiting to be dispatched. */
    fun oldestPendingSince(): Instant?
}

interface TransferRouteRepository {
    fun overrides(): List<TransferRoute>

    fun upsert(route: TransferRoute)
}

interface StatementFormatRepository {
    fun find(registryCode: String, profileCode: String, kind: StatementKind): StatementFormatView?

    fun forRegistry(registryCode: String, kind: StatementKind): List<StatementFormatView>

    fun all(registryCode: String?): List<StatementFormatView>

    fun upsert(format: StatementFormatView)
}

/** Dispatch circuits per registry (`registry_circuit`). Unknown registries have a closed circuit. */
interface RegistryCircuitRepository {
    fun all(): List<RegistryCircuitView>

    fun find(registryCode: String): RegistryCircuitView?

    /**
     * Counts a transport failure. At [threshold] failures in a row the circuit opens (or stays open) with the next
     * probe at [probeAfter].
     */
    fun failure(registryCode: String, reason: String, at: Instant, threshold: Int, probeAfter: Instant): RegistryCircuitView

    /** The registry answered: the failure count is reset and an open circuit closes. Returns whether it was open. */
    fun answered(registryCode: String, at: Instant): Boolean

    /** An operator closes the circuit. Returns null for a registry without a circuit row. */
    fun resume(registryCode: String, by: String, at: Instant): RegistryCircuitView?
}
