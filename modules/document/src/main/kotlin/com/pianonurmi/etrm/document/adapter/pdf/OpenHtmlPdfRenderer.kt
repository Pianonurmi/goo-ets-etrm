package com.pianonurmi.etrm.document.adapter.pdf

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.pianonurmi.etrm.document.domain.PdfRenderer
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/** XHTML + CSS (paged media) to PDF with openhtmltopdf; templates must be well-formed XHTML. */
@Component
class OpenHtmlPdfRenderer : PdfRenderer {
    override fun render(xhtml: String, title: String): ByteArray {
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .withHtmlContent(xhtml, null)
            .withProducer("goo-ets-etrm")
            .toStream(out)
            .run()
        return out.toByteArray()
    }
}
