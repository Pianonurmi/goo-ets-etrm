package com.pianonurmi.etrm.trade.adapter.workflow

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
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

/** Trade approvals in the inbox: opened when a trade waits for approval, decided by middle office or risk. */
@Component
class TradeApprovalTasks(private val workflow: WorkflowCommand, private val trades: TradeCommand) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "trade",
        displayName = "Trade approval",
        candidateRoles = setOf(Role.MIDDLE_OFFICE, Role.RISK),
        defaultSla = Duration.ofHours(4),
        escalationRoles = setOf(Role.MIDDLE_OFFICE, Role.RISK),
        defaultPriority = 70,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> trades.approve(task.subjectRef)
            Decision.REJECT -> trades.reject(task.subjectRef, reason ?: throw ValidationException("A rejection needs a reason"))
            Decision.ACKNOWLEDGE -> throw ValidationException("Trade approvals are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    /** Withdrawing a trade approval cancels the trade that waits for it. */
    override fun onWithdraw(task: ApprovalTaskView, reason: String): Boolean {
        trades.cancel(task.subjectRef, "withdrawn from approval: $reason")
        return true
    }

    @EventListener
    fun on(event: TradeCaptured) = open(event.trade)

    @EventListener
    fun on(event: TradeAmended) = open(event.trade)

    @EventListener
    fun on(event: TradeApproved) {
        workflow.closeFor(TYPE, SUBJECT, event.trade.id, TaskStatus.APPROVED, "approved outside the inbox")
    }

    @EventListener
    fun on(event: TradeCancelled) {
        workflow.closeFor(TYPE, SUBJECT, event.trade.id, TaskStatus.CANCELLED, event.reason)
    }

    private fun open(trade: TradeView) {
        if (trade.status != TradeStatus.PENDING_APPROVAL) return
        workflow.open(
            OpenTask(
                type = TYPE,
                subjectType = SUBJECT,
                subjectRef = trade.id,
                legalEntityId = trade.legalEntityId,
                summary = "Trade ${trade.tradeNumber}: " +
                    trade.legs.joinToString { "${it.side} ${it.quantity.toPlainString()} ${it.unit} @ ${it.price} ${it.priceCurrency}" } +
                    if (trade.requiresRiskApproval) " (risk approval required)" else "",
                requestedBy = trade.trader,
                payload = mapOf(
                    "tradeNumber" to trade.tradeNumber,
                    "notional" to trade.notional,
                    "counterpartyId" to trade.counterpartyId.toString(),
                    "findings" to trade.approvalFindings.map { mapOf("code" to it.code, "severity" to it.severity.name, "message" to it.message) },
                ),
            ),
        )
    }

    companion object {
        const val TYPE = "TRADE_APPROVAL"
        const val SUBJECT = "TRADE"
    }
}
