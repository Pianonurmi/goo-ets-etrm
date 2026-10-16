package com.pianonurmi.etrm.document.adapter.pdf

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.pianonurmi.etrm.document.domain.PdfRenderer
import com.pianonurmi.etrm.document.domain.RenderedPdf
import org.apache.pdfbox.Loader
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * XHTML + CSS (paged media) to PDF with openhtmltopdf; templates must be well-formed XHTML. The bundled Liberation Sans
 * (SIL OFL, metric-compatible with Helvetica) is embedded, so names outside Latin-1 (Nordic and Baltic letters, for
 * example) print as written. Templates ask for it as `font-family: "Liberation Sans"`.
 */
@Component
class OpenHtmlPdfRenderer : PdfRenderer {
    override fun render(xhtml: String, title: String): RenderedPdf {
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .withHtmlContent(xhtml, null)
            .withProducer("goo-ets-etrm")
            .apply { FONTS.forEach { (file, weight) -> useFont({ font(file) }, FAMILY, weight, FontStyle.NORMAL, true) } }
            .toStream(out)
            .run()
        val bytes = out.toByteArray()
        return RenderedPdf(bytes, Loader.loadPDF(bytes).use { it.numberOfPages })
    }

    private fun font(file: String) = javaClass.classLoader.getResourceAsStream("documents/fonts/$file")
        ?: error("Bundled font $file is missing")

    private companion object {
        const val FAMILY = "Liberation Sans"
        val FONTS = listOf("LiberationSans-Regular.ttf" to 400, "LiberationSans-Bold.ttf" to 700)
    }
}
