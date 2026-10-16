package com.pianonurmi.etrm.workflow.domain

import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.InboxFilter
import com.pianonurmi.etrm.workflow.api.TaskCommentView
import com.pianonurmi.etrm.workflow.api.TaskEventView
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import java.time.Instant
import java.util.UUID

interface ApprovalTaskRepository {
    fun find(id: UUID): ApprovalTaskView?

    fun lock(id: UUID): ApprovalTaskView?

    fun openFor(type: String, subjectType: String, subjectRef: UUID): ApprovalTaskView?

    /** One page of tasks visible to [actor]; returns up to `filter.limit + 1` rows so the caller can tell if more exist. */
    fun page(filter: InboxFilter, actor: Actor, now: Instant): List<ApprovalTaskView>

    fun counts(actor: Actor, actionable: Boolean): Map<String, Int>

    /** Open tasks past their due date, locked with SKIP LOCKED so parallel escalation runs do not collide. */
    fun lockOverdue(now: Instant, limit: Int): List<ApprovalTaskView>

    /** Inserts an open task; false when another one is already open for the same type and subject (the partial unique index). */
    fun insert(task: ApprovalTaskView): Boolean

    /** Optimistic update: succeeds only if the stored version is `task.version - 1`. */
    fun update(task: ApprovalTaskView): Boolean

    fun insertComment(comment: TaskCommentView)

    fun comments(taskId: UUID): List<TaskCommentView>

    fun appendEvent(taskId: UUID, eventType: String, actor: String, at: Instant, data: Map<String, Any?>): TaskEventView

    fun events(taskId: UUID): List<TaskEventView>

    fun upsertDefinition(definition: TaskTypeDefinition, at: Instant)
}

/** Outbound notifications about tasks (e-mail, chat, push). The default adapter only logs. */
interface TaskNotifier {
    fun opened(task: ApprovalTaskView)

    fun assigned(task: ApprovalTaskView)

    fun escalated(task: ApprovalTaskView, notifyRoles: Set<com.pianonurmi.etrm.platform.security.Role>)
}
