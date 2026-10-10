package com.pianonurmi.etrm.workflow.adapter.jooq

import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.workflow.adapter.jooq.generated.tables.ApprovalTask.APPROVAL_TASK
import com.pianonurmi.etrm.workflow.adapter.jooq.generated.tables.records.ApprovalTaskRecord
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskType
import com.pianonurmi.etrm.workflow.application.ApprovalTaskRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.util.UUID

@Repository
class JooqApprovalTaskRepository(private val dsl: DSLContext) : ApprovalTaskRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun find(id: UUID): ApprovalTaskView? = dsl.selectFrom(APPROVAL_TASK).where(APPROVAL_TASK.ID.eq(id)).fetchOne()?.let(::toView)

    override fun lock(id: UUID): ApprovalTaskView? = dsl.selectFrom(APPROVAL_TASK).where(APPROVAL_TASK.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun openFor(subjectType: String, subjectRef: UUID): ApprovalTaskView? = dsl.selectFrom(APPROVAL_TASK)
        .where(APPROVAL_TASK.SUBJECT_TYPE.eq(subjectType), APPROVAL_TASK.SUBJECT_REF.eq(subjectRef), APPROVAL_TASK.STATUS.eq(TaskStatus.OPEN.name))
        .fetchOne()?.let(::toView)

    override fun inbox(legalEntityId: UUID?, status: TaskStatus, limit: Int): List<ApprovalTaskView> = dsl.selectFrom(APPROVAL_TASK)
        .where(APPROVAL_TASK.STATUS.eq(status.name))
        .and(legalEntityId?.let { APPROVAL_TASK.LEGAL_ENTITY_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(APPROVAL_TASK.REQUESTED_AT)
        .limit(limit)
        .fetch(::toView)

    override fun insert(task: ApprovalTaskView) {
        dsl.insertInto(APPROVAL_TASK)
            .set(APPROVAL_TASK.ID, task.id)
            .set(APPROVAL_TASK.TASK_TYPE, task.type.name)
            .set(APPROVAL_TASK.SUBJECT_TYPE, task.subjectType)
            .set(APPROVAL_TASK.SUBJECT_REF, task.subjectRef)
            .set(APPROVAL_TASK.LEGAL_ENTITY_ID, task.legalEntityId)
            .set(APPROVAL_TASK.SUMMARY, task.summary)
            .set(APPROVAL_TASK.REQUESTED_BY, task.requestedBy)
            .set(APPROVAL_TASK.REQUESTED_AT, task.requestedAt)
            .set(APPROVAL_TASK.STATUS, task.status.name)
            .set(APPROVAL_TASK.PAYLOAD, JSONB.jsonb(Json.mapper.writeValueAsString(task.payload)))
            .set(APPROVAL_TASK.VERSION, task.version)
            .execute()
    }

    override fun update(task: ApprovalTaskView): Boolean = dsl.update(APPROVAL_TASK)
        .set(APPROVAL_TASK.STATUS, task.status.name)
        .set(APPROVAL_TASK.DECIDED_BY, task.decidedBy)
        .set(APPROVAL_TASK.DECIDED_AT, task.decidedAt)
        .set(APPROVAL_TASK.REASON, task.reason)
        .set(APPROVAL_TASK.VERSION, task.version)
        .where(APPROVAL_TASK.ID.eq(task.id), APPROVAL_TASK.VERSION.eq(task.version - 1))
        .execute() == 1

    private fun toView(r: ApprovalTaskRecord) = ApprovalTaskView(
        r.id,
        TaskType.valueOf(r.taskType),
        r.subjectType,
        r.subjectRef,
        r.legalEntityId,
        r.summary,
        r.requestedBy,
        r.requestedAt,
        TaskStatus.valueOf(r.status),
        r.decidedBy,
        r.decidedAt,
        r.reason,
        Json.mapper.readValue(r.payload.data(), mapType),
        r.version,
    )
}
