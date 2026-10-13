package com.pianonurmi.etrm.marketdata

import com.pianonurmi.etrm.marketdata.api.ImportFormatKind
import com.pianonurmi.etrm.marketdata.api.ImportFormatView
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.domain.ImportParsers
import com.pianonurmi.etrm.marketdata.domain.ParsedRow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Parsing of the seeded import formats. The ECB fixtures follow the layout of the ECB's published reference rate
 * files; the rates in them are illustrative.
 */
class ImportParsersTest {
    private fun format(
        kind: ImportFormatKind,
        mapping: Map<String, String> = emptyMap(),
        constants: Map<String, String> = emptyMap(),
        delimiter: Char = ',',
        decimal: Char = '.',
        dateFormat: String = "yyyy-MM-dd",
    ) =
        ImportFormatView("F", kind, "test", "TEST", delimiter, 0, dateFormat, decimal, mapping, constants, true, 1)

    @Test
    fun `ECB daily XML gives one EUR rate per currency`() {
        val xml = javaClass.getResourceAsStream("/marketdata/eurofxref-daily.xml")!!.readBytes()
        val parsed = ImportParsers.parse(format(ImportFormatKind.FX_ECB_XML, constants = mapOf("base" to "EUR")), xml)
        parsed.issues shouldHaveSize 0
        parsed.rows shouldHaveSize 8
        parsed.rows.filterIsInstance<ParsedRow.Fx>().first { it.quote == "SEK" }.let {
            it.base shouldBe "EUR"
            it.date shouldBe LocalDate.of(2026, 10, 9)
            it.rate shouldBe BigDecimal("11.0540")
        }
    }

    @Test
    fun `XML with a document type declaration is refused`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>""" +
            """<Cube time="2026-10-09"><Cube currency="USD" rate="&e;"/></Cube>"""
        shouldThrowAny { ImportParsers.parse(format(ImportFormatKind.FX_ECB_XML), evil.toByteArray()) }
    }

    @Test
    fun `ECB history CSV skips N A and reports bad values`() {
        val csv = "Date,USD,JPY,SEK,\n2026-10-09,1.1650,172.35,11.0540,\n2026-10-08,1.1600,N/A,abc,\nnot-a-date,1,2,3,\n"
        val parsed = ImportParsers.parse(format(ImportFormatKind.FX_WIDE_CSV, mapOf("date" to "Date"), mapOf("base" to "EUR")), csv.toByteArray())
        parsed.rows shouldHaveSize 4
        parsed.issues.map { it.lineNo } shouldBe listOf(3, 4)
        parsed.issues.first().message shouldContain "SEK"
    }

    @Test
    fun `long CSV with decimal commas, quoted fields and constants`() {
        val csv = "Datum;Kontrakt;Preis\n09.10.2026;\"EUA Dec-26\";\"72,45\"\n09.10.2026;EUA Dec-27;0\n"
        val parsed = ImportParsers.parse(
            format(
                ImportFormatKind.QUOTES_LONG_CSV,
                mapOf("date" to "Datum", "instrument" to "Kontrakt", "value" to "Preis"),
                mapOf("quoteType" to "SETTLEMENT", "currency" to "EUR"),
                delimiter = ';',
                decimal = ',',
                dateFormat = "dd.MM.yyyy",
            ),
            csv.toByteArray(),
        )
        // A zero price parses: whether it may be zero depends on the instrument (rates may), checked when it is stored.
        parsed.rows shouldHaveSize 2
        parsed.rows.first().let {
            it as ParsedRow.Price
            it.instrumentRef shouldBe "EUA Dec-26"
            it.value shouldBe BigDecimal("72.45")
            it.type shouldBe QuoteType.SETTLEMENT
            it.currency shouldBe "EUR"
        }
        parsed.issues shouldHaveSize 0
    }

    @Test
    fun `a missing mapped column is reported, not guessed`() {
        val parsed = ImportParsers.parse(
            format(ImportFormatKind.QUOTES_LONG_CSV, mapOf("date" to "Date", "instrument" to "Contract", "value" to "Settle")),
            "Date,Contract,Price\n".toByteArray(),
        )
        parsed.rows shouldHaveSize 0
        parsed.issues.single().message shouldContain "value"
    }
}
