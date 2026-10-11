package com.pianonurmi.etrm.document.adapter.rest

import com.pianonurmi.etrm.document.api.DocumentQuery
import com.pianonurmi.etrm.document.api.DocumentView
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Immutable business documents: confirmations, invoices, statements")
class DocumentController(private val query: DocumentQuery, private val actorProvider: ActorProvider) {
    /** Documents of legal entities outside the user's scope are left out (documents without an entity are shared). */
    @GetMapping
    fun forOwner(
        @RequestParam ownerType: String,
        @RequestParam ownerRef: UUID,
    ): List<DocumentView> {
        val actor = actorProvider.current()
        return query.forOwner(ownerType, ownerRef).filter { actor.canAccess(it.legalEntityId) }
    }

    @GetMapping("/{id}")
    fun document(
        @PathVariable id: UUID,
    ): DocumentView = requireDocument(id)

    @GetMapping("/{id}/content")
    fun content(
        @PathVariable id: UUID,
    ): ResponseEntity<ByteArray> {
        requireDocument(id)
        val content = query.content(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.document.mimeType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(content.document.fileName).build().toString())
            .header("X-Content-SHA256", content.document.sha256)
            .body(content.bytes)
    }

    @GetMapping("/{id}/verify")
    fun verify(
        @PathVariable id: UUID,
    ): Map<String, Any> = requireDocument(id).let { mapOf("documentId" to id, "intact" to query.verify(id)) }

    /** A document of a legal entity is visible only to users with access to that entity. */
    private fun requireDocument(id: UUID): DocumentView =
        (query.document(id) ?: throw NotFoundException("Document", id)).also { actorProvider.current().requireEntity(it.legalEntityId) }
}
