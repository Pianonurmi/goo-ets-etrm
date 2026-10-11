package com.pianonurmi.etrm.platform.text

/**
 * RFC 4180 CSV: fields containing the delimiter, a quote or a line break are quoted, quotes are doubled.
 * [parse] accepts quoted fields spanning lines and both LF and CRLF line endings.
 */
object Csv {
    fun field(value: Any?, delimiter: Char = ','): String {
        val text = value?.toString() ?: return ""
        val needsQuotes = text.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    fun line(values: List<Any?>, delimiter: Char = ','): String = values.joinToString(delimiter.toString()) { field(it, delimiter) }

    fun write(header: List<String>, rows: List<List<Any?>>, delimiter: Char = ','): String = buildString {
        append(line(header, delimiter)).append("\r\n")
        rows.forEach { append(line(it, delimiter)).append("\r\n") }
    }

    /**
     * CSV for people to open in a spreadsheet: text that a spreadsheet would run as a formula (starting with `=`, `+`,
     * `-`, `@`, a tab or a carriage return) gets a leading `'`. Numbers, including negative ones, are left alone.
     * Files for other systems (registry, GL interface) use [write], which never alters a value.
     */
    fun writeForSpreadsheet(header: List<String>, rows: List<List<Any?>>, delimiter: Char = ','): String =
        write(header.map { defuse(it).toString() }, rows.map { row -> row.map(::defuse) }, delimiter)

    fun defuse(value: Any?): Any? {
        if (value !is CharSequence) return value
        val text = value.toString()
        return if (text.isNotEmpty() && text[0] in FORMULA_START && !NUMBER.matches(text)) "'$text" else value
    }

    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')
    private val NUMBER = Regex("[+-]?\\d+(\\.\\d+)?")

    /** Parses [content] into records of fields. Blank lines are skipped. */
    @Suppress("CyclomaticComplexMethod")
    fun parse(content: String, delimiter: Char = ','): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var fieldStarted = false
        var i = 0

        fun endRecord() {
            if (fieldStarted || current.isNotEmpty() || fields.isNotEmpty()) {
                fields += current.toString()
                if (!(fields.size == 1 && fields[0].isBlank())) records += fields
            }
            fields = mutableListOf()
            current.clear()
            fieldStarted = false
        }

        while (i < content.length) {
            val c = content[i]
            when {
                inQuotes && c == '"' && i + 1 < content.length && content[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> current.append(c)
                c == '"' && current.isEmpty() -> {
                    inQuotes = true
                    fieldStarted = true
                }
                c == delimiter -> {
                    fields += current.toString()
                    current.clear()
                    fieldStarted = true
                }
                c == '\r' -> Unit
                c == '\n' -> endRecord()
                else -> current.append(c)
            }
            i++
        }
        endRecord()
        return records
    }
}
