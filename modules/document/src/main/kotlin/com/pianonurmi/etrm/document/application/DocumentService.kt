package com.pianonurmi.etrm.document.application

import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.document.api.DocumentContent
import com.pianonurmi.etrm.document.api.DocumentQuery
import com.pianonurmi.etrm.document.api.DocumentStored
import com.pianonurmi.etrm.document.api.DocumentView
import com.pianonurmi.etrm.document.api.RenderPdf
import com.pianonurmi.etrm.document.api.StoreDocument
import com.pianonurmi.etrm.document.domain.DocumentRepository
import com.pianonurmi.etrm.document.domain.PdfRenderer
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.samskivert.mustache.Mustache
import com.samskivert.mustache.Template
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
@Transactional
class DocumentService(
    private val repository: DocumentRepository,
    private val renderer: PdfRenderer,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : DocumentCommand, DocumentQuery {
    private val compiler = Mustache.compiler().defaultValue("").escapeHTML(true)
    private val templates = ConcurrentHashMap<String, Template>()

    override fun renderPdf(command: RenderPdf): DocumentView {
        val xhtml = template(command.template).execute(command.model)
        val bytes = renderer.render(xhtml, command.title)
        return save(
            StoreDocument(command.kind, command.ownerType, command.ownerRef, command.legalEntityId, command.title, command.fileName, PDF, bytes),
        )
    }

    override fun store(command: StoreDocument): DocumentView {
        if (command.bytes.isEmpty()) throw ValidationException("Document ${command.fileName} is empty")
        if (command.bytes.size > MAX_BYTES) throw ValidationException("Document ${command.fileName} exceeds ${MAX_BYTES / 1_048_576} MB")
        return save(command)
    }

    @Transactional(readOnly = true)
    override fun document(id: UUID): DocumentView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun content(id: UUID): DocumentContent {
        val document = repository.find(id) ?: throw NotFoundException("Document", id)
        return DocumentContent(document, repository.bytes(id)!!)
    }

    @Transactional(readOnly = true)
    override fun forOwner(ownerType: String, ownerRef: UUID): List<DocumentView> = repository.forOwner(ownerType, ownerRef)

    @Transactional(readOnly = true)
    override fun verify(id: UUID): Boolean {
        val content = content(id)
        return sha256(content.bytes) == content.document.sha256
    }

    private fun save(command: StoreDocument): DocumentView {
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
        private val TEMPLATE_NAME = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
