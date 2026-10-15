package com.pianonurmi.etrm.allocation.adapter.workflow

import com.pianonurmi.etrm.allocation.api.AllocationApproved
import com.pianonurmi.etrm.allocation.api.AllocationCommand
import com.pianonurmi.etrm.allocation.api.AllocationProposed
import com.pianonurmi.etrm.allocation.api.AllocationRejected
import com.pianonurmi.etrm.allocation.api.AllocationStale
import com.pianonurmi.etrm.allocation.api.PlanStatus
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.security.Roles
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/** Allocation plan approvals: approving reserves the lots and instructs the registries; a stale plan supersedes the task. */
@Component
class AllocationApprovalTasks(private val workflow: WorkflowCommand, private val allocation: AllocationCommand) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "allocation",
        displayName = "Allocation plan approval",
        candidateRoles = Roles.OPERATIONS,
        defaultSla = Duration.ofHours(8),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 60,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome = when (decision) {
        Decision.APPROVE -> {
            val plan = allocation.approve(task.subjectRef)
            if (plan.status == PlanStatus.STALE) {
                DecisionOutcome.Superseded(
                    "plan became stale: ${plan.decisionReason ?: "inventory changed"}",
                )
            } else {
                DecisionOutcome.Applied
            }
        }
        Decision.REJECT -> {
            allocation.reject(task.subjectRef, reason ?: throw ValidationException("A rejection needs a reason"))
            DecisionOutcome.Applied
        }
        Decision.ACKNOWLEDGE -> throw ValidationException("Allocation plans are approved or rejected")
    }

    @EventListener
    fun on(event: AllocationProposed) {
        workflow.open(
            OpenTask(
                type = TYPE,
                subjectType = SUBJECT,
                subjectRef = event.plan.id,
                legalEntityId = event.plan.holderEntityId,
                summary = "Allocation plan ${event.plan.id}: ${event.plan.lines.size} lines, ${event.plan.allocatedMwh} MWh",
                requestedBy = event.plan.proposedBy,
                payload = mapOf(
                    "allocatedMwh" to event.plan.allocatedMwh,
                    "lines" to event.plan.lines.size,
                    "coverageShortfall" to event.plan.coverage?.totalShortfallMwh,
                ),
                dueAt = event.plan.reservationExpiresAt,
            ),
        )
    }

    @EventListener
    fun on(event: AllocationApproved) {
        workflow.closeFor(TYPE, SUBJECT, event.plan.id, TaskStatus.APPROVED, "approved outside the inbox")
    }

    @EventListener
    fun on(event: AllocationRejected) {
        workflow.closeFor(TYPE, SUBJECT, event.plan.id, TaskStatus.REJECTED, event.reason)
    }

    @EventListener
    fun on(event: AllocationStale) {
        workflow.closeFor(TYPE, SUBJECT, event.plan.id, TaskStatus.CANCELLED, "plan stale: ${event.reason}")
    }

    companion object {
        const val TYPE = "ALLOCATION_APPROVAL"
        const val SUBJECT = "ALLOCATION_PLAN"
    }
}
