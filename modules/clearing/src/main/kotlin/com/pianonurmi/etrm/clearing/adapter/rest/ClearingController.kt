package com.pianonurmi.etrm.clearing.adapter.rest

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.ClearingCommand
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.CreateClearingAccount
import com.pianonurmi.etrm.clearing.api.MarginRunView
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
@Tag(name = "Clearing", description = "Clearing accounts and daily variation margin of cleared EUA futures")
class ClearingController(private val query: ClearingQuery, private val command: ClearingCommand) {
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

    data class ComputeMargin(val businessDate: LocalDate)
}
