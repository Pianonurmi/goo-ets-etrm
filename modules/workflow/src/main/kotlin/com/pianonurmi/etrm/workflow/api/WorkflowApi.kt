package com.pianonurmi.etrm.workflow.api

import java.time.Instant
import java.util.UUID

enum class TaskType { TRADE_APPROVAL, ALLOCATION_APPROVAL, GENERIC }

enum class TaskStatus { OPEN, APPROVED, REJECTED, CANCELLED }

enum class Decision { APPROVE, REJECT }

data class ApprovalTaskView(
    val id: UUID,
    val type: TaskType,
    val subjectType: String,
    val subjectRef: UUID,
    val legalEntityId: UUID?,
    val summary: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val status: TaskStatus,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val reason: String?,
    val payload: Map<String, Any?>,
    val version: Int,
)

data class OpenTask(
    val type: TaskType,
    val subjectType: String,
    val subjectRef: UUID,
    val legalEntityId: UUID?,
    val summary: String,
    val requestedBy: String,
    val payload: Map<String, Any?> = emptyMap(),
)

interface WorkflowCommand {
    fun open(command: OpenTask): ApprovalTaskView

    /** Applies the decision; the decider must differ from the requester. Executes the subject's approve/reject. */
    fun decide(taskId: UUID, decision: Decision, reason: String?): ApprovalTaskView

    fun cancel(taskId: UUID, reason: String): ApprovalTaskView
}

interface WorkflowQuery {
    fun task(id: UUID): ApprovalTaskView?

    fun inbox(legalEntityId: UUID? = null, status: TaskStatus = TaskStatus.OPEN, limit: Int = 200): List<ApprovalTaskView>

    fun openFor(subjectType: String, subjectRef: UUID): ApprovalTaskView?
}
