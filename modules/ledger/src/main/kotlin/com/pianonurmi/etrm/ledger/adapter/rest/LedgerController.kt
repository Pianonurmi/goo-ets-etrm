package com.pianonurmi.etrm.ledger.adapter.rest

import com.pianonurmi.etrm.ledger.api.AccountingPeriodView
import com.pianonurmi.etrm.ledger.api.GlAccountView
import com.pianonurmi.etrm.ledger.api.GlBalanceLine
import com.pianonurmi.etrm.ledger.api.GlExportFormatView
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.GlInterfaceCommand
import com.pianonurmi.etrm.ledger.api.GlInterfaceQuery
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.LedgerCommand
import com.pianonurmi.etrm.ledger.api.LedgerQuery
import com.pianonurmi.etrm.ledger.api.ManualJournal
import com.pianonurmi.etrm.ledger.api.PostingRuleChangeView
import com.pianonurmi.etrm.ledger.api.PostingRuleVersionView
import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.ReconciliationRow
import com.pianonurmi.etrm.ledger.api.RuleChangeStatus
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
class LedgerController(
    private val command: LedgerCommand,
    private val query: LedgerQuery,
    private val gl: GlInterfaceCommand,
    private val glQuery: GlInterfaceQuery,
) {
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

    /** Proposes a rule change; it applies when another accountant approves the `POSTING_RULE_CHANGE` task. */
    @PutMapping("/rules")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upsertRule(
        @Valid @RequestBody body: UpsertPostingRule,
    ): PostingRuleChangeView = gl.requestRuleChange(body)

    @GetMapping("/rules/changes")
    fun ruleChanges(
        @RequestParam(required = false) status: RuleChangeStatus?,
    ): List<PostingRuleChangeView> = glQuery.ruleChanges(status)

    @GetMapping("/rules/{ruleCode}/history")
    fun ruleHistory(
        @PathVariable ruleCode: String,
    ): List<PostingRuleVersionView> = glQuery.ruleHistory(ruleCode)

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
        @RequestParam(defaultValue = "GENERIC_CSV") format: String,
    ): GlExportView = command.exportGl(legalEntityId, period, format)

    @PostMapping("/exports/{period}/csv", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun exportCsv(
        @RequestParam legalEntityId: UUID,
        @PathVariable period: YearMonth,
        @RequestParam(defaultValue = "GENERIC_CSV") format: String,
    ): String = command.exportGl(legalEntityId, period, format).content

    @GetMapping("/exports")
    fun exports(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) period: YearMonth?,
    ): List<GlExportView> = glQuery.exports(legalEntityId, period).map { it.copy(content = "") }

    @GetMapping("/export-formats")
    fun exportFormats(): List<GlExportFormatView> = glQuery.formats()

    @PostMapping("/exports/{id}/delivered")
    fun delivered(
        @PathVariable id: UUID,
    ): GlExportView = gl.markDelivered(id).copy(content = "")

    @PostMapping("/exports/{id}/acknowledge")
    fun acknowledge(
        @PathVariable id: UUID,
        @RequestBody body: AcknowledgeRequest,
    ): GlExportView = gl.acknowledge(id, body.reference).copy(content = "")

    @PostMapping("/exports/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): GlExportView = gl.reject(id, body.reason).copy(content = "")

    @PutMapping("/gl-balances/{period}")
    fun importBalances(
        @RequestParam legalEntityId: UUID,
        @PathVariable period: YearMonth,
        @RequestBody body: List<GlBalanceLine>,
    ): List<ReconciliationRow> = gl.importBalances(legalEntityId, period, body)

    @GetMapping("/reconciliation")
    fun reconciliation(
        @RequestParam legalEntityId: UUID,
        @RequestParam period: YearMonth,
    ): List<ReconciliationRow> = glQuery.reconciliation(legalEntityId, period)

    @GetMapping("/periods/{period}/hard-close-blockers")
    fun hardCloseBlockers(
        @RequestParam legalEntityId: UUID,
        @PathVariable period: YearMonth,
    ): List<String> = glQuery.hardCloseBlockers(legalEntityId, period)

    @GetMapping("/journal/verify")
    fun verify(
        @RequestParam legalEntityId: UUID,
    ): Map<String, Any> = query.verifyJournal(legalEntityId).let { mapOf("brokenEntries" to it, "ok" to it.isEmpty()) }
}

data class ReasonRequest(val reason: String)

data class AcknowledgeRequest(val reference: String)
