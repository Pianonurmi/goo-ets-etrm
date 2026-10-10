package com.pianonurmi.etrm.workflow.adapter.rest

import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.BulkDecisionResult
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.InboxFilter
import com.pianonurmi.etrm.workflow.api.InboxPage
import com.pianonurmi.etrm.workflow.api.InboxSort
import com.pianonurmi.etrm.workflow.api.TaskCommentView
import com.pianonurmi.etrm.workflow.api.TaskDetails
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import com.pianonurmi.etrm.workflow.api.WorkflowQuery
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class DecisionRequest(val reason: String? = null)

data class BulkDecisionRequest(val taskIds: List<UUID>, val decision: Decision, val reason: String? = null)

data class AssignRequest(val assignee: String)

data class CommentRequest(val body: String)

data class TaskCancelRequest(val reason: String)

@RestController
@RequestMapping("/api/v1/workflow")
@Tag(name = "Workflow", description = "Approval inbox: tasks registered by the owning modules, with four-eyes, assignment, SLA and escalation")
class WorkflowController(private val query: WorkflowQuery, private val command: WorkflowCommand) {
    @GetMapping("/tasks")
    @Suppress("LongParameterList")
    fun inbox(
        @RequestParam(defaultValue = "OPEN") status: TaskStatus,
        @RequestParam(required = false) type: Set<String>?,
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(required = false) assignee: String?,
        @RequestParam(defaultValue = "false") mine: Boolean,
        @RequestParam(defaultValue = "false") actionable: Boolean,
        @RequestParam(required = false) subjectType: String?,
        @RequestParam(required = false) subjectRef: UUID?,
        @RequestParam(required = false) requestedBy: String?,
        @RequestParam(required = false) requestedFrom: Instant?,
        @RequestParam(required = false) requestedTo: Instant?,
        @RequestParam(required = false) overdue: Boolean?,
        @RequestParam(defaultValue = "DUE") sort: InboxSort,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): InboxPage = query.inbox(
        InboxFilter(
            status,
            type.orEmpty(),
            legalEntityId,
            assignee,
            mine,
            actionable,
            subjectType,
            subjectRef,
            requestedBy,
            requestedFrom,
            requestedTo,
            overdue,
            sort,
            limit,
            cursor,
        ),
    )

    @GetMapping("/tasks/counts")
    fun counts(
        @RequestParam(defaultValue = "true") actionable: Boolean,
    ): Map<String, Int> = query.counts(actionable)

    @GetMapping("/task-types")
    fun taskTypes(): List<TaskTypeDefinition> = query.definitions()

    @GetMapping("/tasks/{id}")
    fun task(
        @PathVariable id: UUID,
    ): TaskDetails = query.details(id)

    @PostMapping("/tasks/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: DecisionRequest?,
    ): ApprovalTaskView = command.decide(id, Decision.APPROVE, body?.reason)

    @PostMapping("/tasks/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody body: DecisionRequest,
    ): ApprovalTaskView = command.decide(id, Decision.REJECT, body.reason)

    @PostMapping("/tasks/{id}/acknowledge")
    fun acknowledge(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: DecisionRequest?,
    ): ApprovalTaskView = command.decide(id, Decision.ACKNOWLEDGE, body?.reason)

    @PostMapping("/tasks/bulk-decision")
    fun bulk(
        @RequestBody body: BulkDecisionRequest,
    ): List<BulkDecisionResult> = command.decideAll(body.taskIds, body.decision, body.reason)

    @PostMapping("/tasks/{id}/claim")
    fun claim(
        @PathVariable id: UUID,
    ): ApprovalTaskView = command.claim(id)

    @PostMapping("/tasks/{id}/assign")
    fun assign(
        @PathVariable id: UUID,
        @RequestBody body: AssignRequest,
    ): ApprovalTaskView = command.assign(id, body.assignee)

    @PostMapping("/tasks/{id}/release")
    fun release(
        @PathVariable id: UUID,
    ): ApprovalTaskView = command.release(id)

    @PostMapping("/tasks/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun comment(
        @PathVariable id: UUID,
        @RequestBody body: CommentRequest,
    ): TaskCommentView = command.comment(id, body.body)

    @PostMapping("/tasks/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: TaskCancelRequest,
    ): ApprovalTaskView = command.cancel(id, body.reason)
}
