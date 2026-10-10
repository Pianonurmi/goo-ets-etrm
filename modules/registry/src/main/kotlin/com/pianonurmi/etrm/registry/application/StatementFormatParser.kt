package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.AllowanceStatementLine
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ImportAllowanceStatement
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryTransactionLine
import com.pianonurmi.etrm.registry.api.StatementFormatView
import com.pianonurmi.etrm.registry.api.StatementKind
import com.pianonurmi.etrm.registry.api.TransactionLineStatus
import java.math.BigInteger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/** Reads registry exports through a [StatementFormatView]: the profile says which header carries which field. */
object StatementFormatParser {
    private val HOLDING_FIELDS = setOf("issuanceRef", "certStart", "certEnd", "certRange", "deviceGsrn", "productionStart", "productionEnd")
    private val TRANSACTION_FIELDS = setOf(
        "externalRef",
        "instructionKey",
        "transactionType",
        "issuanceRef",
        "certStart",
        "certEnd",
        "certRange",
        "status",
        "transactionDate",
    )
    private val ALLOWANCE_HOLDING_FIELDS = setOf("productCode", "quantity")
    private val COMPLETED_WORDS = setOf("COMPLETED", "CONFIRMED", "DONE", "EXECUTED", "SETTLED", "ACCEPTED")
    private val FAILED_WORDS = setOf("FAILED", "REJECTED", "CANCELLED", "ERROR")
    private val TRANSFER_WORDS = setOf("TRANSFER", "TRANSFER_OUT", "EXPORT", "TRANSFER-OUT")
    private val CANCELLATION_WORDS = setOf("CANCELLATION", "CANCEL", "REDEMPTION", "CANCELLED_FOR_DISCLOSURE")

    /** Checks that a profile maps the fields its kind needs; returns the list of problems (empty when valid). */
    fun problems(format: StatementFormatView): List<String> {
        val mapped = format.columnMapping.keys
        val allowed = when (format.kind) {
            StatementKind.GOO_HOLDINGS -> HOLDING_FIELDS
            StatementKind.GOO_TRANSACTIONS -> TRANSACTION_FIELDS
            StatementKind.ALLOWANCE_HOLDINGS -> ALLOWANCE_HOLDING_FIELDS
            StatementKind.ALLOWANCE_TRANSACTIONS -> mapped
        }
        val problems = mutableListOf<String>()
        (mapped - allowed).takeIf { it.isNotEmpty() }?.let { problems += "unknown fields $it for ${format.kind}" }
        val hasRange = "certRange" in mapped || ("certStart" in mapped && "certEnd" in mapped)
        when (format.kind) {
            StatementKind.GOO_HOLDINGS -> {
                if ("issuanceRef" !in mapped) problems += "issuanceRef must be mapped"
                if (!hasRange) problems += "certStart and certEnd (or certRange) must be mapped"
            }
            StatementKind.GOO_TRANSACTIONS ->
                if ("externalRef" !in mapped && "instructionKey" !in mapped && !hasRange) {
                    problems += "map externalRef, instructionKey or the certificate range so lines can be matched"
                }
            StatementKind.ALLOWANCE_HOLDINGS ->
                if ("productCode" !in mapped || "quantity" !in mapped) problems += "productCode and quantity must be mapped"
            else -> Unit
        }
        runCatching { DateTimeFormatter.ofPattern(format.dateFormat) }.onFailure { problems += "invalid date format ${format.dateFormat}" }
        return problems
    }

    fun holdings(format: StatementFormatView, registryAccountId: UUID, statementDate: LocalDate, source: String, content: String): ImportStatement {
        requireKind(format, StatementKind.GOO_HOLDINGS)
        val lines = rows(format, content).map { (lineNo, row) ->
            val start = row.date(format, "productionStart", lineNo)
            val end = row.date(format, "productionEnd", lineNo)
            StatementLine(
                lineNo = lineNo,
                issuanceRef = row.required("issuanceRef", lineNo),
                range = row.range(format, lineNo) ?: throw ValidationException("Line $lineNo has no certificate range"),
                deviceGsrn = row.fields["deviceGsrn"],
                productionPeriod = if (start != null && end != null) LocalDateRange(start, end) else null,
                raw = row.raw,
            )
        }
        return ImportStatement(registryCode = "", registryAccountId = registryAccountId, statementDate = statementDate, source = source, lines = lines)
    }

    /**
     * Balances per allowance product. Several lines for one product (e.g. per unit type or vintage the registry
     * reports separately) are added up; `product.<word>` constants map registry unit names to product codes.
     */
    fun allowanceHoldings(
        format: StatementFormatView,
        registryAccountId: UUID,
        statementDate: LocalDate,
        source: String,
        content: String,
    ): ImportAllowanceStatement {
        requireKind(format, StatementKind.ALLOWANCE_HOLDINGS)
        val rows = rows(format, content).map { (lineNo, row) ->
            val word = row.required("productCode", lineNo).trim().uppercase()
            val product = format.constants["product.$word"] ?: word
            Triple(lineNo, product, quantity(row.required("quantity", lineNo), lineNo))
        }
        val lines = rows.groupBy { it.second }.entries.mapIndexed { i, (product, group) ->
            AllowanceStatementLine(
                lineNo = i + 1,
                productCode = product,
                quantity = group.sumOf { it.third },
                raw = mapOf("lines" to group.map { it.first }),
            )
        }
        return ImportAllowanceStatement("", registryAccountId, statementDate, source, lines)
    }

    private fun quantity(text: String, lineNo: Int): Long = try {
        java.math.BigDecimal(text.replace(" ", "").replace("\u00A0", "").replace("_", "")).longValueExact()
    } catch (_: NumberFormatException) {
        throw ValidationException("Line $lineNo: quantity '$text' is not a whole number")
    } catch (_: ArithmeticException) {
        throw ValidationException("Line $lineNo: quantity '$text' is not a whole number")
    }

    fun transactions(format: StatementFormatView, content: String): List<RegistryTransactionLine> {
        requireKind(format, StatementKind.GOO_TRANSACTIONS)
        return rows(format, content).map { (lineNo, row) ->
            RegistryTransactionLine(
                lineNo = lineNo,
                externalRef = row.fields["externalRef"],
                instructionKey = row.fields["instructionKey"],
                transactionType = row.fields["transactionType"]?.let { type(format, it) },
                issuanceRef = row.fields["issuanceRef"],
                range = row.range(format, lineNo),
                status = row.fields["status"]?.let { status(format, it) } ?: TransactionLineStatus.COMPLETED,
                transactionDate = row.date(format, "transactionDate", lineNo),
                raw = row.raw,
            )
        }
    }

    private class Row(val fields: Map<String, String>, val raw: Map<String, String>) {
        fun required(field: String, lineNo: Int): String = fields[field] ?: throw ValidationException("Line $lineNo has no $field")

        fun date(format: StatementFormatView, field: String, lineNo: Int): LocalDate? = fields[field]?.let {
            try {
                LocalDate.parse(it, DateTimeFormatter.ofPattern(format.dateFormat))
            } catch (ex: DateTimeParseException) {
                throw ValidationException("Line $lineNo: $field '$it' does not match ${format.dateFormat}")
            }
        }

        fun range(format: StatementFormatView, lineNo: Int): CertificateRange? {
            val combined = fields["certRange"]
            val (start, end) = if (combined != null) {
                val separator = format.constants["rangeSeparator"] ?: "-"
                combined.split(separator).map { it.trim() }.takeIf { it.size == 2 }?.let { it[0] to it[1] }
                    ?: throw ValidationException("Line $lineNo: certificate range '$combined' is not 'start${separator}end'")
            } else {
                (fields["certStart"] ?: return null) to (fields["certEnd"] ?: return null)
            }
            return try {
                CertificateRange(BigInteger(start), BigInteger(end))
            } catch (ex: NumberFormatException) {
                throw ValidationException("Line $lineNo: certificate numbers '$start'..'$end' are not numeric")
            } catch (ex: IllegalArgumentException) {
                throw ValidationException("Line $lineNo: ${ex.message}")
            }
        }
    }

    private fun rows(format: StatementFormatView, content: String): List<Pair<Int, Row>> {
        val records = Csv.parse(content.removePrefix(""), format.delimiter).drop(format.skipLines)
            .filterNot { it.first().trimStart().startsWith("#") }
        if (records.isEmpty()) throw ValidationException("Statement is empty")
        val header = records.first().map { it.trim().lowercase() }
        val index = format.columnMapping.mapValues { (field, column) ->
            header.indexOf(column.trim().lowercase()).takeIf { it >= 0 }
                ?: throw ValidationException("Statement has no column '$column' (mapped to $field) for profile ${format.profileCode}")
        }
        val mappedColumns = index.values.toSet()
        return records.drop(1).mapIndexed { i, cells ->
            val lineNo = i + 1
            if (cells.size < header.size) throw ValidationException("Line $lineNo has ${cells.size} cells, expected ${header.size}")
            val fields = index.mapValues { (_, column) -> cells[column].trim() }.filterValues { it.isNotEmpty() }
            val raw = header.indices.filter { it !in mappedColumns }.associate { header[it] to cells[it].trim() }
            lineNo to Row(fields, raw)
        }
    }

    private fun status(format: StatementFormatView, word: String): TransactionLineStatus {
        val key = word.trim().uppercase()
        format.constants["status.$key"]?.let { return TransactionLineStatus.valueOf(it) }
        return when (key) {
            in COMPLETED_WORDS -> TransactionLineStatus.COMPLETED
            in FAILED_WORDS -> TransactionLineStatus.FAILED
            else -> TransactionLineStatus.OTHER
        }
    }

    private fun type(format: StatementFormatView, word: String): InstructionType? {
        val key = word.trim().uppercase()
        format.constants["type.$key"]?.let { return InstructionType.valueOf(it) }
        return when (key) {
            in TRANSFER_WORDS -> InstructionType.TRANSFER
            in CANCELLATION_WORDS -> InstructionType.CANCELLATION
            else -> null
        }
    }

    private fun requireKind(format: StatementFormatView, kind: StatementKind) {
        if (format.kind != kind) throw ValidationException("Profile ${format.profileCode} is for ${format.kind}, not $kind")
    }
}
