package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.AccountType
import com.pianonurmi.etrm.ledger.api.AccountingPeriodView
import com.pianonurmi.etrm.ledger.api.EntryStatus
import com.pianonurmi.etrm.ledger.api.GlAccountView
import com.pianonurmi.etrm.ledger.api.GlExportKind
import com.pianonurmi.etrm.ledger.api.GlExportStatus
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.GlExported
import com.pianonurmi.etrm.ledger.api.JournalEntryPosted
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.JournalLineView
import com.pianonurmi.etrm.ledger.api.LedgerCommand
import com.pianonurmi.etrm.ledger.api.LedgerQuery
import com.pianonurmi.etrm.ledger.api.ManualJournal
import com.pianonurmi.etrm.ledger.api.PeriodStatus
import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.PostingSide
import com.pianonurmi.etrm.ledger.api.TrialBalanceRow
import com.pianonurmi.etrm.ledger.api.UpsertPostingRule
import com.pianonurmi.etrm.ledger.domain.EntryBuilder
import com.pianonurmi.etrm.ledger.domain.GlAccountRepository
import com.pianonurmi.etrm.ledger.domain.GlExportRenderer
import com.pianonurmi.etrm.ledger.domain.GlExportRepository
import com.pianonurmi.etrm.ledger.domain.GlInterfaceRepository
import com.pianonurmi.etrm.ledger.domain.JournalRepository
import com.pianonurmi.etrm.ledger.domain.PeriodRepository
import com.pianonurmi.etrm.ledger.domain.PostingRuleRepository
import com.pianonurmi.etrm.ledger.domain.RuleSelector
import com.pianonurmi.etrm.ledger.domain.TrialBalance
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Service
@Transactional
class LedgerService(
    private val accounts: GlAccountRepository,
    private val rules: PostingRuleRepository,
    private val journal: JournalRepository,
    private val periods: PeriodRepository,
    private val fxRates: FxRates,
    private val exports: GlExportRepository,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val glInterface: GlInterfaceRepository,
    /** Checks before a hard close (ADR 0024); none when not wired (hand-wired tests). */
    private val gates: HardCloseGates? = null,
) : LedgerCommand, LedgerQuery {
    // ----------------------------------------------------------------------------------------------------------
    // Posting
    // ----------------------------------------------------------------------------------------------------------

    override fun post(facts: PostingFacts): List<JournalEntryView> = postByRules(intoOpenPeriod(facts), manual = false)

    /**
     * Posts by rules on the facts' own date, as an adjustment: allowed in a soft-closed period, refused in a closed
     * one. The caller checks the role (period end runs as ACCOUNTANT).
     */
    fun postAdjustment(facts: PostingFacts): List<JournalEntryView> = postByRules(facts, manual = true)

    /** Serialises posting for an entity until the transaction ends. */
    fun lockEntity(legalEntityId: UUID) = journal.lockEntity(legalEntityId)

    private fun postByRules(facts: PostingFacts, manual: Boolean): List<JournalEntryView> {
        val applicable = RuleSelector.select(rules.all(), facts)
        if (applicable.isEmpty()) return emptyList()
        val chart = ensureChartOfAccounts(facts.legalEntityId).associateBy { it.code }
        val entity = referenceData.requireLegalEntity(facts.legalEntityId)
        val base = entity.baseCurrency.currencyCode
        val rate = fxRateFor(facts.postingDate, facts.currency, base)
        journal.lockEntity(facts.legalEntityId)
        return applicable.mapNotNull { rule ->
            facts.eventId?.let { journal.findByEvent(it, rule.ruleCode) }?.let { return@mapNotNull it }
            val drafts = EntryBuilder.draft(rule, facts) ?: return@mapNotNull null
            val lines = drafts.mapIndexed { index, d ->
                val account = chart[d.accountCode] ?: throw ValidationException("Rule ${rule.ruleCode} references unknown account ${d.accountCode}")
                line(index + 1, account, d.side, d.amount, facts.currency, rate, facts.dimensions + mapOf("glMapping" to account.glMapping), d.memo)
            }
            write(
                facts.legalEntityId,
                facts.postingDate,
                facts.eventType,
                rule.ruleCode,
                facts.sourceType,
                facts.sourceRef,
                facts.eventId,
                facts.description,
                facts.currency,
                lines,
                manual = manual,
            )
        }
    }

    override fun postManual(manual: ManualJournal): JournalEntryView {
        actorProvider.current().requireRole(Role.ACCOUNTANT, Role.BACK_OFFICE)
        val chart = ensureChartOfAccounts(manual.legalEntityId).associateBy { it.code }
        val base = referenceData.requireLegalEntity(manual.legalEntityId).baseCurrency.currencyCode
        val rate = fxRateFor(manual.postingDate, manual.currency, base)
        val lines = manual.lines.mapIndexed { index, l ->
            val account = chart[l.accountCode] ?: throw NotFoundException("GlAccount", l.accountCode)
            val side = if (l.debit.signum() > 0) PostingSide.DEBIT else PostingSide.CREDIT
            line(
                index + 1,
                account,
                side,
                if (side == PostingSide.DEBIT) l.debit else l.credit,
                manual.currency,
                rate,
                l.dimensions + mapOf("glMapping" to account.glMapping),
                l.memo,
            )
        }
        journal.lockEntity(manual.legalEntityId)
        return write(
            manual.legalEntityId,
            manual.postingDate,
            "ManualJournal",
            null,
            "MANUAL",
            manual.sourceRef,
            null,
            manual.description,
            manual.currency,
            lines,
            manual = true,
        )
    }

    override fun reverse(entryId: UUID, reason: String): JournalEntryView {
        actorProvider.current().requireRole(Role.ACCOUNTANT, Role.BACK_OFFICE)
        val original = journal.find(entryId) ?: throw NotFoundException("JournalEntry", entryId)
        if (original.status != EntryStatus.POSTED) throw ValidationException("Entry ${original.entryNumber} is ${original.status} and cannot be reversed")
        journal.lockEntity(original.legalEntityId)
        val today = LocalDate.now(clock)
        val postingDate = if (periods.find(
                original.legalEntityId,
                EntryBuilder.periodOf(original.postingDate),
            )?.status == PeriodStatus.CLOSED
        ) {
            today
        } else {
            original.postingDate
        }
        val lines = original.lines.map { l ->
            l.copy(debit = l.credit, credit = l.debit, baseDebit = l.baseCredit, baseCredit = l.baseDebit, memo = "Reversal: $reason")
        }
        val reversal = write(
            original.legalEntityId,
            postingDate,
            "Reversal",
            original.ruleCode,
            original.sourceType,
            original.sourceRef,
            null,
            "Reversal of ${original.entryNumber}: $reason",
            original.currency,
            lines,
            manual = true,
            status = EntryStatus.REVERSAL,
            reversalOf = original.id,
        )
        journal.markReversed(original.id, reversal.id)
        return reversal
    }

    private fun write(
        legalEntityId: UUID,
        postingDate: LocalDate,
        eventType: String,
        ruleCode: String?,
        sourceType: String?,
        sourceRef: String?,
        eventId: UUID?,
        description: String,
        currency: String,
        lines: List<JournalLineView>,
        manual: Boolean,
        status: EntryStatus = EntryStatus.POSTED,
        reversalOf: UUID? = null,
    ): JournalEntryView {
        EntryBuilder.requireBalanced(lines)
        val period = EntryBuilder.periodOf(postingDate)
        EntryBuilder.requireOpen(periods.find(legalEntityId, period), manual)
        val prev = journal.lastHash(legalEntityId)
        val number = journal.nextEntryNumber()
        val payload = chainPayload(number, postingDate, eventType, lines)
        val entry = JournalEntryView(
            id = Ids.newId(),
            entryNumber = number,
            legalEntityId = legalEntityId,
            period = period,
            postingDate = postingDate,
            eventType = eventType,
            ruleCode = ruleCode,
            sourceType = sourceType,
            sourceRef = sourceRef,
            eventId = eventId,
            description = description,
            currency = currency,
            status = status,
            reversalOf = reversalOf,
            reversedBy = null,
            createdBy = actorProvider.current().userId,
            createdAt = clock.instant(),
            prevHash = prev,
            hash = HashChain.next(prev, payload),
            lines = lines,
        )
        val chart = accounts.accounts(legalEntityId).associate { it.code to it.id }
        journal.insert(entry, chart)
        events.publishEvent(JournalEntryPosted(entry))
        return entry
    }

    private fun line(
        no: Int,
        account: GlAccountView,
        side: PostingSide,
        amount: BigDecimal,
        currency: String,
        rate: BigDecimal,
        dims: Map<String, String?>,
        memo: String?,
    ) = JournalLineView(
        lineNo = no,
        accountCode = account.code,
        accountName = account.name,
        debit = if (side == PostingSide.DEBIT) amount else BigDecimal.ZERO,
        credit = if (side == PostingSide.CREDIT) amount else BigDecimal.ZERO,
        currency = currency,
        fxRate = rate,
        baseDebit = if (side == PostingSide.DEBIT) (amount * rate).setScale(2, RoundingMode.HALF_EVEN) else BigDecimal.ZERO,
        baseCredit = if (side == PostingSide.CREDIT) (amount * rate).setScale(2, RoundingMode.HALF_EVEN) else BigDecimal.ZERO,
        dimensions = dims,
        memo = memo,
    )

    /** Hash input normalised so that a round trip through NUMERIC columns reproduces it exactly. */
    private fun chainPayload(number: Long, postingDate: LocalDate, eventType: String, lines: List<JournalLineView>): String = Json.canonical(
        mapOf(
            "n" to number,
            "d" to postingDate.toString(),
            "e" to eventType,
            "lines" to lines.map { listOf(it.accountCode, money(it.debit), money(it.credit), it.currency) },
        ),
    )

    private fun money(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    /**
     * Automatic postings dated in a closed (or soft-closed) period go to the first day of the next open period, with
     * the original date kept as a dimension and in the description; manual adjustments are the way into closed periods.
     */
    private fun intoOpenPeriod(facts: PostingFacts): PostingFacts {
        var month = YearMonth.from(facts.postingDate)
        repeat(MAX_PERIODS_AHEAD) {
            val status = periods.find(facts.legalEntityId, month.toString())?.status ?: PeriodStatus.OPEN
            if (status == PeriodStatus.OPEN) {
                if (month == YearMonth.from(facts.postingDate)) return facts
                return facts.copy(
                    postingDate = month.atDay(1),
                    description = "${facts.description} (dated ${facts.postingDate}; period ${YearMonth.from(facts.postingDate)} is closed)",
                    dimensions = facts.dimensions + mapOf("originalDate" to facts.postingDate.toString()),
                )
            }
            month = month.plusMonths(1)
        }
        throw ValidationException("No open period within $MAX_PERIODS_AHEAD months after ${facts.postingDate}")
    }

    /** The rate converting [from] into [to] on [date] (latest on or before). */
    fun rateOn(date: LocalDate, from: String, to: String): BigDecimal = fxRateFor(date, from, to)

    private fun fxRateFor(date: LocalDate, from: String, to: String): BigDecimal = fxRates.require(date, from, to).rate

    // ----------------------------------------------------------------------------------------------------------
    // Periods, export, master data
    // ----------------------------------------------------------------------------------------------------------

    /** OPEN → SOFT_CLOSED → CLOSED; a hard-closed period is final. */
    override fun closePeriod(legalEntityId: UUID, period: YearMonth, hard: Boolean): AccountingPeriodView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        actor.requireEntity(legalEntityId)
        val current = periods.find(legalEntityId, period.toString())?.status ?: PeriodStatus.OPEN
        val target = if (hard) PeriodStatus.CLOSED else PeriodStatus.SOFT_CLOSED
        if (current == PeriodStatus.CLOSED) throw ValidationException("Period $period is already hard-closed")
        if (current == target) throw ValidationException("Period $period is already ${target.name.lowercase().replace('_', '-')}")
        if (hard) {
            gates?.blockers(legalEntityId, period)?.takeIf {
                it.isNotEmpty()
            }?.let { throw ValidationException("Period $period cannot be hard-closed: ${it.joinToString("; ")}") }
        }
        val view = AccountingPeriodView(legalEntityId, period.toString(), target, actor.userId, clock.instant())
        periods.upsert(view)
        return view
    }

    override fun reopenPeriod(legalEntityId: UUID, period: YearMonth): AccountingPeriodView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        actor.requireEntity(legalEntityId)
        when (periods.find(legalEntityId, period.toString())?.status ?: PeriodStatus.OPEN) {
            PeriodStatus.CLOSED -> throw ValidationException("Period $period is hard-closed and cannot be reopened; post adjustments in an open period")
            PeriodStatus.OPEN -> throw ValidationException("Period $period is open")
            PeriodStatus.SOFT_CLOSED -> Unit
        }
        val view = AccountingPeriodView(legalEntityId, period.toString(), PeriodStatus.OPEN, null, null)
        periods.upsert(view)
        return view
    }

    /**
     * Exports the entries of a closed (soft or hard) period that no earlier export carried (ADR 0024): the first export
     * of a period is PERIOD, later ones (adjustments posted since, or entries of a rejected export) DELTA. The journal's
     * hash chain must verify and every exported account must have a GL mapping; memo accounts stay in the sub-ledger.
     */
    override fun exportGl(legalEntityId: UUID, period: YearMonth, format: String): GlExportView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        actor.requireEntity(legalEntityId)
        val status = periods.find(legalEntityId, period.toString())?.status ?: PeriodStatus.OPEN
        if (status == PeriodStatus.OPEN) throw ValidationException("Period $period is open; close it (soft or hard) before exporting")
        if (verifyJournal(legalEntityId).isNotEmpty()) throw ValidationException("The journal hash chain does not verify; the export is refused")
        val layout = glInterface.layout(format.uppercase()) ?: throw NotFoundException("GlExportFormat", format)
        journal.lockEntity(legalEntityId)
        val chart = accounts.accounts(legalEntityId)
        val memo = chart.filter { it.type == AccountType.MEMO }.map { it.code }.toSet()
        val already = glInterface.exportedEntryIds(legalEntityId, period.toString())
        val entries = journal.entries(legalEntityId, period.toString(), null, Int.MAX_VALUE).filter { it.id !in already }
        if (entries.isEmpty()) throw ValidationException("Every entry of $period has been exported")
        val unmapped = entries.flatMap { it.lines }.map { it.accountCode }.filter { it !in memo }.toSet()
            .filter { code -> chart.firstOrNull { it.code == code }?.glMapping.isNullOrBlank() }
        if (unmapped.isNotEmpty()) throw ValidationException("Accounts without a GL mapping: ${unmapped.sorted().joinToString()}")
        val entity = referenceData.requireLegalEntity(legalEntityId)
        val content = GlExportRenderer.render(entries, memo, layout, entity.code, entity.baseCurrency.currencyCode)
        val export = GlExportView(
            Ids.newId(),
            legalEntityId,
            exports.nextSeq(legalEntityId),
            period.toString(),
            clock.instant(),
            actor.userId,
            entries.size,
            HashChain.next(null, content),
            content,
            kind = if (glInterface.exports(legalEntityId, period.toString()).any {
                    it.status != GlExportStatus.REJECTED
                }
            ) {
                GlExportKind.DELTA
            } else {
                GlExportKind.PERIOD
            },
            format = format.uppercase(),
        )
        exports.insert(export)
        glInterface.insertEntries(export.id, entries.map { it.id })
        events.publishEvent(GlExported(export.id, legalEntityId, export.exportSeq, export.period, export.entryCount, export.contentHash))
        return export
    }

    /** Creates the default chart for a new entity, and adds default accounts introduced later to existing charts. */
    override fun ensureChartOfAccounts(legalEntityId: UUID): List<GlAccountView> {
        val existing = accounts.accounts(legalEntityId)
        val codes = existing.map { it.code }.toSet()
        val missing = DefaultLedgerData.chartOfAccounts().filter { it.code !in codes }
        if (missing.isEmpty()) return existing
        referenceData.requireLegalEntity(legalEntityId)
        missing.forEach { template ->
            accounts.insert(GlAccountView(Ids.newId(), legalEntityId, template.code, template.name, template.type, template.glMapping, true))
        }
        return accounts.accounts(legalEntityId)
    }

    /** Applies an approved rule change (from the `POSTING_RULE_CHANGE` task); returns the rule's new version. */
    fun applyRule(command: UpsertPostingRule): PostingRuleView {
        if (command.lines.isEmpty()) throw ValidationException("A posting rule needs lines")
        val view = PostingRuleView(
            Ids.newId(),
            command.legalEntityId,
            command.ruleCode.uppercase(),
            command.eventType,
            command.conditions,
            command.lines,
            command.description,
            command.priority,
            command.effectiveFrom,
            command.active,
            1,
        )
        rules.upsert(view)
        return rules.all().first { it.ruleCode == view.ruleCode && it.legalEntityId == view.legalEntityId }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun entry(id: UUID): JournalEntryView? = journal.find(id)

    @Transactional(readOnly = true)
    override fun entries(legalEntityId: UUID, period: YearMonth?, sourceRef: String?, limit: Int): List<JournalEntryView> {
        actorProvider.current().requireEntity(legalEntityId)
        return journal.entries(legalEntityId, period?.toString(), sourceRef, limit)
    }

    @Transactional(readOnly = true)
    override fun trialBalance(legalEntityId: UUID, period: YearMonth): List<TrialBalanceRow> {
        actorProvider.current().requireEntity(legalEntityId)
        return TrialBalance.compute(journal.entries(legalEntityId, period.toString(), null, Int.MAX_VALUE), accounts.accounts(legalEntityId))
    }

    @Transactional(readOnly = true)
    override fun accounts(legalEntityId: UUID): List<GlAccountView> {
        actorProvider.current().requireEntity(legalEntityId)
        return accounts.accounts(legalEntityId)
    }

    @Transactional(readOnly = true)
    override fun rules(
        legalEntityId: UUID?,
    ): List<PostingRuleView> = rules.all().filter { legalEntityId == null || it.legalEntityId == null || it.legalEntityId == legalEntityId }

    @Transactional(readOnly = true)
    override fun periods(legalEntityId: UUID): List<AccountingPeriodView> {
        actorProvider.current().requireEntity(legalEntityId)
        return periods.periods(legalEntityId)
    }

    @Transactional(readOnly = true)
    override fun fxRate(date: LocalDate, from: String, to: String): BigDecimal? = fxRates.rate(date, from, to)?.rate

    @Transactional(readOnly = true)
    override fun verifyJournal(legalEntityId: UUID): List<UUID> {
        var prev: String? = null
        val broken = mutableListOf<UUID>()
        journal.allForEntity(legalEntityId).forEach { e ->
            val payload = chainPayload(e.entryNumber, e.postingDate, e.eventType, e.lines)
            if (e.prevHash != prev || e.hash != HashChain.next(prev, payload)) broken += e.id
            prev = e.hash
        }
        return broken
    }

    private companion object {
        const val MAX_PERIODS_AHEAD = 24
    }
}

/** Default chart of accounts and posting rules shipped as resources. */
object DefaultLedgerData {
    data class AccountTemplate(val code: String, val name: String, val type: AccountType, val glMapping: String?)

    private val chart: List<AccountTemplate> by lazy {
        Json.mapper.readValue(resource("ledger/default-chart-of-accounts.json"), object : TypeReference<List<AccountTemplate>>() {})
    }

    fun chartOfAccounts(): List<AccountTemplate> = chart

    /** Default rules with the version of the set; raise the version whenever a default rule changes. */
    data class RuleSet(val version: Int, val rules: List<UpsertPostingRule>)

    fun postingRules(): RuleSet = Json.mapper.readValue(resource("ledger/default-posting-rules.json"), RuleSet::class.java)

    private fun resource(path: String): String = DefaultLedgerData::class.java.classLoader.getResourceAsStream(path)!!.use { it.readBytes().decodeToString() }
}
