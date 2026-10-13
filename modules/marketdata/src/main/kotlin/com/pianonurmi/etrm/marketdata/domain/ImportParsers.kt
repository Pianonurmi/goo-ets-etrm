package com.pianonurmi.etrm.marketdata.domain

import com.pianonurmi.etrm.marketdata.api.ImportFormatKind
import com.pianonurmi.etrm.marketdata.api.ImportFormatView
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.platform.text.Csv
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** One accepted line of a market data file. */
sealed interface ParsedRow {
    val lineNo: Int

    data class Fx(override val lineNo: Int, val date: LocalDate, val base: String, val quote: String, val rate: BigDecimal) : ParsedRow

    /** [instrumentRef] is the instrument code or a vendor code resolved through the format's source aliases. */
    data class Price(
        override val lineNo: Int,
        val date: LocalDate,
        val instrumentRef: String,
        val type: QuoteType,
        val value: BigDecimal,
        val currency: String?,
    ) : ParsedRow
}

data class ParseIssue(val lineNo: Int, val message: String)

data class ParsedFile(val rows: List<ParsedRow>, val issues: List<ParseIssue>, val linesRead: Int)

/**
 * Reads market data files as described by an [ImportFormatView]. Bad lines are reported, never fatal, so that one
 * unreadable value does not hold back a whole price file.
 */
object ImportParsers {
    fun parse(format: ImportFormatView, content: ByteArray): ParsedFile = when (format.kind) {
        ImportFormatKind.FX_ECB_XML -> ecbXml(format, content)
        ImportFormatKind.FX_WIDE_CSV -> wideCsv(format, text(content))
        ImportFormatKind.QUOTES_LONG_CSV -> longCsv(format, text(content))
    }

    private fun text(content: ByteArray): String = content.decodeToString().removePrefix("")

    /**
     * The ECB's reference rate XML: `Cube time="…"` elements holding `Cube currency="USD" rate="1.0934"`, each rate
     * being units of currency per euro. The same layout serves the daily, 90-day and full history files.
     */
    private fun ecbXml(format: ImportFormatView, content: ByteArray): ParsedFile {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(content))
        val base = format.constants["base"] ?: "EUR"
        val rows = mutableListOf<ParsedRow>()
        val issues = mutableListOf<ParseIssue>()
        val cubes = document.getElementsByTagNameNS("*", "Cube")
        var n = 0
        for (i in 0 until cubes.length) {
            val day = cubes.item(i) as Element
            if (!day.hasAttribute("time")) continue
            val date = runCatching { LocalDate.parse(day.getAttribute("time")) }.getOrNull()
            val children = day.getElementsByTagNameNS("*", "Cube")
            for (j in 0 until children.length) {
                val rate = children.item(j) as Element
                n++
                val currency = rate.getAttribute("currency").trim().uppercase()
                val value = rate.getAttribute("rate").trim().toBigDecimalOrNull()
                when {
                    date == null -> issues += ParseIssue(n, "Unreadable date ${day.getAttribute("time")}")
                    !currency.matches(CURRENCY) -> issues += ParseIssue(n, "Unreadable currency '$currency'")
                    value == null || value.signum() <= 0 -> issues += ParseIssue(n, "Unreadable rate '${rate.getAttribute("rate")}' for $currency")
                    else -> rows += ParsedRow.Fx(n, date, base, currency, value)
                }
            }
        }
        return ParsedFile(rows, issues, n)
    }

    /** One row per date, one column per currency (the ECB history CSV); `N/A` and blanks mean no rate that day. */
    private fun wideCsv(format: ImportFormatView, text: String): ParsedFile {
        val records = Csv.parse(text, format.delimiter).drop(format.skipLines).filter { r -> r.any { it.isNotBlank() } }
        if (records.isEmpty()) return ParsedFile(emptyList(), listOf(ParseIssue(0, "Empty file")), 0)
        val header = records.first().map { it.trim() }
        val dateColumn = header.indexOf(format.columnMapping["date"] ?: "Date")
        if (dateColumn < 0) return ParsedFile(emptyList(), listOf(ParseIssue(1, "No ${format.columnMapping["date"] ?: "Date"} column")), records.size)
        val base = format.constants["base"] ?: "EUR"
        val dates = DateTimeFormatter.ofPattern(format.dateFormat)
        val rows = mutableListOf<ParsedRow>()
        val issues = mutableListOf<ParseIssue>()
        records.drop(1).forEachIndexed { index, record ->
            val lineNo = index + 2 + format.skipLines
            val date = runCatching { LocalDate.parse(record.getOrNull(dateColumn)?.trim(), dates) }.getOrNull()
            if (date == null) {
                issues += ParseIssue(lineNo, "Unreadable date '${record.getOrNull(dateColumn)}'")
                return@forEachIndexed
            }
            header.forEachIndexed { column, currency ->
                if (column == dateColumn || !currency.uppercase().matches(CURRENCY)) return@forEachIndexed
                val raw = record.getOrNull(column)?.trim().orEmpty()
                if (raw.isEmpty() || raw.equals("N/A", ignoreCase = true)) return@forEachIndexed
                val value = number(raw, format.decimalSeparator)
                if (value == null || value.signum() <= 0) {
                    issues += ParseIssue(lineNo, "Unreadable rate '$raw' for $currency")
                } else {
                    rows += ParsedRow.Fx(lineNo, date, base, currency.uppercase(), value)
                }
            }
        }
        return ParsedFile(rows, issues, records.size - 1)
    }

    /** One price per row; columns named by the mapping, missing fields taken from the format's constants. */
    private fun longCsv(format: ImportFormatView, text: String): ParsedFile {
        val records = Csv.parse(text, format.delimiter).drop(format.skipLines).filter { r -> r.any { it.isNotBlank() } }
        if (records.isEmpty()) return ParsedFile(emptyList(), listOf(ParseIssue(0, "Empty file")), 0)
        val header = records.first().map { it.trim() }

        fun column(field: String): Int? = format.columnMapping[field]?.let { name -> header.indexOf(name).takeIf { it >= 0 } }
        val missing = listOf("date", "instrument", "value").filter { column(it) == null }
        if (missing.isNotEmpty()) return ParsedFile(emptyList(), listOf(ParseIssue(1, "Missing columns for ${missing.joinToString()}")), records.size)
        val dates = DateTimeFormatter.ofPattern(format.dateFormat)
        val rows = mutableListOf<ParsedRow>()
        val issues = mutableListOf<ParseIssue>()
        records.drop(1).forEachIndexed { index, record ->
            val lineNo = index + 2 + format.skipLines

            fun field(name: String): String? = column(name)?.let { record.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() } ?: format.constants[name]
            val date = runCatching { LocalDate.parse(field("date"), dates) }.getOrNull()
            val instrument = field("instrument")
            val value = field("value")?.let { number(it, format.decimalSeparator) }
            val type = field("quoteType")?.let { t -> QuoteType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } }
            val currency = field("currency")?.uppercase()
            when {
                date == null -> issues += ParseIssue(lineNo, "Unreadable date '${field("date")}'")
                instrument.isNullOrBlank() -> issues += ParseIssue(lineNo, "No instrument")
                // Positivity depends on the instrument (rates may be negative) and is checked when the price is stored.
                value == null -> issues += ParseIssue(lineNo, "Unreadable price '${field("value")}'")
                type == null -> issues += ParseIssue(lineNo, "Unknown quote type '${field("quoteType")}'")
                currency != null && !currency.matches(CURRENCY) -> issues += ParseIssue(lineNo, "Unreadable currency '$currency'")
                else -> rows += ParsedRow.Price(lineNo, date, instrument, type, value, currency)
            }
        }
        return ParsedFile(rows, issues, records.size - 1)
    }

    private fun number(raw: String, decimalSeparator: Char): BigDecimal? {
        val normalised = raw.replace(" ", "").replace(" ", "").let {
            if (decimalSeparator == ',') it.replace(".", "").replace(',', '.') else it.replace(",", "")
        }
        return normalised.toBigDecimalOrNull()
    }

    private val CURRENCY = Regex("^[A-Z]{3}$")
}
