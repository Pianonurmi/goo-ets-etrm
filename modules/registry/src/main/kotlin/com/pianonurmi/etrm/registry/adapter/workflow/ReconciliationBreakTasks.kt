package com.pianonurmi.etrm.registry.adapter.workflow

import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakAcceptanceRequested
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakDetected
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakResolved
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.ReconciliationCommand
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.security.Roles
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskAssigned
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

private const val SUBJECT = "RECONCILIATION_BREAK"

private fun describe(b: ReconciliationBreakView): String = when {
    b.productCode != null -> "${b.type} ${b.productCode}: registry ${b.registryQuantity}, ours ${b.internalQuantity}"
    else -> "${b.type} ${b.issuanceRef} ${b.range?.start}-${b.range?.end}"
}

/** The legal entity whose account the break is on (statements are for our own accounts). */
private fun ReferenceDataQuery.entityOf(b: ReconciliationBreakView) =
    b.registryAccountId?.let(::registryAccount)?.takeIf { it.holderType == HolderType.LEGAL_ENTITY }?.holderId

/**
 * One work item per open break. Assigning the task (claim, assign, delegate) puts the break under investigation; the
 * task closes when the break is resolved, accepted or cleared, never by a decision in the inbox.
 */
@Component
class ReconciliationBreakTasks(
    private val workflow: WorkflowCommand,
    private val reconciliation: ReconciliationCommand,
    private val reconciliationQuery: ReconciliationQuery,
    private val referenceData: ReferenceDataQuery,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "registry",
        displayName = "Reconciliation break",
        candidateRoles = setOf(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE),
        fourEyes = false,
        allowedDecisions = setOf(Decision.ACKNOWLEDGE),
        reasonRequiredFor = emptySet(),
        defaultSla = Duration.ofDays(2),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 60,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome =
        throw ValidationException("Resolve or accept the break through /api/v1/registry/breaks/${task.subjectRef}; the task then closes")

    @EventListener
    fun on(event: ReconciliationBreakDetected) {
        val b = event.breakView
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                b.id,
                referenceData.entityOf(b),
                "Reconciliation break: ${describe(b)}",
                "system",
                mapOf("type" to b.type.name, "registryAccountId" to b.registryAccountId?.toString(), "lotId" to b.lotId?.toString()),
            ),
        )
    }

    @EventListener
    fun on(event: TaskAssigned) {
        val task = event.task
        if (task.type != TYPE) return
        val assignee = task.assignee ?: return
        val current = reconciliationQuery.requireBreak(task.subjectRef)
        if (current.status == BreakStatus.OPEN || current.status == BreakStatus.INVESTIGATING) reconciliation.assignBreak(current.id, assignee)
    }

    @EventListener
    fun on(event: ReconciliationBreakResolved) {
        val b = event.breakView
        workflow.closeFor(TYPE, SUBJECT, b.id, TaskStatus.COMPLETED, "${b.status}: ${b.resolution ?: ""}")
    }

    companion object {
        const val TYPE = "RECONCILIATION_BREAK"
    }
}

/** Four eyes on accepting a break as it is: the requester may not decide. */
@Component
class BreakAcceptanceTasks(
    private val workflow: WorkflowCommand,
    private val reconciliation: ReconciliationCommand,
    private val referenceData: ReferenceDataQuery,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "registry",
        displayName = "Reconciliation break acceptance",
        candidateRoles = Roles.OPERATIONS,
        defaultSla = Duration.ofDays(1),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 55,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> reconciliation.decideAcceptance(task.subjectRef, true, task.decidedBy!!, reason)
            Decision.REJECT -> reconciliation.decideAcceptance(task.subjectRef, false, task.decidedBy!!, reason)
            Decision.ACKNOWLEDGE -> throw ValidationException("Break acceptance is approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: ReconciliationBreakAcceptanceRequested) {
        val b = event.breakView
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                b.id,
                referenceData.entityOf(b),
                "Accept reconciliation break: ${describe(b)} — ${b.resolution}",
                b.acceptanceRequestedBy!!,
                mapOf("type" to b.type.name, "reason" to b.resolution),
            ),
        )
    }

    /** A break resolved or cleared while acceptance was pending no longer needs the decision. */
    @EventListener
    fun on(event: ReconciliationBreakResolved) {
        workflow.closeFor(TYPE, SUBJECT, event.breakView.id, TaskStatus.CANCELLED, "break ${event.breakView.status}")
    }

    companion object {
        const val TYPE = "BREAK_ACCEPTANCE"
    }
}
