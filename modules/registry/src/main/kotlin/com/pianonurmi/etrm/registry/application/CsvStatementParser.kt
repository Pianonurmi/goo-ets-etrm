package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.registry.api.StatementParser
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID

/**
 * Generic CSV statement format accepted for every registry until registry-specific parsers are delivered:
 * `issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end[,extra columns...]`.
 * Extra columns are kept verbatim in the line's raw map for break analysis.
 */
@Component
class CsvStatementParser : StatementParser {
    override val registryCode: String = "*"

    override fun parse(registryAccountId: UUID, statementDate: LocalDate, source: String, content: String): ImportStatement {
        val rows = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (rows.isEmpty()) throw ValidationException("Statement is empty")
        val header = rows.first().split(',').map { it.trim().lowercase() }
        val required = listOf("issuance_ref", "cert_no_start", "cert_no_end")
        required.filter { it !in header }.takeIf { it.isNotEmpty() }?.let { throw ValidationException("Statement is missing columns $it") }
        val lines = rows.drop(1).mapIndexed { index, row ->
            val cells = row.split(',').map { it.trim() }
            if (cells.size < header.size) throw ValidationException("Line ${index + 2} has ${cells.size} cells, expected ${header.size}")
            val col = header.zip(cells).toMap()
            val start = col.getValue("production_start").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            val end = col["production_end"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            StatementLine(
                lineNo = index + 1,
                issuanceRef = col.getValue("issuance_ref"),
                range = CertificateRange(BigInteger(col.getValue("cert_no_start")), BigInteger(col.getValue("cert_no_end"))),
                deviceGsrn = col["device_gsrn"]?.takeIf { it.isNotBlank() },
                productionPeriod = if (start != null && end != null) LocalDateRange(start, end) else null,
                raw = col.filterKeys { it !in setOf("issuance_ref", "cert_no_start", "cert_no_end", "device_gsrn", "production_start", "production_end") },
            )
        }
        return ImportStatement(registryCode = "", registryAccountId = registryAccountId, statementDate = statementDate, source = source, lines = lines)
    }
}
