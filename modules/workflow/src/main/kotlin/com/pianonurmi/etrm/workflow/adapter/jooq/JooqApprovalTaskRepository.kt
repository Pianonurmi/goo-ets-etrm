package com.pianonurmi.etrm.workflow.adapter.jooq

import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.workflow.adapter.jooq.generated.tables.ApprovalTask.APPROVAL_TASK
import com.pianonurmi.etrm.workflow.adapter.jooq.generated.tables.TaskComment.TASK_COMMENT
import com.pianonurmi.etrm.workflow.adapter.jooq.generated.tables.TaskEvent.TASK_EVENT
import com.pianonurmi.etrm.workflow.adapter.jooq.generated.tables.TaskTypeDefinition.TASK_TYPE_DEFINITION
import com.pianonurmi.etrm.workflow.adapter.jooq.generated.tables.records.ApprovalTaskRecord
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.InboxFilter
import com.pianonurmi.etrm.workflow.api.InboxSort
import com.pianonurmi.etrm.workflow.api.TaskCommentView
import com.pianonurmi.etrm.workflow.api.TaskEventView
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.application.InboxCursor
import com.pianonurmi.etrm.workflow.domain.ApprovalTaskRepository
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.util.UUID

@Repository
class JooqApprovalTaskRepository(private val dsl: DSLContext) : ApprovalTaskRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun find(id: UUID): ApprovalTaskView? = dsl.selectFrom(APPROVAL_TASK).where(APPROVAL_TASK.ID.eq(id)).fetchOne()?.let(::toView)

    override fun lock(id: UUID): ApprovalTaskView? = dsl.selectFrom(APPROVAL_TASK).where(APPROVAL_TASK.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun openFor(type: String, subjectType: String, subjectRef: UUID): ApprovalTaskView? = dsl.selectFrom(APPROVAL_TASK)
        .where(
            APPROVAL_TASK.TASK_TYPE.eq(type),
            APPROVAL_TASK.SUBJECT_TYPE.eq(subjectType),
            APPROVAL_TASK.SUBJECT_REF.eq(subjectRef),
            APPROVAL_TASK.STATUS.eq(TaskStatus.OPEN.name),
        )
        .fetchOne()?.let(::toView)

    override fun page(filter: InboxFilter, actor: Actor, now: Instant): List<ApprovalTaskView> {
        val conditions = mutableListOf(APPROVAL_TASK.STATUS.eq(filter.status.name), visibleTo(actor))
        if (filter.actionable) conditions += actionableBy(actor)
        if (filter.types.isNotEmpty()) conditions += APPROVAL_TASK.TASK_TYPE.`in`(filter.types)
        filter.legalEntityId?.let { conditions += APPROVAL_TASK.LEGAL_ENTITY_ID.eq(it) }
        filter.assignee?.let { conditions += APPROVAL_TASK.ASSIGNEE.eq(it) }
        if (filter.mine) conditions += APPROVAL_TASK.ASSIGNEE.eq(actor.userId)
        filter.subjectType?.let { conditions += APPROVAL_TASK.SUBJECT_TYPE.eq(it) }
        filter.subjectRef?.let { conditions += APPROVAL_TASK.SUBJECT_REF.eq(it) }
        filter.requestedBy?.let { conditions += APPROVAL_TASK.REQUESTED_BY.eq(it) }
        filter.requestedFrom?.let { conditions += APPROVAL_TASK.REQUESTED_AT.ge(it) }
        filter.requestedTo?.let { conditions += APPROVAL_TASK.REQUESTED_AT.lt(it) }
        when (filter.overdue) {
            true -> conditions += APPROVAL_TASK.DUE_AT.lt(now)
            false -> conditions += APPROVAL_TASK.DUE_AT.ge(now)
            null -> Unit
        }
        val cursor = filter.cursor?.let { InboxCursor.decode(it, filter.sort) }
        val base = dsl.selectFrom(APPROVAL_TASK).where(conditions)
        val n = filter.limit + 1
        return when (filter.sort) {
            InboxSort.DUE -> base.orderBy(APPROVAL_TASK.DUE_AT.asc(), APPROVAL_TASK.ID.asc())
                .let { q -> if (cursor != null) q.seek(cursor.instant, cursor.id).limit(n) else q.limit(n) }
            InboxSort.REQUESTED -> base.orderBy(APPROVAL_TASK.REQUESTED_AT.desc(), APPROVAL_TASK.ID.desc())
                .let { q -> if (cursor != null) q.seek(cursor.instant, cursor.id).limit(n) else q.limit(n) }
            InboxSort.PRIORITY -> base.orderBy(APPROVAL_TASK.PRIORITY.desc(), APPROVAL_TASK.DUE_AT.asc(), APPROVAL_TASK.ID.asc())
                .let { q -> if (cursor != null) q.seek(cursor.priority, cursor.instant, cursor.id).limit(n) else q.limit(n) }
        }.fetch(::toView)
    }

    override fun counts(actor: Actor, actionable: Boolean): Map<String, Int> = dsl
        .select(APPROVAL_TASK.TASK_TYPE, DSL.count())
        .from(APPROVAL_TASK)
        .where(APPROVAL_TASK.STATUS.eq(TaskStatus.OPEN.name), visibleTo(actor), if (actionable) actionableBy(actor) else DSL.noCondition())
        .groupBy(APPROVAL_TASK.TASK_TYPE)
        .fetch()
        .associate { it.value1() to it.value2() }

    override fun lockOverdue(now: Instant, limit: Int): List<ApprovalTaskView> = dsl.selectFrom(APPROVAL_TASK)
        .where(APPROVAL_TASK.STATUS.eq(TaskStatus.OPEN.name), APPROVAL_TASK.DUE_AT.lt(now))
        .orderBy(APPROVAL_TASK.DUE_AT)
        .limit(limit)
        .forUpdate()
        .skipLocked()
        .fetch(::toView)

    override fun insert(task: ApprovalTaskView) {
        dsl.insertInto(APPROVAL_TASK)
            .set(APPROVAL_TASK.ID, task.id)
            .set(APPROVAL_TASK.TASK_TYPE, task.type)
            .set(APPROVAL_TASK.SUBJECT_TYPE, task.subjectType)
            .set(APPROVAL_TASK.SUBJECT_REF, task.subjectRef)
            .set(APPROVAL_TASK.LEGAL_ENTITY_ID, task.legalEntityId)
            .set(APPROVAL_TASK.SUMMARY, task.summary)
            .set(APPROVAL_TASK.REQUESTED_BY, task.requestedBy)
            .set(APPROVAL_TASK.REQUESTED_AT, task.requestedAt)
            .set(APPROVAL_TASK.STATUS, task.status.name)
            .set(APPROVAL_TASK.PRIORITY, task.priority)
            .set(APPROVAL_TASK.DUE_AT, task.dueAt)
            .set(APPROVAL_TASK.CANDIDATE_ROLES, task.candidateRoles.map { it.name }.sorted().toTypedArray())
            .set(APPROVAL_TASK.PAYLOAD, JSONB.jsonb(Json.mapper.writeValueAsString(task.payload)))
            .set(APPROVAL_TASK.VERSION, task.version)
            .execute()
    }

    override fun update(task: ApprovalTaskView): Boolean = dsl.update(APPROVAL_TASK)
        .set(APPROVAL_TASK.STATUS, task.status.name)
        .set(APPROVAL_TASK.DECISION, task.decision?.name)
        .set(APPROVAL_TASK.DECIDED_BY, task.decidedBy)
        .set(APPROVAL_TASK.DECIDED_AT, task.decidedAt)
        .set(APPROVAL_TASK.REASON, task.reason)
        .set(APPROVAL_TASK.ASSIGNEE, task.assignee)
        .set(APPROVAL_TASK.CLAIMED_AT, task.claimedAt)
        .set(APPROVAL_TASK.PRIORITY, task.priority)
        .set(APPROVAL_TASK.DUE_AT, task.dueAt)
        .set(APPROVAL_TASK.ESCALATION_LEVEL, task.escalationLevel)
        .set(APPROVAL_TASK.VERSION, task.version)
        .where(APPROVAL_TASK.ID.eq(task.id), APPROVAL_TASK.VERSION.eq(task.version - 1))
        .execute() == 1

    override fun insertComment(comment: TaskCommentView) {
        dsl.insertInto(TASK_COMMENT)
            .set(TASK_COMMENT.ID, comment.id)
            .set(TASK_COMMENT.TASK_ID, comment.taskId)
            .set(TASK_COMMENT.AUTHOR, comment.author)
            .set(TASK_COMMENT.BODY, comment.body)
            .set(TASK_COMMENT.CREATED_AT, comment.createdAt)
            .execute()
    }

    override fun comments(taskId: UUID): List<TaskCommentView> = dsl.selectFrom(TASK_COMMENT)
        .where(TASK_COMMENT.TASK_ID.eq(taskId))
        .orderBy(TASK_COMMENT.CREATED_AT, TASK_COMMENT.ID)
        .fetch { TaskCommentView(it.id, it.taskId, it.author, it.body, it.createdAt) }

    override fun appendEvent(taskId: UUID, eventType: String, actor: String, at: Instant, data: Map<String, Any?>): TaskEventView {
        val previous = dsl.select(TASK_EVENT.HASH).from(TASK_EVENT).where(TASK_EVENT.TASK_ID.eq(taskId))
            .orderBy(TASK_EVENT.SEQ.desc()).limit(1).fetchOne(TASK_EVENT.HASH)
        val payload = Json.canonical(mapOf("taskId" to taskId.toString(), "eventType" to eventType, "actor" to actor, "at" to at.toString(), "data" to data))
        val hash = HashChain.next(previous, payload)
        val record = dsl.insertInto(TASK_EVENT)
            .set(TASK_EVENT.TASK_ID, taskId)
            .set(TASK_EVENT.EVENT_TYPE, eventType)
            .set(TASK_EVENT.ACTOR, actor)
            .set(TASK_EVENT.AT, at)
            .set(TASK_EVENT.DATA, JSONB.jsonb(Json.mapper.writeValueAsString(data)))
            .set(TASK_EVENT.PREV_HASH, previous)
            .set(TASK_EVENT.HASH, hash)
            .returning()
            .fetchOne()!!
        return TaskEventView(record.seq, taskId, eventType, actor, at, data, hash)
    }

    override fun events(taskId: UUID): List<TaskEventView> = dsl.selectFrom(TASK_EVENT)
        .where(TASK_EVENT.TASK_ID.eq(taskId))
        .orderBy(TASK_EVENT.SEQ)
        .fetch { TaskEventView(it.seq, it.taskId, it.eventType, it.actor, it.at, Json.mapper.readValue(it.data.data(), mapType), it.hash) }

    override fun upsertDefinition(definition: TaskTypeDefinition, at: Instant) {
        fun names(values: Collection<Enum<*>>) = values.map { it.name }.sorted().toTypedArray()
        dsl.insertInto(TASK_TYPE_DEFINITION)
            .set(TASK_TYPE_DEFINITION.TASK_TYPE, definition.taskType)
            .set(TASK_TYPE_DEFINITION.OWNER_MODULE, definition.ownerModule)
            .set(TASK_TYPE_DEFINITION.DISPLAY_NAME, definition.displayName)
            .set(TASK_TYPE_DEFINITION.CANDIDATE_ROLES, names(definition.candidateRoles))
            .set(TASK_TYPE_DEFINITION.FOUR_EYES, definition.fourEyes)
            .set(TASK_TYPE_DEFINITION.ALLOWED_DECISIONS, names(definition.allowedDecisions))
            .set(TASK_TYPE_DEFINITION.REASON_REQUIRED, names(definition.reasonRequiredFor))
            .set(TASK_TYPE_DEFINITION.DEFAULT_SLA_MINUTES, definition.defaultSla.toMinutes().toInt())
            .set(TASK_TYPE_DEFINITION.ESCALATION_ROLES, names(definition.escalationRoles))
            .set(TASK_TYPE_DEFINITION.DEFAULT_PRIORITY, definition.defaultPriority)
            .set(TASK_TYPE_DEFINITION.UPDATED_AT, at)
            .onConflict(TASK_TYPE_DEFINITION.TASK_TYPE)
            .doUpdate()
            .set(TASK_TYPE_DEFINITION.OWNER_MODULE, DSL.excluded(TASK_TYPE_DEFINITION.OWNER_MODULE))
            .set(TASK_TYPE_DEFINITION.DISPLAY_NAME, DSL.excluded(TASK_TYPE_DEFINITION.DISPLAY_NAME))
            .set(TASK_TYPE_DEFINITION.CANDIDATE_ROLES, DSL.excluded(TASK_TYPE_DEFINITION.CANDIDATE_ROLES))
            .set(TASK_TYPE_DEFINITION.FOUR_EYES, DSL.excluded(TASK_TYPE_DEFINITION.FOUR_EYES))
            .set(TASK_TYPE_DEFINITION.ALLOWED_DECISIONS, DSL.excluded(TASK_TYPE_DEFINITION.ALLOWED_DECISIONS))
            .set(TASK_TYPE_DEFINITION.REASON_REQUIRED, DSL.excluded(TASK_TYPE_DEFINITION.REASON_REQUIRED))
            .set(TASK_TYPE_DEFINITION.DEFAULT_SLA_MINUTES, DSL.excluded(TASK_TYPE_DEFINITION.DEFAULT_SLA_MINUTES))
            .set(TASK_TYPE_DEFINITION.ESCALATION_ROLES, DSL.excluded(TASK_TYPE_DEFINITION.ESCALATION_ROLES))
            .set(TASK_TYPE_DEFINITION.DEFAULT_PRIORITY, DSL.excluded(TASK_TYPE_DEFINITION.DEFAULT_PRIORITY))
            .set(TASK_TYPE_DEFINITION.UPDATED_AT, DSL.excluded(TASK_TYPE_DEFINITION.UPDATED_AT))
            .execute()
    }

    // ----------------------------------------------------------------------------------------------------------

    /** Legal-entity scope: tasks without an entity are visible to everyone. */
    private fun visibleTo(actor: Actor): Condition = when {
        actor.allEntities || actor.has(Role.AUDITOR) || Role.SYSTEM in actor.roles -> DSL.noCondition()
        actor.legalEntityScope.isEmpty() -> APPROVAL_TASK.LEGAL_ENTITY_ID.isNull
        else -> APPROVAL_TASK.LEGAL_ENTITY_ID.isNull.or(APPROVAL_TASK.LEGAL_ENTITY_ID.`in`(actor.legalEntityScope))
    }

    /** Tasks the actor could decide: a candidate role and not their own request. */
    private fun actionableBy(actor: Actor): Condition {
        val notOwn = APPROVAL_TASK.REQUESTED_BY.ne(actor.userId)
        if (actor.has(Role.ADMIN)) return notOwn
        val roles = actor.roles.map { it.name }.toTypedArray()
        return notOwn.and(DSL.condition("{0} && {1}::text[]", APPROVAL_TASK.CANDIDATE_ROLES, DSL.`val`(roles)))
    }

    private fun toView(r: ApprovalTaskRecord) = ApprovalTaskView(
        id = r.id,
        type = r.taskType,
        subjectType = r.subjectType,
        subjectRef = r.subjectRef,
        legalEntityId = r.legalEntityId,
        summary = r.summary,
        requestedBy = r.requestedBy,
        requestedAt = r.requestedAt,
        status = TaskStatus.valueOf(r.status),
        decision = r.decision?.let(Decision::valueOf),
        decidedBy = r.decidedBy,
        decidedAt = r.decidedAt,
        reason = r.reason,
        assignee = r.assignee,
        claimedAt = r.claimedAt,
        priority = r.priority,
        dueAt = r.dueAt,
        escalationLevel = r.escalationLevel,
        candidateRoles = r.candidateRoles.mapNotNull { name -> name?.let { runCatching { Role.valueOf(it) }.getOrNull() } }.toSet(),
        payload = Json.mapper.readValue(r.payload.data(), mapType),
        version = r.version,
    )
}
