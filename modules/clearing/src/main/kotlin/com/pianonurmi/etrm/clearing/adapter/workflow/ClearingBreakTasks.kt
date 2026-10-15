package com.pianonurmi.etrm.clearing.adapter.workflow

import com.pianonurmi.etrm.clearing.api.ClearingBreakClosed
import com.pianonurmi.etrm.clearing.api.ClearingBreakOpened
import com.pianonurmi.etrm.clearing.api.ClearingBreakStatus
import com.pianonurmi.etrm.clearing.application.ClearingStatementService
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
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

private const val SUBJECT = "CLEARING_BREAK"

/**
 * A day whose clearing statement differs from the computed variation margin. Approving accepts the member's figures
 * with a reason (a second person: not who imported the statement); a correction of the trade or price that makes them
 * agree closes the task by itself.
 */
@Component
class ClearingBreakTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val statements: ClearingStatementService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "clearing",
        displayName = "Clearing statement break",
        candidateRoles = Roles.OPERATIONS,
        allowedDecisions = setOf(Decision.APPROVE),
        reasonRequiredFor = setOf(Decision.APPROVE),
        defaultSla = Duration.ofHours(24),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 70,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        if (decision != Decision.APPROVE) throw ValidationException("A clearing break is accepted, or closes when the margin is corrected")
        statements.accept(task.subjectRef, task.decidedBy!!, reason)
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: ClearingBreakOpened) {
        val b = event.clearingBreak
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                b.id,
                b.legalEntityId,
                "Clearing statement of ${b.businessDate} differs: ${b.differences.joinToString {
                    listOfNotNull(it.kind.name, it.contractCode).joinToString(" ")
                }}",
                b.openedBy,
                mapOf(
                    "accountId" to b.accountId.toString(),
                    "businessDate" to b.businessDate.toString(),
                    "differences" to b.differences.map {
                        mapOf("kind" to it.kind.name, "contract" to it.contractCode, "ours" to it.ours?.toPlainString(), "theirs" to it.theirs?.toPlainString())
                    },
                ),
            ),
        )
    }

    /** Matched by a correction; an accepted break's task is the one being decided. */
    @EventListener
    fun on(event: ClearingBreakClosed) {
        if (event.clearingBreak.status != ClearingBreakStatus.MATCHED) return
        workflow.closeFor(TYPE, SUBJECT, event.clearingBreak.id, TaskStatus.COMPLETED, event.clearingBreak.resolution ?: "Matched")
    }

    companion object {
        const val TYPE = "CLEARING_BREAK"
    }
}
