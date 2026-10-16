package com.pianonurmi.etrm.document.domain

import com.pianonurmi.etrm.document.api.DocumentView
import java.time.Instant
import java.util.UUID

interface DocumentRepository {
    /** Inserts the document; false when one with its id is already stored (a render that lost a race). */
    fun insert(document: DocumentView, bytes: ByteArray): Boolean

    fun find(id: UUID): DocumentView?

    fun bytes(id: UUID): ByteArray?

    fun forOwner(ownerType: String, ownerRef: UUID): List<DocumentView>

    fun insertJob(job: RenderJob)

    fun job(id: UUID): RenderJob?

    fun jobsFor(ownerType: String, ownerRef: UUID): List<UUID>

    /** Jobs requested before [before] that may still be tried: renders lost to a restart or failed for a while. */
    fun dueJobs(before: Instant, maxAttempts: Int, limit: Int): List<UUID>

    fun deleteJob(id: UUID)

    fun jobFailed(id: UUID, error: String, permanent: Boolean)
}

/**
 * A PDF requested in a business transaction and rendered after it commits ([DocumentView.id] is reserved up front).
 * The template is already filled in ([xhtml]); only the page layout is left.
 */
data class RenderJob(
    val id: UUID,
    val kind: String,
    val ownerType: String,
    val ownerRef: UUID,
    val legalEntityId: UUID?,
    val title: String,
    val fileName: String,
    val xhtml: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val attempts: Int = 0,
    val lastError: String? = null,
    val failed: Boolean = false,
)

data class RenderedPdf(val bytes: ByteArray, val pages: Int) {
    override fun equals(other: Any?): Boolean = other is RenderedPdf && other.pages == pages && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * pages + bytes.contentHashCode()
}

/** Turns XHTML into PDF bytes. */
fun interface PdfRenderer {
    fun render(xhtml: String, title: String): RenderedPdf
}
