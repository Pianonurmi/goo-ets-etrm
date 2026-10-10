package com.pianonurmi.etrm.ledger.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

enum class AccountType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE, MEMO }

enum class PostingSide { DEBIT, CREDIT }

enum class EntryStatus { POSTED, REVERSED, REVERSAL }

enum class PeriodStatus { OPEN, SOFT_CLOSED, CLOSED }

data class GlAccountView(
    val id: UUID,
    val legalEntityId: UUID,
    val code: String,
    val name: String,
    val type: AccountType,
    val glMapping: String?,
    val active: Boolean,
)

/** One line of a posting rule: which account, which side, and which fact supplies the amount. */
data class RuleLine(val side: PostingSide, val account: String, val amount: String)

data class PostingRuleView(
    val id: UUID,
    val legalEntityId: UUID?,
    val ruleCode: String,
    val eventType: String,
    /** Attribute equality conditions, all of which must hold (e.g. `{"movementType": "PURCHASE"}`). */
    val conditions: Map<String, String>,
    val lines: List<RuleLine>,
    val description: String?,
    val priority: Int,
    val effectiveFrom: LocalDate,
    val active: Boolean,
    val version: Int,
)

data class JournalLineView(
    val lineNo: Int,
    val accountCode: String,
    val accountName: String,
    val debit: BigDecimal,
    val credit: BigDecimal,
    val currency: String,
    val fxRate: BigDecimal,
    val baseDebit: BigDecimal,
    val baseCredit: BigDecimal,
    val dimensions: Map<String, String?>,
    val memo: String?,
)

data class JournalEntryView(
    val id: UUID,
    val entryNumber: Long,
    val legalEntityId: UUID,
    val period: String,
    val postingDate: LocalDate,
    val eventType: String,
    val ruleCode: String?,
    val sourceType: String?,
    val sourceRef: String?,
    val eventId: UUID?,
    val description: String,
    val currency: String,
    val status: EntryStatus,
    val reversalOf: UUID?,
    val reversedBy: UUID?,
    val createdBy: String,
    val createdAt: Instant,
    val prevHash: String?,
    val hash: String,
    val lines: List<JournalLineView>,
) {
    val totalDebit: BigDecimal get() = lines.sumOf { it.debit }
    val totalCredit: BigDecimal get() = lines.sumOf { it.credit }
}

/**
 * Business facts about one event, from which posting rules derive journal entries. Amounts are totals in the
 * transaction currency; attributes drive rule selection; dimensions are carried onto every line.
 */
data class PostingFacts(
    val legalEntityId: UUID,
    val eventType: String,
    val eventId: UUID?,
    val postingDate: LocalDate,
    val currency: String,
    val sourceType: String?,
    val sourceRef: String?,
    val description: String,
    val amounts: Map<String, BigDecimal>,
    val attributes: Map<String, String> = emptyMap(),
    val dimensions: Map<String, String?> = emptyMap(),
) {
    /** Derived attributes available to rule conditions. */
    val effectiveAttributes: Map<String, String>
        get() = attributes + amounts.filterValues { it.signum() > 0 }.keys.associate { "has${it.replaceFirstChar(Char::uppercase)}" to "true" }
}

data class ManualLine(
    val accountCode: String,
    val debit: BigDecimal = BigDecimal.ZERO,
    val credit: BigDecimal = BigDecimal.ZERO,
    val memo: String? = null,
    val dimensions: Map<String, String?> = emptyMap(),
)

data class ManualJournal(
    val legalEntityId: UUID,
    val postingDate: LocalDate,
    val description: String,
    val currency: String,
    val lines: List<ManualLine>,
    val sourceRef: String? = null,
)

data class TrialBalanceRow(
    val accountCode: String,
    val accountName: String,
    val type: AccountType,
    val glMapping: String?,
    val debit: BigDecimal,
    val credit: BigDecimal,
) {
    /** Debit-normal balance: positive for assets/expenses in debit, negative otherwise. */
    val balance: BigDecimal get() = debit - credit
}

data class AccountingPeriodView(val legalEntityId: UUID, val period: String, val status: PeriodStatus, val closedBy: String?, val closedAt: Instant?)

data class GlExportView(
    val id: UUID,
    val legalEntityId: UUID,
    /** Export number per legal entity, without gaps. */
    val exportSeq: Int,
    val period: String,
    val exportedAt: Instant,
    val exportedBy: String,
    val entryCount: Int,
    val contentHash: String,
    val content: String,
)

data class UpsertPostingRule(
    val legalEntityId: UUID? = null,
    val ruleCode: String,
    val eventType: String,
    val conditions: Map<String, String> = emptyMap(),
    val lines: List<RuleLine>,
    val description: String? = null,
    val priority: Int = 100,
    val effectiveFrom: LocalDate = LocalDate.of(2000, 1, 1),
    val active: Boolean = true,
)

interface LedgerCommand {
    /** Applies every matching posting rule; returns the entries created (possibly none). Idempotent per (event, rule). */
    fun post(facts: PostingFacts): List<JournalEntryView>

    fun postManual(manual: ManualJournal): JournalEntryView

    fun reverse(entryId: UUID, reason: String): JournalEntryView

    fun closePeriod(legalEntityId: UUID, period: YearMonth, hard: Boolean): AccountingPeriodView

    fun reopenPeriod(legalEntityId: UUID, period: YearMonth): AccountingPeriodView

    fun exportGl(legalEntityId: UUID, period: YearMonth): GlExportView

    fun ensureChartOfAccounts(legalEntityId: UUID): List<GlAccountView>

    fun upsertRule(command: UpsertPostingRule): PostingRuleView
}

interface LedgerQuery {
    fun entry(id: UUID): JournalEntryView?

    fun entries(legalEntityId: UUID, period: YearMonth? = null, sourceRef: String? = null, limit: Int = 500): List<JournalEntryView>

    fun trialBalance(legalEntityId: UUID, period: YearMonth): List<TrialBalanceRow>

    fun accounts(legalEntityId: UUID): List<GlAccountView>

    fun rules(legalEntityId: UUID?): List<PostingRuleView>

    fun periods(legalEntityId: UUID): List<AccountingPeriodView>

    /** The rate used for postings (from market data). */
    fun fxRate(date: LocalDate, from: String, to: String): BigDecimal?

    /** Ids of entries whose hash chain is broken, per entity. */
    fun verifyJournal(legalEntityId: UUID): List<UUID>
}

/** A GL export was produced; the audit trail records who exported what. */
data class GlExported(
    val exportId: UUID,
    val legalEntityId: UUID,
    val exportSeq: Int,
    val period: String,
    val entryCount: Int,
    val contentHash: String,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata

data class JournalEntryPosted(val entry: JournalEntryView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
