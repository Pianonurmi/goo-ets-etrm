package com.pianonurmi.etrm.registry.adapter.rest

import com.pianonurmi.etrm.inventory.api.StatementView
import com.pianonurmi.etrm.registry.api.AssetKind
import com.pianonurmi.etrm.registry.api.InstructionPage
import com.pianonurmi.etrm.registry.api.InstructionSearch
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.RegistryStatementCommand
import com.pianonurmi.etrm.registry.api.StatementFormatCommand
import com.pianonurmi.etrm.registry.api.StatementFormatQuery
import com.pianonurmi.etrm.registry.api.StatementFormatView
import com.pianonurmi.etrm.registry.api.SubmitAllowanceTransfer
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitSurrender
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.registry.api.TransactionImportReport
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.api.TransferRouteCommand
import com.pianonurmi.etrm.registry.api.TransferRouteQuery
import com.pianonurmi.etrm.registry.api.UpsertStatementFormat
import com.pianonurmi.etrm.registry.api.UpsertTransferRoute
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/registry")
@Tag(name = "Registry", description = "Transfer routes, registry instructions and statement import")
class RegistryController(
    private val instructionCommand: RegistryInstructionCommand,
    private val instructionQuery: RegistryInstructionQuery,
    private val routeQuery: TransferRouteQuery,
    private val routeCommand: TransferRouteCommand,
    private val statements: RegistryStatementCommand,
    private val formatQuery: StatementFormatQuery,
    private val formatCommand: StatementFormatCommand,
    private val access: RegistryAccess,
) {
    @GetMapping("/routes")
    fun routes(
        @RequestParam from: String,
    ): Map<String, TransferRoute> = access.reader().let { routeQuery.routesFrom(from) }

    @PutMapping("/routes")
    fun upsertRoute(
        @RequestBody body: UpsertTransferRoute,
    ): TransferRoute = access.maintainer().let { routeCommand.upsert(body) }

    @GetMapping("/instructions")
    fun open(
        @RequestParam(required = false) registryCode: String?,
    ): List<RegistryInstructionView> {
        val actor = access.reader()
        return instructionQuery.open(registryCode).filter { actor.canAccess(it.holderEntityId) }
    }

    /** Instructions of any status, newest first, with a cursor for the next page. */
    @GetMapping("/instructions/search")
    fun search(
        @RequestParam(required = false) status: Set<InstructionStatus>?,
        @RequestParam(required = false) registryCode: String?,
        @RequestParam(required = false) holderEntityId: UUID?,
        @RequestParam(required = false) assetKind: AssetKind?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): InstructionPage = access.reader().let {
        instructionQuery.search(InstructionSearch(status, registryCode, holderEntityId, assetKind, from, to, limit, cursor))
    }

    @GetMapping("/instructions/{id}")
    fun instruction(
        @PathVariable id: UUID,
    ): RegistryInstructionView = instructionQuery.requireInstruction(id).also { access.reader(it.holderEntityId) }

    @PostMapping("/instructions/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    fun transfer(
        @RequestBody body: SubmitTransfer,
    ): RegistryInstructionView = access.submitterForLot(body.lotId).let { instructionCommand.submitTransfer(body) }

    @PostMapping("/instructions/cancellations")
    @ResponseStatus(HttpStatus.CREATED)
    fun cancellation(
        @RequestBody body: SubmitCancellation,
    ): RegistryInstructionView = access.submitterForLot(body.lotId).let { instructionCommand.submitCancellation(body) }

    /** Transfer of allowances from one of our Union Registry accounts; executed manually after a second approval. */
    @PostMapping("/instructions/allowance-transfers")
    @ResponseStatus(HttpStatus.CREATED)
    fun allowanceTransfer(
        @RequestBody body: SubmitAllowanceTransfer,
    ): RegistryInstructionView = access.submitterForAccount(body.fromAccountId).let { instructionCommand.submitAllowanceTransfer(body) }

    @PostMapping("/instructions/surrenders")
    @ResponseStatus(HttpStatus.CREATED)
    fun surrender(
        @RequestBody body: SubmitSurrender,
    ): RegistryInstructionView = access.submitterForAccount(body.fromAccountId).let { instructionCommand.submitSurrender(body) }

    @PostMapping("/instructions/{id}/confirm")
    fun confirm(
        @PathVariable id: UUID,
        @RequestBody body: ConfirmRequest,
    ): RegistryInstructionView = operate(id).let { instructionCommand.confirm(id, body.externalRef) }

    @PostMapping("/instructions/{id}/fail")
    fun fail(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): RegistryInstructionView = operate(id).let { instructionCommand.fail(id, body.reason) }

    @PostMapping("/instructions/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): RegistryInstructionView = operate(id).let { instructionCommand.cancel(id, body.reason) }

    @PostMapping("/instructions/{id}/retry")
    fun retry(
        @PathVariable id: UUID,
    ): RegistryInstructionView = operate(id).let { instructionCommand.retry(id) }

    /** Uploads a holdings statement, parses it with the account registry's format profile and reconciles it. */
    @PostMapping("/statements/csv", consumes = ["text/csv", "text/plain"])
    @ResponseStatus(HttpStatus.CREATED)
    fun importHoldings(
        @RequestParam registryAccountId: UUID,
        @RequestParam statementDate: LocalDate,
        @RequestParam(defaultValue = "upload.csv") source: String,
        @RequestParam(required = false) profile: String?,
        @RequestBody content: String,
    ): StatementView = access.importer(registryAccountId).let { statements.importHoldings(registryAccountId, statementDate, source, content, profile) }

    /** Uploads a registry transaction export; matching open instructions are confirmed or failed. */
    @PostMapping("/statements/transactions", consumes = ["text/csv", "text/plain"])
    fun importTransactions(
        @RequestParam registryCode: String,
        @RequestParam(defaultValue = "transactions.csv") source: String,
        @RequestParam(required = false) profile: String?,
        @RequestBody content: String,
    ): TransactionImportReport = access.registryWideImporter().let { statements.importTransactions(registryCode, source, content, profile) }

    @GetMapping("/statement-formats")
    fun formats(
        @RequestParam(required = false) registryCode: String?,
    ): List<StatementFormatView> = access.reader().let { formatQuery.formats(registryCode) }

    @PutMapping("/statement-formats")
    fun upsertFormat(
        @RequestBody body: UpsertStatementFormat,
    ): StatementFormatView = access.maintainer().let { formatCommand.upsert(body) }

    private fun operate(id: UUID) = access.operator(instructionQuery.requireInstruction(id).holderEntityId)
}

data class ConfirmRequest(val externalRef: String? = null)

data class ReasonRequest(val reason: String)
