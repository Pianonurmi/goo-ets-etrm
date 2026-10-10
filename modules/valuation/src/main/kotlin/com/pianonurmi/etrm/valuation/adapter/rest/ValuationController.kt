package com.pianonurmi.etrm.valuation.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.valuation.api.PnlExplainView
import com.pianonurmi.etrm.valuation.api.RunKind
import com.pianonurmi.etrm.valuation.api.ValuationCommand
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunView
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

@RestController
@RequestMapping("/api/v1/valuation")
@Tag(name = "Valuation", description = "Mark-to-market runs, sign-off and P&L explain")
class ValuationController(private val query: ValuationQuery, private val command: ValuationCommand) {
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    fun run(
        @RequestBody body: RunRequest,
    ): ValuationRunView = command.run(body.legalEntityId, body.valuationDate, body.kind, body.note)

    @PostMapping("/runs/{id}/submit")
    fun submit(
        @PathVariable id: UUID,
    ): ValuationRunView = command.submit(id)

    @GetMapping("/runs")
    fun runs(
        @RequestParam legalEntityId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<ValuationRunView> = query.runs(legalEntityId, from, to)

    @GetMapping("/runs/{id}")
    fun runDetail(
        @PathVariable id: UUID,
    ): ValuationRunView = query.run(id) ?: throw NotFoundException("ValuationRun", id)

    @GetMapping("/runs/{id}/results")
    fun results(
        @PathVariable id: UUID,
        @RequestParam(required = false) bookId: UUID?,
    ): List<ValuationResultView> = query.results(id, bookId)

    @GetMapping("/official")
    fun official(
        @RequestParam legalEntityId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): ValuationRunView = query.official(legalEntityId, date, orEarlier = true) ?: throw NotFoundException("Official valuation", "$legalEntityId on $date")

    @GetMapping("/pnl-explain")
    fun explain(
        @RequestParam legalEntityId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): PnlExplainView = query.explain(legalEntityId, from, to) ?: throw NotFoundException("Official valuations", "$legalEntityId $from..$to")

    @GetMapping("/trades/{id}/mtm")
    fun tradeMtm(
        @PathVariable id: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): List<ValuationResultView> = query.tradeMtm(id, date)

    data class RunRequest(val legalEntityId: UUID, val valuationDate: LocalDate, val kind: RunKind = RunKind.ADHOC, val note: String? = null)
}
