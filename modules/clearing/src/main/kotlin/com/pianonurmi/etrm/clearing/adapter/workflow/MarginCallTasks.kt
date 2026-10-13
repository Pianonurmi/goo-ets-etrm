package com.pianonurmi.etrm.clearing.adapter.workflow

import com.pianonurmi.etrm.clearing.api.MarginCallClosed
import com.pianonurmi.etrm.clearing.api.MarginCallOpened
import com.pianonurmi.etrm.clearing.api.MarginCallStatus
import com.pianonurmi.etrm.clearing.application.MarginCallService
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
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

private const val SUBJECT = "MARGIN_CALL"

/**
 * A margin call from the clearing member. Approving pays it from the bank, rejecting disputes it; either by someone
 * other than who imported the statement. A later statement cancels the task with the call.
 */
@Component
class MarginCallTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val calls: MarginCallService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "clearing",
        displayName = "Margin call",
        candidateRoles = setOf(Role.BACK_OFFICE, Role.ACCOUNTANT),
        defaultSla = Duration.ofHours(4),
        escalationRoles = setOf(Role.BACK_OFFICE, Role.MIDDLE_OFFICE),
        defaultPriority = 90,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> calls.decide(task.subjectRef, task.decidedBy!!, pay = true, reason)
            Decision.REJECT -> calls.decide(task.subjectRef, task.decidedBy!!, pay = false, reason)
            Decision.ACKNOWLEDGE -> throw ValidationException("A margin call is paid (approve) or disputed (reject)")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: MarginCallOpened) {
        val c = event.call
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                c.id,
                c.legalEntityId,
                "Margin call of ${c.amount.toPlainString()} ${c.currency} on ${c.businessDate}",
                c.requestedBy,
                mapOf(
                    "accountId" to c.accountId.toString(),
                    "amount" to c.amount.toPlainString(),
                    "currency" to c.currency,
                    "businessDate" to c.businessDate.toString(),
                ),
            ),
        )
    }

    /** Replaced by a later statement; a paid or disputed call's task is the one being decided. */
    @EventListener
    fun on(event: MarginCallClosed) {
        if (event.call.status != MarginCallStatus.CANCELLED) return
        workflow.closeFor(TYPE, SUBJECT, event.call.id, TaskStatus.CANCELLED, event.call.reason)
    }

    companion object {
        const val TYPE = "MARGIN_CALL"
    }
}
