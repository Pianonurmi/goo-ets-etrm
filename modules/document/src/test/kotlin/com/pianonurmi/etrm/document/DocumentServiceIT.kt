package com.pianonurmi.etrm.document

import com.pianonurmi.etrm.document.adapter.jooq.JooqDocumentRepository
import com.pianonurmi.etrm.document.adapter.pdf.OpenHtmlPdfRenderer
import com.pianonurmi.etrm.document.api.DocumentStored
import com.pianonurmi.etrm.document.api.RenderPdf
import com.pianonurmi.etrm.document.api.StoreDocument
import com.pianonurmi.etrm.document.application.DocumentLimits
import com.pianonurmi.etrm.document.application.DocumentService
import com.pianonurmi.etrm.document.domain.PdfRenderer
import com.pianonurmi.etrm.document.domain.RenderJob
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.awaitility.Awaitility.await
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentServiceIT {
    private val published: MutableList<Any> = Collections.synchronizedList(mutableListOf())
    private val renders = AtomicInteger()
    private val renderer = PdfRenderer { xhtml, title -> renders.incrementAndGet().let { OpenHtmlPdfRenderer().render(xhtml, title) } }
    private val transactions by lazy { TransactionTemplate(DataSourceTransactionManager(TestDatabase.dataSource)) }
    private val repository by lazy { JooqDocumentRepository(TestDatabase.dsl) }
    private lateinit var documents: DocumentService

    private fun service(limits: DocumentLimits = DocumentLimits()) = DocumentService(
        repository,
        renderer,
        ActorProvider { Actor("backoffice", "Back office", setOf(Role.BACK_OFFICE)) },
        ApplicationEventPublisher { published += it },
        Clock.systemUTC(),
        transactions,
        limits,
    )

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("document")
        documents = service()
    }

    private fun letter(owner: UUID, title: String = "Statement & summary", recipient: String = "<Ms Smith>") = RenderPdf(
        kind = "TEST_LETTER",
        ownerType = "TEST",
        ownerRef = owner,
        legalEntityId = null,
        title = title,
        fileName = "letter.pdf",
        template = "test-letter",
        model = mapOf("title" to title, "recipient" to recipient, "lines" to listOf(mapOf("item" to "GoO", "amount" to "1 950,00"))),
    )

    @Test
    fun `templates render to an immutable, hashed PDF that can be read back and verified`() {
        val owner = UUID.randomUUID()
        val pdf = documents.renderPdf(letter(owner))
        val stored = documents.document(pdf.id)!!
        stored.mimeType shouldBe "application/pdf"
        stored.createdBy shouldBe "backoffice"
        val content = documents.content(pdf.id)
        String(content.bytes.copyOfRange(0, 5)) shouldStartWith "%PDF-"
        documents.verify(pdf.id) shouldBe true
        published.filterIsInstance<DocumentStored>().single { it.document.id == pdf.id }

        val csv = documents.store(StoreDocument("EXPORT", "TEST", owner, null, "Export", "export.csv", "text/csv", "a,b\r\n1,2\r\n".toByteArray()))
        documents.forOwner("TEST", owner).map { it.id } shouldBe listOf(pdf.id, csv.id)
        shouldThrow<DataAccessException> { TestDatabase.dsl.execute("UPDATE etrm_document.document SET title = 'x' WHERE id = '${pdf.id}'") }
    }

    /** The page layout runs after the requesting transaction commits, never inside it. */
    @Test
    fun `a PDF requested in a transaction is rendered after it commits`() {
        val owner = UUID.randomUUID()
        val before = renders.get()
        val pdf = transactions.execute {
            documents.renderPdf(letter(owner)).also {
                renders.get() shouldBe before
                repository.find(it.id) shouldBe null
            }
        }
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until { repository.find(pdf.id) != null }
        repository.job(pdf.id) shouldBe null
    }

    @Test
    fun `a rolled back request leaves neither job nor document`() {
        val owner = UUID.randomUUID()
        val id = transactions.execute { status ->
            documents.renderPdf(letter(owner)).id.also { status.setRollbackOnly() }
        }
        repository.job(id) shouldBe null
        documents.document(id) shouldBe null
    }

    /** A render lost to a restart is done when the document is read, or by the render job. */
    @Test
    fun `pending renders are done on read and by the render job`() {
        fun pendingJob(requestedAt: Instant) = RenderJob(
            id = Ids.newId(),
            kind = "TEST_LETTER",
            ownerType = "TEST",
            ownerRef = UUID.randomUUID(),
            legalEntityId = null,
            title = "Pending",
            fileName = "pending.pdf",
            xhtml = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title></head><body><p>Pending</p></body></html>",
            requestedBy = "trader",
            requestedAt = requestedAt,
        ).also { repository.insertJob(it) }

        val read = pendingJob(Instant.now())
        documents.content(read.id).document.createdBy shouldBe "trader"
        repository.job(read.id) shouldBe null

        val lost = pendingJob(Instant.now().minus(Duration.ofMinutes(5)))
        documents.renderDue() shouldBe 1
        repository.find(lost.id) shouldNotBe null
    }

    @Test
    fun `a document beyond the limits is refused, not stored`() {
        val strict = service(DocumentLimits(maxPages = 0))
        val pdf = strict.renderPdf(letter(UUID.randomUUID()))
        repository.find(pdf.id) shouldBe null
        repository.job(pdf.id)!!.failed shouldBe true
        shouldThrow<ConflictException> { strict.content(pdf.id) }.message!! shouldContain "more than 0"
    }

    /** The bundled font is embedded: Nordic and Baltic names print as written, not as missing glyphs. */
    @Test
    fun `names outside Latin-1 print with the bundled font`() {
        val name = "Šiaulių Žemaitė, Ģirts Ķēniņš, Łódź"
        val pdf = documents.renderPdf(letter(UUID.randomUUID(), recipient = name))
        val bytes = documents.content(pdf.id).bytes
        Loader.loadPDF(bytes).use { document ->
            PDFTextStripper().getText(document) shouldContain name
            document.getPage(0).resources.fontNames.map { document.getPage(0).resources.getFont(it).name }
                .any { it.contains("LiberationSans") } shouldBe true
        }
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

    private companion object {
        const val TIMEOUT_SECONDS = 20L
    }
}
