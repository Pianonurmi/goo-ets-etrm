package com.pianonurmi.etrm.document.application

import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.document.api.DocumentContent
import com.pianonurmi.etrm.document.api.DocumentQuery
import com.pianonurmi.etrm.document.api.DocumentStored
import com.pianonurmi.etrm.document.api.DocumentView
import com.pianonurmi.etrm.document.api.PendingDocument
import com.pianonurmi.etrm.document.api.RenderPdf
import com.pianonurmi.etrm.document.api.StoreDocument
import com.pianonurmi.etrm.document.domain.DocumentRepository
import com.pianonurmi.etrm.document.domain.PdfRenderer
import com.pianonurmi.etrm.document.domain.RenderJob
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.samskivert.mustache.Mustache
import com.samskivert.mustache.Template
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Limits a rendered PDF must keep; a document beyond them is refused rather than stored (`etrm.documents.*`). */
@ConfigurationProperties("etrm.documents")
data class DocumentLimits(val maxPages: Int = 200, val maxBytes: Long = DocumentService.MAX_BYTES.toLong())

/**
 * Stores documents and renders PDFs. A PDF requested inside a business transaction is filled in there (the template,
 * which is quick) and laid out after the transaction commits, on a virtual thread, so no pool connection waits on page
 * layout. The document row, immutable, is inserted with the reserved id in a short transaction of its own. Reading a
 * document that is still pending renders it first; [renderDue] picks up renders lost to a restart.
 */
@Service
class DocumentService(
    private val repository: DocumentRepository,
    private val renderer: PdfRenderer,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val transactions: TransactionOperations = TransactionOperations.withoutTransaction(),
    private val limits: DocumentLimits = DocumentLimits(),
) : DocumentCommand, DocumentQuery {
    private val log = LoggerFactory.getLogger(DocumentService::class.java)
    private val compiler = Mustache.compiler().defaultValue("").escapeHTML(true)
    private val templates = ConcurrentHashMap<String, Template>()

    @Transactional
    override fun renderPdf(command: RenderPdf): PendingDocument {
        val job = RenderJob(
            id = Ids.newId(),
            kind = command.kind,
            ownerType = command.ownerType,
            ownerRef = command.ownerRef,
            legalEntityId = command.legalEntityId,
            title = command.title,
            fileName = command.fileName,
            xhtml = template(command.template).execute(command.model),
            requestedBy = actorProvider.current().userId,
            requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        repository.insertJob(job)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        Thread.ofVirtual().name("document-render-${job.id}").start { render(job.id) }
                    }
                },
            )
        } else {
            render(job.id)
        }
        return PendingDocument(job.id)
    }

    @Transactional
    override fun store(command: StoreDocument): DocumentView {
        if (command.bytes.isEmpty()) throw ValidationException("Document ${command.fileName} is empty")
        if (command.bytes.size > MAX_BYTES) throw ValidationException("Document ${command.fileName} exceeds ${MAX_BYTES / 1_048_576} MB")
        val document = DocumentView(
            id = Ids.newId(),
            kind = command.kind,
            ownerType = command.ownerType,
            ownerRef = command.ownerRef,
            legalEntityId = command.legalEntityId,
            title = command.title,
            fileName = command.fileName,
            mimeType = command.mimeType,
            sizeBytes = command.bytes.size.toLong(),
            sha256 = sha256(command.bytes),
            createdBy = actorProvider.current().userId,
            createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        repository.insert(document, command.bytes)
        events.publishEvent(DocumentStored(document))
        return document
    }

    override fun document(id: UUID): DocumentView? {
        ensureRendered(id)
        return repository.find(id)
    }

    override fun content(id: UUID): DocumentContent {
        ensureRendered(id)
        val document = repository.find(id) ?: throw NotFoundException("Document", id)
        return DocumentContent(document, repository.bytes(id)!!)
    }

    override fun forOwner(ownerType: String, ownerRef: UUID): List<DocumentView> {
        repository.jobsFor(ownerType, ownerRef).forEach { render(it) }
        return repository.forOwner(ownerType, ownerRef)
    }

    override fun verify(id: UUID): Boolean {
        val content = content(id)
        return sha256(content.bytes) == content.document.sha256
    }

    /** Renders the jobs left over from a restart or an earlier failure (the render job calls this). */
    fun renderDue(): Int {
        val due = repository.dueJobs(clock.instant().minus(GRACE), MAX_ATTEMPTS, BATCH)
        due.forEach { render(it) }
        return due.size
    }

    private fun ensureRendered(id: UUID) {
        if (repository.find(id) != null) return
        val job = repository.job(id) ?: return
        if (job.failed) throw ConflictException("Document $id could not be produced: ${job.lastError}")
        render(id)
        if (repository.find(id) == null) throw ConflictException("Document $id is being prepared; try again shortly")
    }

    /**
     * Lays out the job's PDF outside any transaction, then stores it and drops the job in one short transaction. Two
     * renders of one job (the after-commit thread and a reader) are harmless: the first insert wins.
     */
    private fun render(id: UUID) {
        val job = repository.job(id)?.takeUnless { it.failed } ?: return
        val pdf = try {
            renderer.render(job.xhtml, job.title)
        } catch (e: Exception) {
            log.warn("Rendering document {} ({}) failed; it is tried again", id, job.fileName, e)
            transactions.executeWithoutResult { repository.jobFailed(id, e.message ?: e.javaClass.simpleName, permanent = false) }
            return
        }
        val problem = when {
            pdf.pages > limits.maxPages -> "${job.fileName} has ${pdf.pages} pages, more than ${limits.maxPages}"
            pdf.bytes.size > limits.maxBytes -> "${job.fileName} is ${pdf.bytes.size} bytes, more than ${limits.maxBytes}"
            else -> null
        }
        if (problem != null) {
            log.error("Document {} refused: {}", id, problem)
            transactions.executeWithoutResult { repository.jobFailed(id, problem, permanent = true) }
            return
        }
        val document = DocumentView(
            id = job.id,
            kind = job.kind,
            ownerType = job.ownerType,
            ownerRef = job.ownerRef,
            legalEntityId = job.legalEntityId,
            title = job.title,
            fileName = job.fileName,
            mimeType = PDF,
            sizeBytes = pdf.bytes.size.toLong(),
            sha256 = sha256(pdf.bytes),
            createdBy = job.requestedBy,
            createdAt = job.requestedAt,
        )
        transactions.executeWithoutResult {
            if (repository.insert(document, pdf.bytes)) events.publishEvent(DocumentStored(document))
            repository.deleteJob(id)
        }
    }

    private fun template(name: String): Template {
        if (!TEMPLATE_NAME.matches(name)) throw ValidationException("Invalid template name $name")
        return templates.getOrPut(name) {
            val resource = javaClass.classLoader.getResource("documents/templates/$name.html")
                ?: throw ValidationException("Document template $name not found")
            compiler.compile(resource.readText())
        }
    }

    companion object {
        const val PDF = "application/pdf"
        const val MAX_BYTES = 20 * 1_048_576
        private const val MAX_ATTEMPTS = 5
        private const val BATCH = 50
        private val GRACE: Duration = Duration.ofSeconds(30)
        private val TEMPLATE_NAME = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
