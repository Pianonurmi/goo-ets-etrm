package com.pianonurmi.etrm.trade.adapter.workflow

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.security.Roles
import com.pianonurmi.etrm.trade.api.ConfirmationClosed
import com.pianonurmi.etrm.trade.api.ConfirmationMismatched
import com.pianonurmi.etrm.trade.api.ConfirmationStatus
import com.pianonurmi.etrm.trade.api.DifferenceSeverity
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.application.ConfirmationService
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

private const val SUBJECT = "TRADE_CONFIRMATION"

/**
 * A counterparty confirmation that differs from ours. Approving overrides the differences (a second person: not the
 * one who received the confirmation) and confirms the trade; rejecting disputes the confirmation.
 */
@Component
class ConfirmationBreakTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val confirmations: ConfirmationService,
    private val trades: TradeQuery,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "trade",
        displayName = "Confirmation break",
        candidateRoles = Roles.OPERATIONS,
        defaultSla = Duration.ofHours(24),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 70,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> confirmations.override(task.subjectRef, task.decidedBy!!, reason)
            Decision.REJECT -> confirmations.dispute(task.subjectRef, task.decidedBy!!, reason)
            Decision.ACKNOWLEDGE -> throw ValidationException("A confirmation break is overridden (approve) or disputed (reject)")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: ConfirmationMismatched) {
        val c = event.confirmation
        val mismatches = c.differences.filter { it.severity == DifferenceSeverity.MISMATCH }
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                c.id,
                trades.requireTrade(c.tradeId).legalEntityId,
                "Confirmation ${c.reference} for ${c.tradeNumber} differs: ${mismatches.joinToString { it.field }}",
                c.createdBy,
                mapOf(
                    "tradeId" to c.tradeId.toString(),
                    "differences" to mismatches.map {
                        mapOf("field" to it.field, "ours" to it.ours, "theirs" to it.theirs)
                    },
                ),
            ),
        )
    }

    /** Superseded by a newer confirmation, or re-opened by an amendment of the trade. */
    @EventListener
    fun on(event: ConfirmationClosed) {
        val c = event.confirmation
        val status = if (c.status == ConfirmationStatus.SUPERSEDED || c.status == ConfirmationStatus.RECEIVED) TaskStatus.CANCELLED else TaskStatus.COMPLETED
        workflow.closeFor(TYPE, SUBJECT, c.id, status, c.note ?: c.status.name)
    }

    companion object {
        const val TYPE = "CONFIRMATION_BREAK"
    }
}
