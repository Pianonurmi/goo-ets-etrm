package com.pianonurmi.etrm.document.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.time.Instant
import java.util.UUID

/** Metadata of a stored document. The content is immutable; its SHA-256 proves it was not altered. */
data class DocumentView(
    val id: UUID,
    val kind: String,
    val ownerType: String,
    val ownerRef: UUID,
    val legalEntityId: UUID?,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdBy: String,
    val createdAt: Instant,
)

data class DocumentContent(val document: DocumentView, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is DocumentContent && other.document == document && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * document.hashCode() + bytes.contentHashCode()
}

/**
 * Renders the classpath template `documents/templates/<template>.html` (Mustache, XHTML) with [model] into a PDF.
 * Owner modules ship their templates in their own resources under that path.
 */
data class RenderPdf(
    val kind: String,
    val ownerType: String,
    val ownerRef: UUID,
    val legalEntityId: UUID?,
    val title: String,
    val fileName: String,
    val template: String,
    val model: Map<String, Any?>,
)

/** Stores an already produced document (e.g. CSV export or an uploaded counterparty confirmation). */
data class StoreDocument(
    val kind: String,
    val ownerType: String,
    val ownerRef: UUID,
    val legalEntityId: UUID?,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is StoreDocument && other.kind == kind && other.ownerRef == ownerRef && other.fileName == fileName && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * ownerRef.hashCode() + bytes.contentHashCode()
}

/**
 * A PDF that will be stored under [id]. It is rendered after the requesting transaction commits, so the caller keeps
 * the id (on an invoice, a confirmation) without waiting for the page layout; reading the document renders it first if
 * it is still pending.
 */
data class PendingDocument(val id: UUID)

interface DocumentCommand {
    /** Fills in the template now and renders the PDF once the current transaction commits. */
    fun renderPdf(command: RenderPdf): PendingDocument

    fun store(command: StoreDocument): DocumentView
}

interface DocumentQuery {
    fun document(id: UUID): DocumentView?

    fun content(id: UUID): DocumentContent

    fun forOwner(ownerType: String, ownerRef: UUID): List<DocumentView>

    /** Recomputes the hash of the stored bytes; false means the content no longer matches what was stored. */
    fun verify(id: UUID): Boolean
}

data class DocumentStored(val document: DocumentView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
