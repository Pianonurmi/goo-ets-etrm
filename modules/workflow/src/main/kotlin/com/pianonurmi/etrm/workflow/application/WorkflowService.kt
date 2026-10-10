package com.pianonurmi.etrm.workflow.application

import com.pianonurmi.etrm.allocation.api.AllocationApproved
import com.pianonurmi.etrm.allocation.api.AllocationCommand
import com.pianonurmi.etrm.allocation.api.AllocationProposed
import com.pianonurmi.etrm.allocation.api.AllocationRejected
import com.pianonurmi.etrm.allocation.api.AllocationStale
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskType
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import com.pianonurmi.etrm.workflow.api.WorkflowQuery
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
class WorkflowService(
    private val repository: ApprovalTaskRepository,
    private val trades: TradeCommand,
    private val allocation: AllocationCommand,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : WorkflowCommand, WorkflowQuery {
    override fun open(command: OpenTask): ApprovalTaskView {
        repository.openFor(command.subjectType, command.subjectRef)?.let { return it }
        val task = ApprovalTaskView(
            id = Ids.newId(),
            type = command.type,
            subjectType = command.subjectType,
            subjectRef = command.subjectRef,
            legalEntityId = command.legalEntityId,
            summary = command.summary,
            requestedBy = command.requestedBy,
            requestedAt = clock.instant(),
            status = TaskStatus.OPEN,
            decidedBy = null,
            decidedAt = null,
            reason = null,
            payload = command.payload,
            version = 1,
        )
        repository.insert(task)
        return task
    }

    override fun decide(taskId: UUID, decision: Decision, reason: String?): ApprovalTaskView {
        val task = repository.lock(taskId) ?: throw NotFoundException("ApprovalTask", taskId)
        if (task.status != TaskStatus.OPEN) throw ValidationException("Task $taskId is already ${task.status}")
        val actor = actorProvider.current().userId
        if (actor == task.requestedBy) throw SegregationOfDutiesException("Task $taskId cannot be decided by its requester ($actor)")
        if (decision == Decision.REJECT && reason.isNullOrBlank()) throw ValidationException("A rejection needs a reason")
        when (task.type) {
            TaskType.TRADE_APPROVAL -> if (decision == Decision.APPROVE) trades.approve(task.subjectRef) else trades.reject(task.subjectRef, reason!!)
            TaskType.ALLOCATION_APPROVAL -> if (decision == Decision.APPROVE) {
                allocation.approve(
                    task.subjectRef,
                )
            } else {
                allocation.reject(task.subjectRef, reason!!)
            }
            TaskType.GENERIC -> Unit
        }
        // The module's own event listener may already have closed the task; re-read and finish if still open.
        val current = repository.lock(taskId) ?: task
        if (current.status != TaskStatus.OPEN) return current
        return close(current, if (decision == Decision.APPROVE) TaskStatus.APPROVED else TaskStatus.REJECTED, actor, reason)
    }

    override fun cancel(taskId: UUID, reason: String): ApprovalTaskView {
        val task = repository.lock(taskId) ?: throw NotFoundException("ApprovalTask", taskId)
        if (task.status != TaskStatus.OPEN) return task
        return close(task, TaskStatus.CANCELLED, actorProvider.current().userId, reason)
    }

    @Transactional(readOnly = true)
    override fun task(id: UUID): ApprovalTaskView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun inbox(legalEntityId: UUID?, status: TaskStatus, limit: Int): List<ApprovalTaskView> = repository.inbox(legalEntityId, status, limit)

    @Transactional(readOnly = true)
    override fun openFor(subjectType: String, subjectRef: UUID): ApprovalTaskView? = repository.openFor(subjectType, subjectRef)

    // ----------------------------------------------------------------------------------------------------------
    // Mirror pending approvals from trade and allocation.
    // ----------------------------------------------------------------------------------------------------------

    @EventListener
    fun on(event: TradeCaptured) = openTradeTask(event.trade)

    @EventListener
    fun on(event: TradeAmended) = openTradeTask(event.trade)

    @EventListener
    fun on(event: TradeApproved) = closeOpen("TRADE", event.trade.id, TaskStatus.APPROVED, event.trade.approvedBy ?: "system", null)

    @EventListener
    fun on(event: TradeCancelled) = closeOpen("TRADE", event.trade.id, TaskStatus.CANCELLED, actorProvider.current().userId, event.reason)

    @EventListener
    fun on(event: AllocationProposed) {
        open(
            OpenTask(
                TaskType.ALLOCATION_APPROVAL,
                "ALLOCATION_PLAN",
                event.plan.id,
                event.plan.holderEntityId,
                "Allocation plan ${event.plan.id}: ${event.plan.lines.size} lines, ${event.plan.allocatedMwh} MWh",
                event.plan.proposedBy,
                mapOf(
                    "allocatedMwh" to event.plan.allocatedMwh,
                    "lines" to event.plan.lines.size,
                    "coverageShortfall" to event.plan.coverage?.totalShortfallMwh,
                ),
            ),
        )
    }

    @EventListener
    fun on(event: AllocationApproved) = closeOpen("ALLOCATION_PLAN", event.plan.id, TaskStatus.APPROVED, event.plan.approvedBy ?: "system", null)

    @EventListener
    fun on(event: AllocationRejected) = closeOpen("ALLOCATION_PLAN", event.plan.id, TaskStatus.REJECTED, event.plan.decidedBy ?: "system", event.reason)

    @EventListener
    fun on(event: AllocationStale) = closeOpen("ALLOCATION_PLAN", event.plan.id, TaskStatus.CANCELLED, "system", event.reason)

    private fun openTradeTask(trade: TradeView) {
        if (trade.status != TradeStatus.PENDING_APPROVAL) return
        open(
            OpenTask(
                TaskType.TRADE_APPROVAL,
                "TRADE",
                trade.id,
                trade.legalEntityId,
                "Trade ${trade.tradeNumber}: ${trade.legs.joinToString {
                    "${it.side} ${it.quantity.toPlainString()} ${it.unit} @ ${it.price} ${it.priceCurrency}"
                }}",
                trade.trader,
                mapOf("tradeNumber" to trade.tradeNumber, "notional" to trade.notional, "counterpartyId" to trade.counterpartyId.toString()),
            ),
        )
    }

    private fun closeOpen(subjectType: String, subjectRef: UUID, status: TaskStatus, by: String, reason: String?) {
        repository.openFor(subjectType, subjectRef)?.let { close(it, status, by, reason) }
    }

    private fun close(task: ApprovalTaskView, status: TaskStatus, by: String, reason: String?): ApprovalTaskView {
        val closed = task.copy(status = status, decidedBy = by, decidedAt = clock.instant(), reason = reason, version = task.version + 1)
        if (!repository.update(closed)) throw ValidationException("Task ${task.id} changed concurrently")
        return closed
    }
}

interface ApprovalTaskRepository {
    fun find(id: UUID): ApprovalTaskView?

    fun lock(id: UUID): ApprovalTaskView?

    fun openFor(subjectType: String, subjectRef: UUID): ApprovalTaskView?

    fun inbox(legalEntityId: UUID?, status: TaskStatus, limit: Int): List<ApprovalTaskView>

    fun insert(task: ApprovalTaskView)

    fun update(task: ApprovalTaskView): Boolean
}
