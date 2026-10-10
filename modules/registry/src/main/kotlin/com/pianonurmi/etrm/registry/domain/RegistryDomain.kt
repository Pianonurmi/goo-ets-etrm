package com.pianonurmi.etrm.registry.domain

import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.TransferRoute
import java.time.Instant
import java.util.UUID

/** Result of asking a registry gateway to execute an instruction. */
sealed interface GatewayResult {
    /** The registry accepted and completed the instruction synchronously. */
    data class Confirmed(val externalRef: String?, val response: Map<String, Any?> = emptyMap()) : GatewayResult

    /** The instruction was handed over (API accepted, file written, task created for an operator); confirmation follows. */
    data class Sent(val externalRef: String?, val response: Map<String, Any?> = emptyMap()) : GatewayResult

    data class Failed(val reason: String, val response: Map<String, Any?> = emptyMap()) : GatewayResult
}

/** Port implemented per registry integration mode. */
interface RegistryGateway {
    /** Registry codes this gateway serves; `*` serves any registry with the matching integration mode. */
    fun supports(registryCode: String, integration: String): Boolean

    fun execute(instruction: RegistryInstructionView): GatewayResult
}

/** Instruction state machine. */
class RegistryInstruction(state: RegistryInstructionView) {
    var state: RegistryInstructionView = state
        private set

    fun sent(externalRef: String?, at: Instant): RegistryInstructionView {
        require(InstructionStatus.PENDING, InstructionStatus.FAILED)
        return transition(
            state.copy(
                status = InstructionStatus.SENT,
                externalRef = externalRef ?: state.externalRef,
                sentAt = at,
                attempts = state.attempts + 1,
                lastError = null,
            ),
        )
    }

    fun acknowledged(externalRef: String?): RegistryInstructionView {
        require(InstructionStatus.SENT)
        return transition(state.copy(status = InstructionStatus.ACKNOWLEDGED, externalRef = externalRef ?: state.externalRef))
    }

    fun confirmed(externalRef: String?, by: String, at: Instant): RegistryInstructionView {
        require(InstructionStatus.PENDING, InstructionStatus.SENT, InstructionStatus.ACKNOWLEDGED)
        return transition(
            state.copy(
                status = InstructionStatus.CONFIRMED,
                externalRef = externalRef ?: state.externalRef,
                confirmedBy = by,
                confirmedAt = at,
                attempts = if (state.status == InstructionStatus.PENDING) state.attempts + 1 else state.attempts,
            ),
        )
    }

    /** `attempts` counts gateway executions: a failure reported for an already sent instruction adds none. */
    fun failed(reason: String): RegistryInstructionView {
        require(InstructionStatus.PENDING, InstructionStatus.SENT, InstructionStatus.ACKNOWLEDGED)
        val attempts = if (state.status == InstructionStatus.PENDING) state.attempts + 1 else state.attempts
        return transition(state.copy(status = InstructionStatus.FAILED, lastError = reason, attempts = attempts))
    }

    fun cancelled(reason: String): RegistryInstructionView {
        require(InstructionStatus.PENDING, InstructionStatus.FAILED)
        return transition(state.copy(status = InstructionStatus.CANCELLED, lastError = reason))
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

    fun lock(id: UUID): RegistryInstructionView?

    fun byKey(key: String): RegistryInstructionView?

    fun insert(instruction: RegistryInstructionView, requestPayload: Map<String, Any?>)

    fun update(instruction: RegistryInstructionView, responsePayload: Map<String, Any?>?): Boolean

    fun open(registryCode: String?): List<RegistryInstructionView>

    fun forPlan(planRef: UUID): List<RegistryInstructionView>
}

interface TransferRouteRepository {
    fun overrides(): List<TransferRoute>

    fun upsert(route: TransferRoute)
}
