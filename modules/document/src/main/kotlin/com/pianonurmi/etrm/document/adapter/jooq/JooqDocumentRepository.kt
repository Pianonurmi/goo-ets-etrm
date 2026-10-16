package com.pianonurmi.etrm.document.adapter.jooq

import com.pianonurmi.etrm.document.adapter.jooq.generated.tables.Document.DOCUMENT
import com.pianonurmi.etrm.document.adapter.jooq.generated.tables.DocumentRenderJob.DOCUMENT_RENDER_JOB
import com.pianonurmi.etrm.document.api.DocumentView
import com.pianonurmi.etrm.document.domain.DocumentRepository
import com.pianonurmi.etrm.document.domain.RenderJob
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JooqDocumentRepository(private val dsl: DSLContext) : DocumentRepository {
    private val metadata = DOCUMENT.fields().filter { it != DOCUMENT.CONTENT }

    override fun insert(document: DocumentView, bytes: ByteArray): Boolean = dsl.insertInto(DOCUMENT)
        .set(DOCUMENT.ID, document.id)
        .set(DOCUMENT.KIND, document.kind)
        .set(DOCUMENT.OWNER_TYPE, document.ownerType)
        .set(DOCUMENT.OWNER_REF, document.ownerRef)
        .set(DOCUMENT.LEGAL_ENTITY_ID, document.legalEntityId)
        .set(DOCUMENT.TITLE, document.title)
        .set(DOCUMENT.FILE_NAME, document.fileName)
        .set(DOCUMENT.MIME_TYPE, document.mimeType)
        .set(DOCUMENT.SIZE_BYTES, document.sizeBytes)
        .set(DOCUMENT.SHA256, document.sha256)
        .set(DOCUMENT.CONTENT, bytes)
        .set(DOCUMENT.CREATED_BY, document.createdBy)
        .set(DOCUMENT.CREATED_AT, document.createdAt)
        .onConflict(DOCUMENT.ID)
        .doNothing()
        .execute() == 1

    override fun find(id: UUID): DocumentView? = dsl.select(metadata).from(DOCUMENT).where(DOCUMENT.ID.eq(id)).fetchOne(::toView)

    override fun bytes(id: UUID): ByteArray? = dsl.select(DOCUMENT.CONTENT).from(DOCUMENT).where(DOCUMENT.ID.eq(id)).fetchOne(DOCUMENT.CONTENT)

    override fun forOwner(ownerType: String, ownerRef: UUID): List<DocumentView> = dsl.select(metadata).from(DOCUMENT)
        .where(DOCUMENT.OWNER_TYPE.eq(ownerType), DOCUMENT.OWNER_REF.eq(ownerRef))
        .orderBy(DOCUMENT.CREATED_AT, DOCUMENT.ID)
        .fetch(::toView)

    override fun insertJob(job: RenderJob) {
        dsl.insertInto(DOCUMENT_RENDER_JOB)
            .set(DOCUMENT_RENDER_JOB.ID, job.id)
            .set(DOCUMENT_RENDER_JOB.KIND, job.kind)
            .set(DOCUMENT_RENDER_JOB.OWNER_TYPE, job.ownerType)
            .set(DOCUMENT_RENDER_JOB.OWNER_REF, job.ownerRef)
            .set(DOCUMENT_RENDER_JOB.LEGAL_ENTITY_ID, job.legalEntityId)
            .set(DOCUMENT_RENDER_JOB.TITLE, job.title)
            .set(DOCUMENT_RENDER_JOB.FILE_NAME, job.fileName)
            .set(DOCUMENT_RENDER_JOB.XHTML, job.xhtml)
            .set(DOCUMENT_RENDER_JOB.REQUESTED_BY, job.requestedBy)
            .set(DOCUMENT_RENDER_JOB.REQUESTED_AT, job.requestedAt)
            .execute()
    }

    override fun job(id: UUID): RenderJob? = dsl.selectFrom(DOCUMENT_RENDER_JOB).where(DOCUMENT_RENDER_JOB.ID.eq(id)).fetchOne()?.let {
        RenderJob(
            id = it.id,
            kind = it.kind,
            ownerType = it.ownerType,
            ownerRef = it.ownerRef,
            legalEntityId = it.legalEntityId,
            title = it.title,
            fileName = it.fileName,
            xhtml = it.xhtml,
            requestedBy = it.requestedBy,
            requestedAt = it.requestedAt,
            attempts = it.attempts,
            lastError = it.lastError,
            failed = it.failed,
        )
    }

    override fun jobsFor(ownerType: String, ownerRef: UUID): List<UUID> = dsl.select(DOCUMENT_RENDER_JOB.ID).from(DOCUMENT_RENDER_JOB)
        .where(DOCUMENT_RENDER_JOB.OWNER_TYPE.eq(ownerType), DOCUMENT_RENDER_JOB.OWNER_REF.eq(ownerRef))
        .fetch(DOCUMENT_RENDER_JOB.ID)

    override fun dueJobs(before: Instant, maxAttempts: Int, limit: Int): List<UUID> = dsl.select(DOCUMENT_RENDER_JOB.ID).from(DOCUMENT_RENDER_JOB)
        .where(DOCUMENT_RENDER_JOB.FAILED.isFalse, DOCUMENT_RENDER_JOB.REQUESTED_AT.lt(before), DOCUMENT_RENDER_JOB.ATTEMPTS.lt(maxAttempts))
        .orderBy(DOCUMENT_RENDER_JOB.REQUESTED_AT)
        .limit(limit)
        .fetch(DOCUMENT_RENDER_JOB.ID)

    override fun deleteJob(id: UUID) {
        dsl.deleteFrom(DOCUMENT_RENDER_JOB).where(DOCUMENT_RENDER_JOB.ID.eq(id)).execute()
    }

    override fun jobFailed(id: UUID, error: String, permanent: Boolean) {
        dsl.update(DOCUMENT_RENDER_JOB)
            .set(DOCUMENT_RENDER_JOB.ATTEMPTS, DOCUMENT_RENDER_JOB.ATTEMPTS.add(1))
            .set(DOCUMENT_RENDER_JOB.LAST_ERROR, error.take(MAX_ERROR))
            .set(DOCUMENT_RENDER_JOB.FAILED, if (permanent) DSL.value(true) else DOCUMENT_RENDER_JOB.FAILED)
            .where(DOCUMENT_RENDER_JOB.ID.eq(id))
            .execute()
    }

    private fun toView(r: Record) = DocumentView(
        id = r[DOCUMENT.ID]!!,
        kind = r[DOCUMENT.KIND]!!,
        ownerType = r[DOCUMENT.OWNER_TYPE]!!,
        ownerRef = r[DOCUMENT.OWNER_REF]!!,
        legalEntityId = r[DOCUMENT.LEGAL_ENTITY_ID],
        title = r[DOCUMENT.TITLE]!!,
        fileName = r[DOCUMENT.FILE_NAME]!!,
        mimeType = r[DOCUMENT.MIME_TYPE]!!,
        sizeBytes = r[DOCUMENT.SIZE_BYTES]!!,
        sha256 = r[DOCUMENT.SHA256]!!,
        createdBy = r[DOCUMENT.CREATED_BY]!!,
        createdAt = r[DOCUMENT.CREATED_AT]!!,
    )

    private companion object {
        const val MAX_ERROR = 2000
    }
}
