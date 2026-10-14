package com.pianonurmi.etrm.ledger.domain

import com.pianonurmi.etrm.ledger.api.AccountingPeriodView
import com.pianonurmi.etrm.ledger.api.GlAccountView
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.JournalLineView
import com.pianonurmi.etrm.ledger.api.PeriodStatus
import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.PostingSide
import com.pianonurmi.etrm.ledger.api.TrialBalanceRow
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.text.Csv
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Selects the posting rules that apply to a set of facts: entity-specific rules override defaults by rule code. */
object RuleSelector {
    fun select(rules: List<PostingRuleView>, facts: PostingFacts): List<PostingRuleView> {
        val attributes = facts.effectiveAttributes
        val candidates = rules.filter { it.active && it.eventType == facts.eventType && !it.effectiveFrom.isAfter(facts.postingDate) }
            .filter { it.legalEntityId == null || it.legalEntityId == facts.legalEntityId }
            .filter { rule -> rule.conditions.all { (key, value) -> attributes[key] == value } }
        return candidates.groupBy { it.ruleCode }
            .map { (_, group) -> group.firstOrNull { it.legalEntityId != null } ?: group.first() }
            .sortedWith(compareBy({ it.priority }, { it.ruleCode }))
    }
}

/**
 * Builds a balanced entry from a rule and facts. Returns null when an amount fact is missing or negative, or when all
 * amounts are zero; zero lines are left out (e.g. no VAT line on a reverse-charged invoice).
 */
private val CENT = BigDecimal("0.01")

object EntryBuilder {
    data class Draft(val accountCode: String, val side: PostingSide, val amount: BigDecimal, val memo: String?)

    fun draft(rule: PostingRuleView, facts: PostingFacts): List<Draft>? {
        val lines = rule.lines.mapNotNull { line ->
            val amount = facts.amounts[line.amount] ?: return null
            if (amount.signum() < 0) return null
            if (amount.signum() == 0) return@mapNotNull null
            Draft(line.account, line.side, amount.setScale(2, RoundingMode.HALF_EVEN), rule.description)
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    fun requireBalanced(lines: List<JournalLineView>) {
        val debit = lines.sumOf { it.debit }
        val credit = lines.sumOf { it.credit }
        if (debit.compareTo(credit) != 0) throw ValidationException("Journal entry is unbalanced: debit $debit, credit $credit")
        val baseDebit = lines.sumOf { it.baseDebit }
        val baseCredit = lines.sumOf { it.baseCredit }
        if (baseDebit.compareTo(
                baseCredit,
            ) != 0
        ) {
            throw ValidationException("Journal entry is unbalanced in base currency: debit $baseDebit, credit $baseCredit")
        }
        if (lines.isEmpty()) throw ValidationException("Journal entry has no lines")
        lines.forEach {
            if (it.debit.signum() < 0 || it.credit.signum() < 0) throw ValidationException("Negative amounts are not allowed; post a reversal instead")
            if (it.debit.signum() > 0 && it.credit.signum() > 0) throw ValidationException("A line may be debit or credit, not both")
        }
    }

    /**
     * Base amounts are rounded per line, so a foreign-currency entry with several lines can be a cent off in base
     * currency (review finding D7). The difference goes on the last line of the larger side; more than a cent per
     * line is not rounding, and is refused.
     */
    fun balanceBase(lines: List<JournalLineView>): List<JournalLineView> {
        val difference = lines.sumOf { it.baseDebit } - lines.sumOf { it.baseCredit }
        if (difference.signum() == 0) return lines
        if (difference.abs() > CENT * BigDecimal(lines.size)) {
            throw ValidationException("Journal entry is unbalanced in base currency by $difference")
        }
        val index = if (difference.signum() > 0) lines.indexOfLast { it.baseDebit.signum() > 0 } else lines.indexOfLast { it.baseCredit.signum() > 0 }
        return lines.mapIndexed { i, line ->
            when {
                i != index -> line
                difference.signum() > 0 -> line.copy(baseDebit = line.baseDebit - difference)
                else -> line.copy(baseCredit = line.baseCredit + difference)
            }
        }
    }

    fun periodOf(date: LocalDate): String = YearMonth.from(date).toString()

    fun requireOpen(period: AccountingPeriodView?, manual: Boolean) {
        val status = period?.status ?: PeriodStatus.OPEN
        when (status) {
            PeriodStatus.OPEN -> Unit
            PeriodStatus.SOFT_CLOSED -> if (!manual) throw ValidationException("Period ${period!!.period} is soft-closed; only manual adjustments are allowed")
            PeriodStatus.CLOSED -> throw ValidationException("Period ${period!!.period} is closed")
        }
    }
}

enum class CostingMethod { FIFO, WEIGHTED_AVERAGE }

data class CostLayer(
    val id: UUID,
    val legalEntityId: UUID,
    val assetKey: String,
    val acquiredOn: LocalDate,
    val quantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unitCost: BigDecimal,
    val currency: String,
    val sourceRef: String?,
)

data class Consumption(val cost: BigDecimal, val currency: String?, val consumed: List<Pair<CostLayer, BigDecimal>>)

/** FIFO and weighted-average consumption of cost layers. */
object CostingEngine {
    fun consume(openLayers: List<CostLayer>, quantity: BigDecimal, method: CostingMethod): Consumption {
        if (quantity.signum() <= 0) throw ValidationException("Quantity to consume must be positive")
        val available = openLayers.sumOf { it.remainingQuantity }
        if (available < quantity) throw ValidationException("Insufficient cost layers: need $quantity, have $available")
        val currency = openLayers.firstOrNull()?.currency
        return when (method) {
            CostingMethod.FIFO -> {
                var left = quantity
                val taken = mutableListOf<Pair<CostLayer, BigDecimal>>()
                for (layer in openLayers.sortedWith(compareBy({ it.acquiredOn }, { it.id }))) {
                    if (left.signum() == 0) break
                    val qty = minOf(left, layer.remainingQuantity)
                    taken += layer to qty
                    left -= qty
                }
                Consumption(taken.sumOf { (l, q) -> l.unitCost * q }.setScale(2, RoundingMode.HALF_EVEN), currency, taken)
            }
            CostingMethod.WEIGHTED_AVERAGE -> {
                val totalCost = openLayers.sumOf { it.unitCost * it.remainingQuantity }
                val average = totalCost.divide(available, 10, RoundingMode.HALF_EVEN)
                // Reduce layers in proportion so the average is preserved for later consumption, in whole units:
                // allowances and certificates are whole, so each layer gets its floored share and the units left over
                // go to the layers with the largest remainders (review finding F10).
                val shares = openLayers.map { layer -> layer to (layer.remainingQuantity * quantity).divide(available, 10, RoundingMode.HALF_EVEN) }
                val floors = shares.map { (layer, share) -> layer to share.setScale(0, RoundingMode.DOWN).min(layer.remainingQuantity) }
                var left = quantity - floors.sumOf { it.second }
                val amounts = floors.associate { (layer, qty) -> layer.id to qty }.toMutableMap()
                shares.sortedByDescending { (_, share) -> share - share.setScale(0, RoundingMode.DOWN) }.forEach { (layer, _) ->
                    if (left.signum() > 0 && amounts.getValue(layer.id) < layer.remainingQuantity) {
                        amounts[layer.id] = amounts.getValue(layer.id) + BigDecimal.ONE.min(left)
                        left -= BigDecimal.ONE.min(left)
                    }
                }
                val taken = openLayers.map { it to amounts.getValue(it.id) }.filter { it.second.signum() > 0 }
                Consumption((average * quantity).setScale(2, RoundingMode.HALF_EVEN), currency, taken)
            }
        }
    }
}

object TrialBalance {
    fun compute(entries: List<JournalEntryView>, accounts: List<GlAccountView>): List<TrialBalanceRow> {
        val byCode = accounts.associateBy { it.code }
        return entries.flatMap { it.lines }
            .groupBy { it.accountCode }
            .map { (code, lines) ->
                val account = byCode.getValue(code)
                TrialBalanceRow(code, account.name, account.type, account.glMapping, lines.sumOf { it.baseDebit }, lines.sumOf { it.baseCredit })
            }
            .sortedBy { it.accountCode }
    }
}

/** Commitment memo posted for one delivery schedule of a confirmed trade, with what has been released so far. */
data class CommitmentMemo(
    val id: UUID,
    val legalEntityId: UUID,
    val tradeId: UUID,
    val tradeNumber: String,
    val tradeVersion: Int,
    val scheduleId: UUID,
    val side: String,
    val productFamily: String,
    val currency: String,
    val quantity: BigDecimal,
    val amount: BigDecimal,
    val released: BigDecimal,
) {
    val open: BigDecimal get() = amount - released
}

enum class MemoMovementKind { RELEASE, REVERSAL }

interface CommitmentMemoRepository {
    /** Inserts the memo unless one exists for this confirmation and schedule; returns whether it was inserted. */
    fun insert(memo: CommitmentMemo, confirmedEventId: UUID): Boolean

    fun openForSchedule(scheduleId: UUID): List<CommitmentMemo>

    fun openForTrade(tradeId: UUID): List<CommitmentMemo>

    /** Records a release or reversal once per event and memo; returns whether it was recorded now. */
    fun move(eventId: UUID, memoId: UUID, kind: MemoMovementKind, amount: BigDecimal): Boolean
}

interface SeedRepository {
    fun version(name: String): Int?

    fun record(name: String, version: Int)
}

/** CSV for the general ledger (RFC 4180): one row per journal line, memo accounts left out, amounts with 2 decimals. */
object GlExportFormat {
    val header = listOf(
        "entry_number",
        "posting_date",
        "period",
        "account",
        "gl_mapping",
        "debit",
        "credit",
        "currency",
        "base_debit",
        "base_credit",
        "description",
        "source_type",
        "source_ref",
        "reversal_of",
        "dimensions",
    )

    fun csv(entries: List<JournalEntryView>, memoAccounts: Set<String>): String = Csv.writeForSpreadsheet(
        header,
        entries.sortedBy { it.entryNumber }.flatMap { e ->
            e.lines.filter { it.accountCode !in memoAccounts }.map { l ->
                listOf(
                    e.entryNumber,
                    e.postingDate,
                    e.period,
                    l.accountCode,
                    l.dimensions["glMapping"],
                    amount(l.debit),
                    amount(l.credit),
                    l.currency,
                    amount(l.baseDebit),
                    amount(l.baseCredit),
                    e.description,
                    e.sourceType,
                    e.sourceRef,
                    e.reversalOf,
                    l.dimensions.filterKeys { it != "glMapping" }.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value ?: ""}" },
                )
            }
        },
    )

    private fun amount(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_EVEN).toPlainString()
}

interface GlAccountRepository {
    fun accounts(legalEntityId: UUID): List<GlAccountView>

    fun insert(account: GlAccountView)
}

interface PostingRuleRepository {
    fun all(): List<PostingRuleView>

    fun upsert(rule: PostingRuleView)
}

interface JournalRepository {
    fun nextEntryNumber(): Long

    fun lastHash(legalEntityId: UUID): String?

    fun insert(entry: JournalEntryView, accountIds: Map<String, UUID>)

    /** Marks a POSTED entry REVERSED; false when it was not POSTED any more. */
    fun markReversed(entryId: UUID, reversalId: UUID): Boolean

    fun find(id: UUID): JournalEntryView?

    fun findByEvent(eventId: UUID, ruleCode: String): JournalEntryView?

    fun entries(
        legalEntityId: UUID,
        period: String?,
        sourceRef: String?,
        limit: Int,
        accountCode: String? = null,
        afterEntryNumber: Long? = null,
    ): List<JournalEntryView>

    /** The entity's whole journal in entry number order, with the chain version each entry was hashed with. */
    fun chain(legalEntityId: UUID): List<ChainedEntry>

    fun lockEntity(legalEntityId: UUID)

    /**
     * Balance of an account's lines dated on or before [upTo], optionally only of entries of [eventType], lines in
     * [currency], and lines whose dimensions contain [dimensions].
     */
    fun balance(
        legalEntityId: UUID,
        accountCode: String,
        upTo: LocalDate,
        eventType: String? = null,
        currency: String? = null,
        dimensions: Map<String, String> = emptyMap(),
    ): LineBalance

    /** Currencies of an account's lines dated on or before [upTo]. */
    fun currencies(legalEntityId: UUID, accountCode: String, upTo: LocalDate): Set<String>
}

interface PeriodRepository {
    fun find(legalEntityId: UUID, period: String): AccountingPeriodView?

    fun upsert(period: AccountingPeriodView)

    fun periods(legalEntityId: UUID): List<AccountingPeriodView>
}

interface CostLayerRepository {
    fun open(legalEntityId: UUID, assetKey: String): List<CostLayer>

    fun insert(layer: CostLayer)

    fun reduce(layerId: UUID, quantity: BigDecimal)

    /** Open layers of every asset key starting with [assetKeyPrefix], without locking (for measurement). */
    fun openAll(legalEntityId: UUID, assetKeyPrefix: String): List<CostLayer>
}

interface GlExportRepository {
    fun insert(export: GlExportView)

    /** Next export number for the entity; callers hold the entity's journal lock. */
    fun nextSeq(legalEntityId: UUID): Int

    fun latest(legalEntityId: UUID, period: String): GlExportView?
}
