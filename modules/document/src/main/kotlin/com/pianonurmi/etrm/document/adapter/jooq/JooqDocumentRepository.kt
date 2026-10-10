package com.pianonurmi.etrm.document.adapter.jooq

import com.pianonurmi.etrm.document.adapter.jooq.generated.tables.Document.DOCUMENT
import com.pianonurmi.etrm.document.api.DocumentView
import com.pianonurmi.etrm.document.domain.DocumentRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JooqDocumentRepository(private val dsl: DSLContext) : DocumentRepository {
    private val metadata = DOCUMENT.fields().filter { it != DOCUMENT.CONTENT }

    override fun insert(document: DocumentView, bytes: ByteArray) {
        dsl.insertInto(DOCUMENT)
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
            .execute()
    }

    override fun find(id: UUID): DocumentView? = dsl.select(metadata).from(DOCUMENT).where(DOCUMENT.ID.eq(id)).fetchOne(::toView)

    override fun bytes(id: UUID): ByteArray? = dsl.select(DOCUMENT.CONTENT).from(DOCUMENT).where(DOCUMENT.ID.eq(id)).fetchOne(DOCUMENT.CONTENT)

    override fun forOwner(ownerType: String, ownerRef: UUID): List<DocumentView> = dsl.select(metadata).from(DOCUMENT)
        .where(DOCUMENT.OWNER_TYPE.eq(ownerType), DOCUMENT.OWNER_REF.eq(ownerRef))
        .orderBy(DOCUMENT.CREATED_AT, DOCUMENT.ID)
        .fetch(::toView)

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
}
