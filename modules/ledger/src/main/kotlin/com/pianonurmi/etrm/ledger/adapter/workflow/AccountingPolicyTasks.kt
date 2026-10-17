package com.pianonurmi.etrm.ledger.adapter.workflow

import com.pianonurmi.etrm.ledger.api.PolicyChangeRequested
import com.pianonurmi.etrm.ledger.api.PolicyKind
import com.pianonurmi.etrm.ledger.application.AccountingPolicyService
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

/** Accounting policies (MtM treatment, net realisable value) change only with a second accountant's approval. */
@Component
class AccountingPolicyTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val periodEnd: AccountingPolicyService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "ledger",
        displayName = "Accounting policy change",
        candidateRoles = setOf(Role.ACCOUNTANT),
        defaultSla = Duration.ofDays(5),
        escalationRoles = setOf(Role.ACCOUNTANT),
        defaultPriority = 40,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> periodEnd.applyPolicyChange(task.subjectRef, task.decider())
            Decision.REJECT -> periodEnd.rejectPolicyChange(task.subjectRef, task.decider())
            Decision.ACKNOWLEDGE -> throw ValidationException("Policy changes are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: PolicyChangeRequested) {
        val c = event.change
        val what = when (c.kind) {
            PolicyKind.MTM -> "MtM of ${c.change.bookType} books: ${c.change.treatment}"
            PolicyKind.NRV ->
                "net realisable value: cost to sell ${c.change.gooCostToSellPerMwh}/MWh and ${c.change.allowanceCostToSell}/t " +
                    "${c.change.currency}, nil within ${c.change.expiryDays} days of expiry"
        }
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                c.id,
                c.legalEntityId,
                "Accounting policy ${if (c.legalEntityId == null) "default" else "for the entity"}: $what",
                c.requestedBy,
                mapOf("kind" to c.kind.name, "reason" to c.change.reason),
            ),
        )
    }

    companion object {
        const val TYPE = "ACCOUNTING_POLICY_CHANGE"
        private const val SUBJECT = "ACCOUNTING_POLICY_CHANGE"
    }
}
