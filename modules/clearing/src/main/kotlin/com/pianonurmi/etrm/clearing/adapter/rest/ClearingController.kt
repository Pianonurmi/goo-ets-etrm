package com.pianonurmi.etrm.clearing.adapter.rest

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.ClearingBreakStatus
import com.pianonurmi.etrm.clearing.api.ClearingBreakView
import com.pianonurmi.etrm.clearing.api.ClearingCommand
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.ClearingStatementCommand
import com.pianonurmi.etrm.clearing.api.ClearingStatementQuery
import com.pianonurmi.etrm.clearing.api.ClearingStatementView
import com.pianonurmi.etrm.clearing.api.CreateClearingAccount
import com.pianonurmi.etrm.clearing.api.MarginRunView
import com.pianonurmi.etrm.clearing.api.StatementImportView
import com.pianonurmi.etrm.platform.errors.NotFoundException
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/** Roles and entity scope are checked in [com.pianonurmi.etrm.clearing.application.ClearingService]. */
@RestController
@RequestMapping("/api/v1/clearing")
@Tag(name = "Clearing", description = "Clearing accounts, daily variation margin of cleared EUA futures, clearing statements and their breaks")
class ClearingController(
    private val query: ClearingQuery,
    private val command: ClearingCommand,
    private val statementQuery: ClearingStatementQuery,
    private val statementCommand: ClearingStatementCommand,
) {
    @GetMapping("/accounts")
    fun accounts(
        @RequestParam(required = false) legalEntityId: UUID?,
    ): List<ClearingAccountView> = query.accounts(legalEntityId)

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @RequestBody body: CreateClearingAccount,
    ): ClearingAccountView = command.createAccount(body)

    @GetMapping("/accounts/{id}/margin")
    fun runs(
        @PathVariable id: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<MarginRunView> = query.runs(id, from, to)

    @PostMapping("/accounts/{id}/margin")
    fun computeMargin(
        @PathVariable id: UUID,
        @RequestBody body: ComputeMargin,
    ): MarginRunView = command.computeMargin(id, body.businessDate)

    @GetMapping("/margin/{runId}")
    fun run(
        @PathVariable runId: UUID,
    ): MarginRunView = query.run(runId) ?: throw NotFoundException("MarginRun", runId)

    /** Uploads the clearing member's statement file (one or more days) and reconciles each day against the margin. */
    @PostMapping("/accounts/{id}/statements", consumes = ["text/csv", "text/plain"])
    @ResponseStatus(HttpStatus.CREATED)
    fun importStatements(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "statement.csv") source: String,
        @RequestBody content: String,
    ): StatementImportView = statementCommand.importStatements(id, source, content)

    @GetMapping("/accounts/{id}/statements")
    fun statements(
        @PathVariable id: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<ClearingStatementView> = statementQuery.statements(id, from, to)

    @GetMapping("/statements/{statementId}")
    fun statement(
        @PathVariable statementId: UUID,
    ): ClearingStatementView = statementQuery.statement(statementId) ?: throw NotFoundException("ClearingStatement", statementId)

    /** Differences between statements and computed margin; accepted in the inbox (CLEARING_BREAK tasks). */
    @GetMapping("/breaks")
    fun breaks(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(required = false) status: ClearingBreakStatus?,
    ): List<ClearingBreakView> = statementQuery.breaks(legalEntityId, status)

    @GetMapping("/breaks/{breakId}")
    fun clearingBreak(
        @PathVariable breakId: UUID,
    ): ClearingBreakView = statementQuery.clearingBreak(breakId) ?: throw NotFoundException("ClearingBreak", breakId)

    data class ComputeMargin(val businessDate: LocalDate)
}
