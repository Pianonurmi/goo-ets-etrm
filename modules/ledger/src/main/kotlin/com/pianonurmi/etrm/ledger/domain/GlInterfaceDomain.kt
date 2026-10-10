package com.pianonurmi.etrm.ledger.domain

import com.pianonurmi.etrm.ledger.api.GlBalanceLine
import com.pianonurmi.etrm.ledger.api.GlExportFormatView
import com.pianonurmi.etrm.ledger.api.GlExportStatus
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.PostingRuleChangeView
import com.pianonurmi.etrm.ledger.api.PostingRuleVersionView
import com.pianonurmi.etrm.ledger.api.RuleChangeStatus
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.text.Csv
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** How a GL file is laid out, stored as data per format (`gl_export_format.layout`). */
data class GlExportLayout(
    val delimiter: String = ",",
    val header: Boolean = true,
    /** GENERIC: the built-in line layout ([GlExportFormat]); COLUMNS: the [columns] below. */
    val style: String = "GENERIC",
    val columns: List<Column> = emptyList(),
) {
    data class Column(
        val name: String,
        val source: String,
        val format: String? = null,
        val debit: String? = null,
        val credit: String? = null,
        val maxLength: Int? = null,
    )
}

/** Writes the non-memo lines of journal entries in a layout. Amounts are absolute with two decimals; the side is its own column. */
object GlExportRenderer {
    fun render(entries: List<JournalEntryView>, memoAccounts: Set<String>, layout: GlExportLayout, entityCode: String, baseCurrency: String): String {
        if (layout.style == "GENERIC") return GlExportFormat.csv(entries, memoAccounts)
        if (layout.delimiter.length != 1) throw ValidationException("A GL layout delimiter is one character")
        val rows = entries.sortedBy { it.entryNumber }.flatMap { e ->
            e.lines.filter { it.accountCode !in memoAccounts }.map { line -> layout.columns.map { value(it, e, line, entityCode, baseCurrency) } }
        }
        val text = Csv.write(layout.columns.map { it.name }, rows, layout.delimiter.single())
        return if (layout.header) text else text.substringAfter("\r\n")
    }

    private fun value(
        column: GlExportLayout.Column,
        entry: JournalEntryView,
        line: com.pianonurmi.etrm.ledger.api.JournalLineView,
        entityCode: String,
        baseCurrency: String,
    ): String {
        val debit = line.debit.signum() > 0
        val raw = when (column.source) {
            "ENTITY_CODE" -> entityCode
            "POSTING_DATE" -> column.format?.let { entry.postingDate.format(DateTimeFormatter.ofPattern(it)) } ?: entry.postingDate.toString()
            "PERIOD" -> entry.period
            "PERIOD_MONTH" -> entry.period.substringAfter('-')
            "ENTRY_NUMBER" -> entry.entryNumber.toString()
            "LINE_NO" -> line.lineNo.toString()
            "ACCOUNT" -> line.accountCode
            "GL_ACCOUNT" -> line.dimensions["glMapping"].orEmpty()
            "DEBIT_CREDIT" -> if (debit) column.debit ?: "D" else column.credit ?: "C"
            "AMOUNT" -> money(if (debit) line.debit else line.credit)
            "CURRENCY" -> line.currency
            "BASE_AMOUNT" -> money(if (debit) line.baseDebit else line.baseCredit)
            "BASE_CURRENCY" -> baseCurrency
            "DESCRIPTION" -> entry.description
            "SOURCE_REF" -> entry.sourceRef.orEmpty()
            "REVERSAL_OF" -> entry.reversalOf?.toString().orEmpty()
            else -> throw ValidationException("Unknown GL column source ${column.source}")
        }
        return column.maxLength?.let { raw.take(it) } ?: raw
    }

    private fun money(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_EVEN).toPlainString()
}

interface GlInterfaceRepository {
    fun formats(): List<GlExportFormatView>

    fun layout(code: String): GlExportLayout?

    /** Ids of the period's entries carried by exports that were not rejected. */
    fun exportedEntryIds(legalEntityId: UUID, period: String): Set<UUID>

    fun insertEntries(exportId: UUID, entryIds: Collection<UUID>)

    fun releaseEntries(exportId: UUID)

    fun find(id: UUID, lock: Boolean = false): GlExportView?

    fun exports(legalEntityId: UUID, period: String?): List<GlExportView>

    fun setStatus(id: UUID, status: GlExportStatus, reason: String?, acknowledgementRef: String?, by: String, at: Instant)

    /** Closing balances by GL account (debit positive, base currency) of non-memo lines dated up to [upTo]. */
    fun subLedgerBalances(legalEntityId: UUID, upTo: LocalDate, exportedOnly: Boolean): Map<String, BigDecimal>

    fun replaceGlBalances(legalEntityId: UUID, period: String, balances: List<GlBalanceLine>, by: String)

    fun glBalances(legalEntityId: UUID, period: String): Map<String, BigDecimal>

    fun insertRuleChange(change: PostingRuleChangeView)

    fun ruleChange(id: UUID, lock: Boolean = false): PostingRuleChangeView?

    fun ruleChanges(status: RuleChangeStatus?): List<PostingRuleChangeView>

    fun decideRuleChange(id: UUID, status: RuleChangeStatus, by: String, at: Instant)

    fun insertRuleVersion(version: PostingRuleVersionView)

    fun ruleHistory(ruleCode: String): List<PostingRuleVersionView>
}
