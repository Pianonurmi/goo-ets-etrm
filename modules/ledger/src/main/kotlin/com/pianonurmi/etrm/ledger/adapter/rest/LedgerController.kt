package com.pianonurmi.etrm.ledger.adapter.rest

import com.pianonurmi.etrm.ledger.api.AccountingPeriodView
import com.pianonurmi.etrm.ledger.api.GlAccountView
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.LedgerCommand
import com.pianonurmi.etrm.ledger.api.LedgerQuery
import com.pianonurmi.etrm.ledger.api.ManualJournal
import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.TrialBalanceRow
import com.pianonurmi.etrm.ledger.api.UpsertPostingRule
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Ledger", description = "Sub-ledger: journal, trial balance, posting rules, periods, FX rates, GL export")
class LedgerController(private val command: LedgerCommand, private val query: LedgerQuery) {
    @GetMapping("/entries")
    fun entries(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) period: YearMonth?,
        @RequestParam(required = false) sourceRef: String?,
        @RequestParam(defaultValue = "500") limit: Int,
    ): List<JournalEntryView> = query.entries(legalEntityId, period, sourceRef, limit)

    @GetMapping("/entries/{id}")
    fun entry(
        @PathVariable id: UUID,
    ): JournalEntryView? = query.entry(id)

    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    fun postManual(
        @Valid @RequestBody body: ManualJournal,
    ): JournalEntryView = command.postManual(body)

    @PostMapping("/entries/{id}/reverse")
    fun reverse(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): JournalEntryView = command.reverse(id, body.reason)

    @GetMapping("/trial-balance")
    fun trialBalance(
        @RequestParam legalEntityId: UUID,
        @RequestParam period: YearMonth,
    ): List<TrialBalanceRow> = query.trialBalance(legalEntityId, period)

    @GetMapping("/accounts")
    fun accounts(
        @RequestParam legalEntityId: UUID,
    ): List<GlAccountView> = query.accounts(legalEntityId)

    @GetMapping("/rules")
    fun rules(
        @RequestParam(required = false) legalEntityId: UUID?,
    ): List<PostingRuleView> = query.rules(legalEntityId)

    @PutMapping("/rules")
    fun upsertRule(
        @Valid @RequestBody body: UpsertPostingRule,
    ): PostingRuleView = command.upsertRule(body)

    @GetMapping("/periods")
    fun periods(
        @RequestParam legalEntityId: UUID,
    ): List<AccountingPeriodView> = query.periods(legalEntityId)

    @PostMapping("/periods/{period}/close")
    fun closePeriod(
        @RequestParam legalEntityId: UUID,
        @PathVariable period: YearMonth,
        @RequestParam(defaultValue = "false") hard: Boolean,
    ): AccountingPeriodView =
        command.closePeriod(legalEntityId, period, hard)

    @PostMapping("/periods/{period}/reopen")
    fun reopenPeriod(
        @RequestParam legalEntityId: UUID,
        @PathVariable period: YearMonth,
    ): AccountingPeriodView = command.reopenPeriod(legalEntityId, period)

    @PostMapping("/exports/{period}")
    @ResponseStatus(HttpStatus.CREATED)
    fun export(
        @RequestParam legalEntityId: UUID,
        @PathVariable period: YearMonth,
    ): GlExportView = command.exportGl(legalEntityId, period)

    @PostMapping("/exports/{period}/csv", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun exportCsv(
        @RequestParam legalEntityId: UUID,
        @PathVariable period: YearMonth,
    ): String = command.exportGl(legalEntityId, period).content

    @GetMapping("/journal/verify")
    fun verify(
        @RequestParam legalEntityId: UUID,
    ): Map<String, Any> = query.verifyJournal(legalEntityId).let { mapOf("brokenEntries" to it, "ok" to it.isEmpty()) }
}

data class ReasonRequest(val reason: String)
