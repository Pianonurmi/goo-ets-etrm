package com.pianonurmi.etrm.workflow.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.security.Role
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class TaskStatus {
    OPEN,
    APPROVED,
    REJECTED,
    ACKNOWLEDGED,

    /** Closed by the owning module because the underlying work was done elsewhere (e.g. a break resolved). */
    COMPLETED,
    CANCELLED,
    ;

    val terminal: Boolean get() = this != OPEN
}

enum class Decision(val status: TaskStatus) {
    APPROVE(TaskStatus.APPROVED),
    REJECT(TaskStatus.REJECTED),

    /** For information and work tasks: the task was seen and handled. */
    ACKNOWLEDGE(TaskStatus.ACKNOWLEDGED),
}

/**
 * A task type registered by its owning module through a [TaskHandler]. The workflow module knows no task types of
 * its own; it enforces the rules declared here.
 */
data class TaskTypeDefinition(
    val taskType: String,
    val ownerModule: String,
    val displayName: String,
    /** Roles that may decide (and therefore see in their actionable inbox) tasks of this type. ADMIN always may. */
    val candidateRoles: Set<Role>,
    /** The requester may never decide their own task. */
    val fourEyes: Boolean = true,
    val allowedDecisions: Set<Decision> = setOf(Decision.APPROVE, Decision.REJECT),
    val reasonRequiredFor: Set<Decision> = setOf(Decision.REJECT),
    val defaultSla: Duration = Duration.ofHours(24),
    /** Roles notified on escalation and allowed to reassign tasks of this type. */
    val escalationRoles: Set<Role> = setOf(Role.MIDDLE_OFFICE),
    val defaultPriority: Int = 50,
) {
    init {
        require(TASK_TYPE.matches(taskType)) { "Task type must match ${TASK_TYPE.pattern}" }
        require(candidateRoles.isNotEmpty()) { "Task type $taskType needs at least one candidate role" }
        require(defaultPriority in 0..100) { "Priority must be 0..100" }
    }

    companion object {
        val TASK_TYPE = Regex("^[A-Z][A-Z0-9_]{2,59}$")
    }
}

/** The outcome reported by the owning module when it applied a decision. */
sealed interface DecisionOutcome {
    data object Applied : DecisionOutcome

    /** The decision could not take effect because the subject changed (e.g. a stale allocation plan); the task is cancelled. */
    data class Superseded(val reason: String) : DecisionOutcome
}

/**
 * Implemented by the module that owns a task type. [onDecision] runs in the deciding transaction after the workflow
 * rules (roles, four-eyes, scope, reasons) passed; throwing vetoes the decision and rolls everything back.
 */
interface TaskHandler {
    val definition: TaskTypeDefinition

    fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome

    /**
     * The task is cancelled (its requester withdraws the request): undo the request on the subject in the same
     * transaction and return true, or return false when this type cannot be withdrawn, because its subject would be
     * left waiting for a decision that never comes. The default refuses.
     */
    fun onWithdraw(task: ApprovalTaskView, reason: String): Boolean = false
}

data class ApprovalTaskView(
    val id: UUID,
    val type: String,
    val subjectType: String,
    val subjectRef: UUID,
    val legalEntityId: UUID?,
    val summary: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val status: TaskStatus,
    val decision: Decision?,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val reason: String?,
    val assignee: String?,
    val claimedAt: Instant?,
    val priority: Int,
    val dueAt: Instant,
    val escalationLevel: Int,
    val candidateRoles: Set<Role>,
    val payload: Map<String, Any?>,
    val version: Int,
) {
    fun overdue(now: Instant): Boolean = status == TaskStatus.OPEN && dueAt.isBefore(now)
}

data class TaskCommentView(val id: UUID, val taskId: UUID, val author: String, val body: String, val createdAt: Instant)

data class TaskEventView(
    val seq: Long,
    val taskId: UUID,
    val eventType: String,
    val actor: String,
    val at: Instant,
    val data: Map<String, Any?>,
    val hash: String,
)

data class TaskDetails(val task: ApprovalTaskView, val comments: List<TaskCommentView>, val history: List<TaskEventView>)

data class OpenTask(
    val type: String,
    val subjectType: String,
    val subjectRef: UUID,
    val legalEntityId: UUID?,
    val summary: String,
    val requestedBy: String,
    val payload: Map<String, Any?> = emptyMap(),
    /** Overrides the type's default priority (0..100, higher is more urgent). */
    val priority: Int? = null,
    /** Overrides requested-at plus the type's default SLA; owners compute business-day deadlines themselves. */
    val dueAt: Instant? = null,
)

enum class InboxSort { DUE, REQUESTED, PRIORITY }

data class InboxFilter(
    val status: TaskStatus = TaskStatus.OPEN,
    val types: Set<String> = emptySet(),
    val legalEntityId: UUID? = null,
    val assignee: String? = null,
    /** Only tasks assigned to the current user. */
    val mine: Boolean = false,
    /** Only tasks the current user may decide (candidate role, not their own request under four-eyes). */
    val actionable: Boolean = false,
    val subjectType: String? = null,
    val subjectRef: UUID? = null,
    val requestedBy: String? = null,
    val requestedFrom: Instant? = null,
    val requestedTo: Instant? = null,
    val overdue: Boolean? = null,
    val sort: InboxSort = InboxSort.DUE,
    val limit: Int = 50,
    /** Opaque cursor from the previous page. */
    val cursor: String? = null,
)

data class InboxPage(val items: List<ApprovalTaskView>, val nextCursor: String?)

data class BulkDecisionResult(val taskId: UUID, val ok: Boolean, val status: TaskStatus?, val error: String?)

interface WorkflowCommand {
    /** Opens a task; idempotent per (type, subject) while the task is open. */
    fun open(command: OpenTask): ApprovalTaskView

    /** Decides a task under the type's rules, then lets the owning module apply the decision. */
    fun decide(taskId: UUID, decision: Decision, reason: String?): ApprovalTaskView

    /** Decides several tasks; each is decided in its own savepoint, so one refusal does not undo the others. */
    fun decideAll(taskIds: List<UUID>, decision: Decision, reason: String?): List<BulkDecisionResult>

    fun claim(taskId: UUID): ApprovalTaskView

    /** Assigns (or delegates) a task. Allowed for the current assignee, escalation roles of the type and ADMIN. */
    fun assign(taskId: UUID, assignee: String): ApprovalTaskView

    fun release(taskId: UUID): ApprovalTaskView

    fun comment(taskId: UUID, body: String): TaskCommentView

    fun cancel(taskId: UUID, reason: String): ApprovalTaskView

    /**
     * Closes the open task of [type] for a subject when the owning module finished the work another way (e.g. the
     * trade was approved through its own endpoint). No-op when nothing is open.
     */
    fun closeFor(type: String, subjectType: String, subjectRef: UUID, status: TaskStatus, reason: String?): ApprovalTaskView?

    /** Raises the escalation level of overdue tasks; called by the escalation job. Returns the number escalated. */
    fun escalateOverdue(now: Instant): Int
}

interface WorkflowQuery {
    fun task(id: UUID): ApprovalTaskView?

    /** Tasks whose hash-chained history does not verify (for the nightly chain verification and the DR drill). */
    fun verifyEvents(): List<UUID>

    fun details(id: UUID): TaskDetails

    fun inbox(filter: InboxFilter = InboxFilter()): InboxPage

    /** Open task counts per type for the current user's visible (or actionable) tasks. */
    fun counts(actionable: Boolean = true): Map<String, Int>

    fun openFor(type: String, subjectType: String, subjectRef: UUID): ApprovalTaskView?

    fun definitions(): List<TaskTypeDefinition>
}

data class TaskOpened(val task: ApprovalTaskView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class TaskAssigned(val task: ApprovalTaskView, val previousAssignee: String?, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

data class TaskDecided(val task: ApprovalTaskView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class TaskClosed(val task: ApprovalTaskView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class TaskEscalated(val task: ApprovalTaskView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
