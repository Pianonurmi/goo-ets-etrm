package com.pianonurmi.etrm.workflow.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.paging.page
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.BulkDecisionResult
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.InboxFilter
import com.pianonurmi.etrm.workflow.api.InboxPage
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskAssigned
import com.pianonurmi.etrm.workflow.api.TaskClosed
import com.pianonurmi.etrm.workflow.api.TaskCommentView
import com.pianonurmi.etrm.workflow.api.TaskDecided
import com.pianonurmi.etrm.workflow.api.TaskDetails
import com.pianonurmi.etrm.workflow.api.TaskEscalated
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskOpened
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import com.pianonurmi.etrm.workflow.api.WorkflowQuery
import com.pianonurmi.etrm.workflow.domain.ApprovalTaskRepository
import com.pianonurmi.etrm.workflow.domain.TaskNotifier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Generic approval inbox. Task types, their rules and what a decision does belong to the owning modules
 * ([TaskHandler]); this service enforces roles, four-eyes, legal-entity scope and reasons, keeps assignment,
 * comments, deadlines and an append-only history, and escalates overdue tasks.
 */
@Service
@Transactional
class WorkflowService(
    private val repository: ApprovalTaskRepository,
    private val handlerProvider: ObjectProvider<TaskHandler>,
    private val savepoints: Savepoints,
    private val notifier: TaskNotifier,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : WorkflowCommand, WorkflowQuery {
    // Resolved lazily: handlers live in modules that themselves use the workflow API.
    private val handlers: Map<String, TaskHandler> by lazy {
        val all = handlerProvider.orderedStream().toList()
        all.groupBy { it.definition.taskType }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let {
            error("Task types registered more than once: $it")
        }
        all.associateBy { it.definition.taskType }
    }

    /** Records the registered task types so administrators and reports can see them. */
    @EventListener(ApplicationReadyEvent::class)
    fun registerDefinitions() {
        val now = now()
        handlers.values.forEach { repository.upsertDefinition(it.definition, now) }
    }

    override fun open(command: OpenTask): ApprovalTaskView {
        val definition = definition(command.type)
        repository.openFor(command.type, command.subjectType, command.subjectRef)?.let { return it }
        val now = now()
        val task = ApprovalTaskView(
            id = Ids.newId(),
            type = command.type,
            subjectType = command.subjectType,
            subjectRef = command.subjectRef,
            legalEntityId = command.legalEntityId,
            summary = command.summary,
            requestedBy = command.requestedBy,
            requestedAt = now,
            status = TaskStatus.OPEN,
            decision = null,
            decidedBy = null,
            decidedAt = null,
            reason = null,
            assignee = null,
            claimedAt = null,
            priority = (command.priority ?: definition.defaultPriority).coerceIn(0, 100),
            dueAt = command.dueAt ?: now.plus(definition.defaultSla),
            escalationLevel = 0,
            candidateRoles = definition.candidateRoles,
            payload = command.payload,
            version = 1,
        )
        // A concurrent open of the same task committed first: return that one instead of failing on the unique index.
        if (!repository.insert(task)) {
            return repository.openFor(command.type, command.subjectType, command.subjectRef)
                ?: throw ConflictException("An open ${command.type} task for ${command.subjectType} ${command.subjectRef} was just closed; retry")
        }
        history(task, "OPENED", command.requestedBy, mapOf("dueAt" to task.dueAt.toString(), "priority" to task.priority))
        events.publishEvent(TaskOpened(task))
        notifier.opened(task)
        return task
    }

    override fun decide(taskId: UUID, decision: Decision, reason: String?): ApprovalTaskView {
        val actor = actorProvider.current()
        val task = locked(taskId)
        if (task.status != TaskStatus.OPEN) throw ConflictException("Task $taskId is already ${task.status}")
        val definition = definition(task.type)
        actor.requireEntity(task.legalEntityId)
        if (decision !in definition.allowedDecisions) throw ValidationException("${task.type} tasks cannot be decided with $decision")
        if (!isCandidate(actor, task)) throw ForbiddenException("User ${actor.userId} may not decide ${task.type} tasks")
        if (definition.fourEyes) SecondPerson.require(actor.userId, task.requestedBy) { "Task $taskId cannot be decided by its requester (${actor.userId})" }
        if (decision in definition.reasonRequiredFor && reason.isNullOrBlank()) throw ValidationException("A $decision decision needs a reason")

        val decided = save(
            task.copy(
                status = decision.status,
                decision = decision,
                decidedBy = actor.userId,
                decidedAt = now(),
                reason = reason,
                version = task.version + 1,
            ),
        )
        history(decided, "DECIDED", actor.userId, mapOf("decision" to decision.name, "reason" to reason))
        val final = when (val outcome = handlers.getValue(task.type).onDecision(decided, decision, reason)) {
            DecisionOutcome.Applied -> decided
            is DecisionOutcome.Superseded -> {
                val superseded = save(decided.copy(status = TaskStatus.CANCELLED, reason = "superseded: ${outcome.reason}", version = decided.version + 1))
                history(superseded, "SUPERSEDED", actor.userId, mapOf("reason" to outcome.reason))
                superseded
            }
        }
        events.publishEvent(TaskDecided(final))
        return final
    }

    override fun decideAll(taskIds: List<UUID>, decision: Decision, reason: String?): List<BulkDecisionResult> {
        if (taskIds.size > MAX_BULK) throw ValidationException("At most $MAX_BULK tasks can be decided at once")
        return taskIds.distinct().map { id ->
            try {
                val task = savepoints.attempt { decide(id, decision, reason) }
                BulkDecisionResult(id, true, task.status, null)
            } catch (ex: DomainException) {
                BulkDecisionResult(id, false, null, ex.message)
            }
        }
    }

    override fun claim(taskId: UUID): ApprovalTaskView {
        val actor = actorProvider.current()
        val task = lockedOpen(taskId)
        actor.requireEntity(task.legalEntityId)
        if (!isCandidate(actor, task)) throw ForbiddenException("User ${actor.userId} may not work on ${task.type} tasks")
        if (task.assignee != null && task.assignee != actor.userId) throw ConflictException("Task $taskId is assigned to ${task.assignee}")
        if (task.assignee == actor.userId) return task
        return reassign(task, actor.userId, actor, "CLAIMED")
    }

    override fun assign(taskId: UUID, assignee: String): ApprovalTaskView {
        if (assignee.isBlank()) throw ValidationException("Assignee is required")
        val actor = actorProvider.current()
        val task = lockedOpen(taskId)
        actor.requireEntity(task.legalEntityId)
        if (!mayManage(actor, task)) throw ForbiddenException("User ${actor.userId} may not assign task $taskId")
        if (task.assignee == assignee) return task
        return reassign(task, assignee, actor, "ASSIGNED")
    }

    override fun release(taskId: UUID): ApprovalTaskView {
        val actor = actorProvider.current()
        val task = lockedOpen(taskId)
        if (task.assignee == null) return task
        if (!mayManage(actor, task)) throw ForbiddenException("User ${actor.userId} may not release task $taskId")
        return reassign(task, null, actor, "RELEASED")
    }

    override fun comment(taskId: UUID, body: String): TaskCommentView {
        val text = body.trim()
        if (text.isEmpty() || text.length > MAX_COMMENT) throw ValidationException("Comment must be 1..$MAX_COMMENT characters")
        val actor = actorProvider.current()
        val task = locked(taskId)
        actor.requireEntity(task.legalEntityId)
        val comment = TaskCommentView(Ids.newId(), taskId, actor.userId, text, now())
        repository.insertComment(comment)
        history(task, "COMMENTED", actor.userId, mapOf("commentId" to comment.id.toString()))
        return comment
    }

    override fun cancel(taskId: UUID, reason: String): ApprovalTaskView {
        if (reason.isBlank()) throw ValidationException("A cancellation needs a reason")
        val actor = actorProvider.current()
        val task = locked(taskId)
        if (task.status != TaskStatus.OPEN) return task
        actor.requireEntity(task.legalEntityId)
        if (actor.userId != task.requestedBy && !mayManage(actor, task)) throw ForbiddenException("User ${actor.userId} may not cancel task $taskId")
        val handler = handlers[task.type]
        if (handler != null) {
            if (!handler.onWithdraw(task, reason)) {
                throw ConflictException("A ${handler.definition.displayName.lowercase()} cannot be cancelled; approve or reject it instead")
            }
            // Withdrawing the subject usually closes the task already (closeFor).
            val after = locked(taskId)
            if (after.status != TaskStatus.OPEN) return after
            return close(after, TaskStatus.CANCELLED, actor.userId, reason)
        }
        return close(task, TaskStatus.CANCELLED, actor.userId, reason)
    }

    override fun closeFor(type: String, subjectType: String, subjectRef: UUID, status: TaskStatus, reason: String?): ApprovalTaskView? {
        if (status == TaskStatus.OPEN) throw ValidationException("closeFor needs a terminal status")
        val open = repository.openFor(type, subjectType, subjectRef) ?: return null
        val task = repository.lock(open.id) ?: return null
        if (task.status != TaskStatus.OPEN) return task
        return close(task, status, actorProvider.current().userId, reason)
    }

    override fun escalateOverdue(now: Instant): Int {
        var escalated = 0
        repository.lockOverdue(now, ESCALATION_BATCH).forEach { task ->
            val definition = handlers[task.type]?.definition ?: return@forEach
            // Level n is reached once the task is n SLA periods past its due date (level 1 at the due date).
            val periodsOverdue = 1 + ChronoUnit.MINUTES.between(task.dueAt, now) / definition.defaultSla.toMinutes().coerceAtLeast(1)
            val level = periodsOverdue.coerceAtMost(MAX_ESCALATION.toLong()).toInt()
            if (level <= task.escalationLevel) return@forEach
            val next = save(task.copy(escalationLevel = level, version = task.version + 1))
            history(next, "ESCALATED", Actor.SYSTEM.userId, mapOf("level" to level))
            events.publishEvent(TaskEscalated(next))
            notifier.escalated(next, definition.escalationRoles)
            escalated++
        }
        return escalated
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun task(id: UUID): ApprovalTaskView? = repository.find(id)?.also { actorProvider.current().requireEntity(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun details(id: UUID): TaskDetails {
        val task = task(id) ?: throw NotFoundException("ApprovalTask", id)
        return TaskDetails(task, repository.comments(id), repository.events(id))
    }

    @Transactional(readOnly = true)
    override fun inbox(filter: InboxFilter): InboxPage {
        if (filter.limit !in 1..MAX_PAGE) throw ValidationException("limit must be 1..$MAX_PAGE")
        val actor = actorProvider.current()
        filter.legalEntityId?.let { actor.requireEntity(it) }
        val rows = repository.page(filter, actor, now())
        val (items, next) = rows.page(filter.limit) { InboxCursor.encode(filter.sort, it) }
        return InboxPage(items, next)
    }

    @Transactional(readOnly = true)
    override fun counts(actionable: Boolean): Map<String, Int> = repository.counts(actorProvider.current(), actionable)

    @Transactional(readOnly = true)
    override fun openFor(type: String, subjectType: String, subjectRef: UUID): ApprovalTaskView? = repository.openFor(type, subjectType, subjectRef)

    override fun definitions(): List<TaskTypeDefinition> = handlers.values.map { it.definition }.sortedBy { it.taskType }

    // ----------------------------------------------------------------------------------------------------------

    private fun definition(type: String): TaskTypeDefinition = handlers[type]?.definition ?: throw ValidationException("Unknown task type $type")

    /**
     * A decision needs one of the task's candidate roles held explicitly: ADMIN administers the inbox (assigns,
     * escalates) but does not stand in for the business roles that four eyes require.
     */
    private fun isCandidate(actor: Actor, task: ApprovalTaskView): Boolean = task.candidateRoles.any { it in actor.roles }

    private fun mayManage(actor: Actor, task: ApprovalTaskView): Boolean =
        actor.has(Role.ADMIN) || actor.userId == task.assignee || (handlers[task.type]?.definition?.escalationRoles.orEmpty()).any { actor.has(it) }

    private fun reassign(task: ApprovalTaskView, assignee: String?, actor: Actor, eventType: String): ApprovalTaskView {
        val next = save(task.copy(assignee = assignee, claimedAt = assignee?.let { now() }, version = task.version + 1))
        history(next, eventType, actor.userId, mapOf("from" to task.assignee, "to" to assignee))
        events.publishEvent(TaskAssigned(next, task.assignee))
        if (assignee != null) notifier.assigned(next)
        return next
    }

    private fun close(task: ApprovalTaskView, status: TaskStatus, by: String, reason: String?): ApprovalTaskView {
        val closed = save(task.copy(status = status, decidedBy = by, decidedAt = now(), reason = reason, version = task.version + 1))
        history(closed, "CLOSED", by, mapOf("status" to status.name, "reason" to reason))
        events.publishEvent(TaskClosed(closed))
        return closed
    }

    private fun save(task: ApprovalTaskView): ApprovalTaskView {
        if (!repository.update(task)) throw ConflictException("Task ${task.id} changed concurrently")
        return task
    }

    private fun history(task: ApprovalTaskView, eventType: String, actor: String, data: Map<String, Any?>) {
        repository.appendEvent(task.id, eventType, actor, now(), data)
    }

    private fun locked(id: UUID): ApprovalTaskView = repository.lock(id) ?: throw NotFoundException("ApprovalTask", id)

    private fun lockedOpen(id: UUID): ApprovalTaskView = locked(id).also {
        if (it.status != TaskStatus.OPEN) throw ConflictException("Task $id is ${it.status}")
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val MAX_PAGE = 500
        const val MAX_BULK = 100
        const val MAX_COMMENT = 4000
        const val MAX_ESCALATION = 3
        const val ESCALATION_BATCH = 500
    }
}
