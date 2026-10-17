package com.pianonurmi.etrm.inventory.adapter.workflow

import com.pianonurmi.etrm.inventory.api.BeneficiaryErased
import com.pianonurmi.etrm.inventory.api.BeneficiaryErasureRequested
import com.pianonurmi.etrm.inventory.application.BeneficiaryService
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

/**
 * An administrator's request to erase a beneficiary's name and identifier waits for an auditor's approval. The task
 * names no one: it goes to the audit trail and the inbox, which must not keep the name either.
 */
@Component
class BeneficiaryErasureTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val beneficiaries: BeneficiaryService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "inventory",
        displayName = "Beneficiary erasure",
        candidateRoles = setOf(Role.AUDITOR),
        reasonRequiredFor = setOf(Decision.REJECT),
        allowedDecisions = setOf(Decision.APPROVE, Decision.REJECT),
        defaultSla = Duration.ofDays(5),
        escalationRoles = setOf(Role.AUDITOR),
        defaultPriority = 40,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> beneficiaries.erase(task.subjectRef, task.decider())
            Decision.REJECT -> beneficiaries.withdrawErasure(task.subjectRef, reason)
            Decision.ACKNOWLEDGE -> throw ValidationException("An erasure is approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: BeneficiaryErasureRequested) {
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                event.beneficiaryId,
                null,
                "Erase beneficiary ${event.beneficiaryId}: ${event.reason}",
                event.requestedBy,
                mapOf("reason" to event.reason),
            ),
        )
    }

    @EventListener
    fun on(event: BeneficiaryErased) = workflow.closeFor(TYPE, SUBJECT, event.beneficiaryId, TaskStatus.COMPLETED, "erased")

    companion object {
        const val TYPE = "BENEFICIARY_ERASURE"
        private const val SUBJECT = "BENEFICIARY"
    }
}
