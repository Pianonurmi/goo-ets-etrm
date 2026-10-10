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
        if (lines.isEmpty()) throw ValidationException("Journal entry has no lines")
        lines.forEach {
            if (it.debit.signum() < 0 || it.credit.signum() < 0) throw ValidationException("Negative amounts are not allowed; post a reversal instead")
            if (it.debit.signum() > 0 && it.credit.signum() > 0) throw ValidationException("A line may be debit or credit, not both")
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
                // Reduce layers proportionally so the average is preserved for later consumption.
                val taken = openLayers.map { layer -> layer to (layer.remainingQuantity * quantity).divide(available, 3, RoundingMode.DOWN) }
                    .filter { it.second.signum() > 0 }
                    .toMutableList()
                val rounding = quantity - taken.sumOf { it.second }
                if (rounding.signum() > 0 && taken.isNotEmpty()) {
                    val (layer, qty) = taken.last()
                    taken[taken.size - 1] = layer to (qty + rounding)
                }
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

object GlExportFormat {
    fun csv(entries: List<JournalEntryView>): String = buildString {
        appendLine(
            "entry_number,posting_date,period,account,gl_mapping,debit,credit,currency,base_debit,base_credit,description,source_type,source_ref,dimensions",
        )
        entries.sortedBy { it.entryNumber }.forEach { e ->
            e.lines.forEach { l ->
                appendLine(
                    listOf(
                        e.entryNumber,
                        e.postingDate,
                        e.period,
                        l.accountCode,
                        l.glMapping ?: "",
                        l.debit,
                        l.credit,
                        l.currency,
                        l.baseDebit,
                        l.baseCredit,
                        quote(e.description),
                        e.sourceType ?: "",
                        e.sourceRef ?: "",
                        quote(
                            l.dimensions.entries.joinToString(";") {
                                "${it.key}=${it.value ?: ""}"
                            },
                        ),
                    ).joinToString(","),
                )
            }
        }
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private val JournalLineView.glMapping: String? get() = dimensions["glMapping"]
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

    fun markReversed(entryId: UUID, reversalId: UUID)

    fun find(id: UUID): JournalEntryView?

    fun findByEvent(eventId: UUID, ruleCode: String): JournalEntryView?

    fun entries(legalEntityId: UUID, period: String?, sourceRef: String?, limit: Int): List<JournalEntryView>

    fun allForEntity(legalEntityId: UUID): List<JournalEntryView>

    fun lockEntity(legalEntityId: UUID)
}

interface PeriodRepository {
    fun find(legalEntityId: UUID, period: String): AccountingPeriodView?

    fun upsert(period: AccountingPeriodView)

    fun periods(legalEntityId: UUID): List<AccountingPeriodView>
}

interface FxRateRepository {
    fun rateOn(date: LocalDate, from: String, to: String): BigDecimal?

    fun upsert(date: LocalDate, from: String, to: String, rate: BigDecimal, source: String)
}

interface CostLayerRepository {
    fun open(legalEntityId: UUID, assetKey: String): List<CostLayer>

    fun insert(layer: CostLayer)

    fun reduce(layerId: UUID, quantity: BigDecimal)
}

interface GlExportRepository {
    fun insert(export: GlExportView)

    fun latest(legalEntityId: UUID, period: String): GlExportView?
}
