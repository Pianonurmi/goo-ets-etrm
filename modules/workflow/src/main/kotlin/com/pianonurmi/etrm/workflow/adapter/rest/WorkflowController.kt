package com.pianonurmi.etrm.workflow.adapter.rest

import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import com.pianonurmi.etrm.workflow.api.WorkflowQuery
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/workflow")
@Tag(name = "Workflow", description = "Approval inbox with four-eyes control")
class WorkflowController(private val query: WorkflowQuery, private val command: WorkflowCommand) {
    @GetMapping("/tasks")
    fun inbox(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(defaultValue = "OPEN") status: TaskStatus,
        @RequestParam(defaultValue = "200") limit: Int,
    ): List<ApprovalTaskView> = query.inbox(legalEntityId, status, limit)

    @GetMapping("/tasks/{id}")
    fun task(
        @PathVariable id: UUID,
    ): ApprovalTaskView? = query.task(id)

    @PostMapping("/tasks/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: ReasonRequest?,
    ): ApprovalTaskView = command.decide(id, Decision.APPROVE, body?.reason)

    @PostMapping("/tasks/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): ApprovalTaskView = command.decide(id, Decision.REJECT, body.reason)

    @PostMapping("/tasks/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): ApprovalTaskView = command.cancel(id, body.reason ?: "cancelled by operator")
}

data class ReasonRequest(val reason: String? = null)
