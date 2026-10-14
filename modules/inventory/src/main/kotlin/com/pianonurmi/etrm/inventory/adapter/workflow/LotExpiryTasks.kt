package com.pianonurmi.etrm.inventory.adapter.workflow

import com.pianonurmi.etrm.inventory.api.LotCancelled
import com.pianonurmi.etrm.inventory.api.LotExpired
import com.pianonurmi.etrm.inventory.api.LotExpiryFailed
import com.pianonurmi.etrm.inventory.api.LotWithdrawn
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
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

private const val SUBJECT = "LOT"

/**
 * One work item per lot that the nightly sweep could not expire (D6). The cause sits elsewhere (a missing rate, an
 * unmapped posting rule), so the task has no decision of its own: it closes when the lot expires, or when it leaves the
 * book some other way.
 */
@Component
class LotExpiryTasks(private val workflow: WorkflowCommand) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "inventory",
        displayName = "Lot expiry failed",
        candidateRoles = setOf(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR),
        fourEyes = false,
        allowedDecisions = setOf(Decision.ACKNOWLEDGE),
        reasonRequiredFor = emptySet(),
        defaultSla = Duration.ofDays(1),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 60,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome =
        throw ValidationException("Fix the cause (exchange rate, posting rule) and expire the lot again; the task then closes")

    @EventListener
    fun on(event: LotExpiryFailed) {
        val lot = event.lot
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                lot.id,
                lot.holderEntityId,
                "Lot ${lot.issuanceRef} ${lot.range} could not be expired: ${event.reason}",
                "system",
                mapOf("expiryDate" to lot.expiryDate.toString(), "reason" to event.reason),
            ),
        )
    }

    @EventListener
    fun on(event: LotExpired) = close(event.lot.id, TaskStatus.COMPLETED, "expired")

    @EventListener
    fun on(event: LotWithdrawn) = close(event.lot.id, TaskStatus.CANCELLED, "withdrawn")

    @EventListener
    fun on(event: LotCancelled) = close(event.lot.id, TaskStatus.CANCELLED, "cancelled")

    private fun close(lotId: UUID, status: TaskStatus, reason: String) {
        workflow.closeFor(TYPE, SUBJECT, lotId, status, reason)
    }

    companion object {
        const val TYPE = "LOT_EXPIRY_FAILED"
    }
}
