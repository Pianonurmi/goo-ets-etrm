package com.pianonurmi.etrm.ledger.adapter.workflow

import com.pianonurmi.etrm.ledger.api.PostingRuleChangeRequested
import com.pianonurmi.etrm.ledger.application.GlInterfaceService
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/** Posting rules decide what the books say: a change applies only when a second accountant approves it. */
@Component
class PostingRuleChangeTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val gl: GlInterfaceService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "ledger",
        displayName = "Posting rule change",
        candidateRoles = setOf(Role.ACCOUNTANT),
        defaultSla = Duration.ofDays(2),
        escalationRoles = setOf(Role.ACCOUNTANT),
        defaultPriority = 50,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> gl.applyRuleChange(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> gl.rejectRuleChange(task.subjectRef, task.decidedBy!!)
            Decision.ACKNOWLEDGE -> throw ValidationException("Posting rule changes are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: PostingRuleChangeRequested) {
        val c = event.change
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                c.id,
                c.legalEntityId,
                "Posting rule ${c.ruleCode} on ${c.change.eventType}: " + c.change.lines.joinToString { "${it.side} ${it.account} ${it.amount}" },
                c.requestedBy,
                mapOf("ruleCode" to c.ruleCode, "conditions" to c.change.conditions, "active" to c.change.active),
            ),
        )
    }

    companion object {
        const val TYPE = "POSTING_RULE_CHANGE"
        private const val SUBJECT = "POSTING_RULE"
    }
}
