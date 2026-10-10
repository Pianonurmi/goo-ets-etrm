package com.pianonurmi.etrm.referencedata.adapter.workflow

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.ChangeRequestStatus
import com.pianonurmi.etrm.referencedata.api.ReferenceDataChangeCancelled
import com.pianonurmi.etrm.referencedata.api.ReferenceDataChangeRequested
import com.pianonurmi.etrm.referencedata.application.ChangeRequestService
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

/** Sensitive master data changes go through the inbox and are applied only on a second person's approval. */
@Component
class ReferenceDataChangeTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val changes: ChangeRequestService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "referencedata",
        displayName = "Master data change",
        candidateRoles = setOf(Role.MIDDLE_OFFICE, Role.RISK),
        defaultSla = Duration.ofHours(24),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 60,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        val by = task.decidedBy ?: "unknown"
        val result = when (decision) {
            Decision.APPROVE -> changes.approve(task.subjectRef, by, reason)
            Decision.REJECT -> changes.reject(task.subjectRef, by, reason)
            Decision.ACKNOWLEDGE -> throw ValidationException("Master data changes are approved or rejected")
        }
        return if (result.status == ChangeRequestStatus.PENDING) DecisionOutcome.Superseded("change still pending") else DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: ReferenceDataChangeRequested) {
        val request = event.request
        workflow.open(
            OpenTask(
                type = TYPE,
                subjectType = SUBJECT,
                subjectRef = request.id,
                legalEntityId = request.legalEntityId,
                summary = request.summary,
                requestedBy = request.requestedBy,
                payload = mapOf("entityType" to request.entityType.name, "entityKey" to request.entityKey, "reason" to request.reason),
            ),
        )
    }

    @EventListener
    fun on(event: ReferenceDataChangeCancelled) {
        workflow.closeFor(TYPE, SUBJECT, event.request.id, TaskStatus.CANCELLED, event.request.decisionNote)
    }

    companion object {
        const val TYPE = "REFERENCE_DATA_CHANGE"
        const val SUBJECT = "CHANGE_REQUEST"
    }
}
