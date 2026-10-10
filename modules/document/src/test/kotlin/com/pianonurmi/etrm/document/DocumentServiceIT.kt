package com.pianonurmi.etrm.document

import com.pianonurmi.etrm.document.adapter.jooq.JooqDocumentRepository
import com.pianonurmi.etrm.document.adapter.pdf.OpenHtmlPdfRenderer
import com.pianonurmi.etrm.document.api.DocumentStored
import com.pianonurmi.etrm.document.api.RenderPdf
import com.pianonurmi.etrm.document.api.StoreDocument
import com.pianonurmi.etrm.document.application.DocumentService
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentServiceIT {
    private val published = mutableListOf<Any>()
    private lateinit var documents: DocumentService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("document")
        documents = DocumentService(
            JooqDocumentRepository(TestDatabase.dsl),
            OpenHtmlPdfRenderer(),
            ActorProvider { Actor("backoffice", "Back office", setOf(Role.BACK_OFFICE)) },
            ApplicationEventPublisher { published += it },
            Clock.systemUTC(),
        )
    }

    @Test
    fun `templates render to an immutable, hashed PDF that can be read back and verified`() {
        val owner = UUID.randomUUID()
        val pdf = documents.renderPdf(
            RenderPdf(
                kind = "TEST_LETTER",
                ownerType = "TEST",
                ownerRef = owner,
                legalEntityId = null,
                title = "Statement & summary",
                fileName = "letter.pdf",
                template = "test-letter",
                model = mapOf("title" to "Statement & summary", "recipient" to "<Ms Smith>", "lines" to listOf(mapOf("item" to "GoO", "amount" to "1 950,00"))),
            ),
        )
        pdf.mimeType shouldBe "application/pdf"
        pdf.createdBy shouldBe "backoffice"
        val content = documents.content(pdf.id)
        String(content.bytes.copyOfRange(0, 5)) shouldStartWith "%PDF-"
        documents.verify(pdf.id) shouldBe true
        published.filterIsInstance<DocumentStored>().single { it.document.id == pdf.id }

        val csv = documents.store(StoreDocument("EXPORT", "TEST", owner, null, "Export", "export.csv", "text/csv", "a,b\r\n1,2\r\n".toByteArray()))
        documents.forOwner("TEST", owner).map { it.id } shouldBe listOf(pdf.id, csv.id)
        shouldThrow<DataAccessException> { TestDatabase.dsl.execute("UPDATE etrm_document.document SET title = 'x' WHERE id = '${pdf.id}'") }
    }

    @Test
    fun `unknown or unsafe templates and empty documents are rejected`() {
        val owner = UUID.randomUUID()

        fun render(template: String) = documents.renderPdf(RenderPdf("X", "TEST", owner, null, "t", "t.pdf", template, emptyMap()))
        shouldThrow<ValidationException> { render("../secrets") }
        shouldThrow<ValidationException> { render("does-not-exist") }
        shouldThrow<ValidationException> { documents.store(StoreDocument("X", "TEST", owner, null, "t", "t.txt", "text/plain", ByteArray(0))) }
        documents.forOwner("TEST", owner) shouldHaveSize 0
    }
}
