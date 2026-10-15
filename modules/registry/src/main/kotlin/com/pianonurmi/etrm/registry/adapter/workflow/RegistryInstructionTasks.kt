package com.pianonurmi.etrm.registry.adapter.workflow

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.registry.api.InstructionCancelled
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.InstructionOutcomeUnknown
import com.pianonurmi.etrm.registry.api.InstructionOverdue
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionSubmitted
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.application.InstructionCommands
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

private const val SUBJECT = "REGISTRY_INSTRUCTION"

private fun describe(i: RegistryInstructionView): String =
    "${i.type} ${i.quantity} ${i.unit} ${i.productCode ?: ""} ${i.registryCode}" + (i.range?.let { " ${it.start}-${it.end}" } ?: "") +
        (i.toAccountRef?.let { " to ${i.toRegistryCode}:$it" } ?: "") + (i.complianceYear?.let { " for $it" } ?: "")

/** Four-eyes on registry instructions that do not come from an approved allocation plan. */
@Component
class InstructionApprovalTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val instructions: InstructionCommands,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "registry",
        displayName = "Registry instruction approval",
        candidateRoles = setOf(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE, Role.MIDDLE_OFFICE),
        defaultSla = Duration.ofHours(8),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 65,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> instructions.approve(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> instructions.cancel(task.subjectRef, "rejected: ${reason ?: "no reason"}")
            Decision.ACKNOWLEDGE -> throw ValidationException("Registry instructions are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: InstructionSubmitted) {
        val i = event.instruction
        if (i.status != InstructionStatus.PENDING_APPROVAL) return
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                i.id,
                i.holderEntityId,
                "Approve registry instruction: ${describe(i)}",
                i.submittedBy,
                mapOf("instructionKey" to i.instructionKey),
            ),
        )
    }

    @EventListener
    fun on(event: InstructionCancelled) {
        workflow.closeFor(TYPE, SUBJECT, event.instruction.id, TaskStatus.CANCELLED, event.reason)
    }

    companion object {
        const val TYPE = "INSTRUCTION_APPROVAL"
    }
}

/** Instructions needing a person: no confirmation within the channel's SLA, or an unknown outcome after a registry call. */
@Component
class InstructionFollowUpTasks(private val workflow: WorkflowCommand) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "registry",
        displayName = "Registry instruction follow-up",
        candidateRoles = setOf(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE),
        fourEyes = false,
        allowedDecisions = setOf(Decision.ACKNOWLEDGE),
        reasonRequiredFor = emptySet(),
        defaultSla = Duration.ofHours(4),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 75,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome = DecisionOutcome.Applied

    @EventListener
    fun on(event: InstructionOverdue) = open(event.instruction, "No registry confirmation yet")

    @EventListener
    fun on(event: InstructionOutcomeUnknown) = open(event.instruction, "Outcome unknown (${event.reason})")

    @EventListener
    fun on(event: InstructionConfirmed) = close(event.instruction, "confirmed")

    @EventListener
    fun on(event: InstructionFailed) = close(event.instruction, "failed: ${event.reason}")

    @EventListener
    fun on(event: InstructionCancelled) = close(event.instruction, "cancelled")

    private fun open(i: RegistryInstructionView, what: String) {
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                i.id,
                i.holderEntityId,
                "$what: ${describe(i)}",
                "system",
                mapOf("instructionKey" to i.instructionKey, "status" to i.status.name, "channel" to i.channel),
            ),
        )
    }

    private fun close(i: RegistryInstructionView, why: String) {
        workflow.closeFor(TYPE, SUBJECT, i.id, TaskStatus.COMPLETED, why)
    }

    companion object {
        const val TYPE = "INSTRUCTION_FOLLOW_UP"
    }
}
