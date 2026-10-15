package com.pianonurmi.etrm.reporting.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.reporting.api.EmirCompleteness
import com.pianonurmi.etrm.reporting.api.EmirEventView
import com.pianonurmi.etrm.reporting.api.EmirFieldRule
import com.pianonurmi.etrm.reporting.api.ReportDefinitionView
import com.pianonurmi.etrm.reporting.api.ReportExportView
import com.pianonurmi.etrm.reporting.api.ReportFormat
import com.pianonurmi.etrm.reporting.api.ReportOutput
import com.pianonurmi.etrm.reporting.api.ReportingCommand
import com.pianonurmi.etrm.reporting.api.ReportingQuery
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Report catalogue and export log, EMIR events and completeness")
class ReportingController(private val query: ReportingQuery, private val command: ReportingCommand) {
    @GetMapping
    fun catalogue(): List<ReportDefinitionView> = query.catalogue()

    /** Produces a report; every run is logged as an export (number and content hash in response headers). */
    @GetMapping("/{code}")
    fun report(
        @PathVariable code: String,
        @RequestParam(defaultValue = "json") format: String,
        @RequestParam parameters: Map<String, String>,
    ): ResponseEntity<String> {
        val output = command.produce(code, parameters - "format", ReportFormat.valueOf(format.uppercase()))
        return respond(output)
    }

    @GetMapping("/exports")
    @AllowedRoles(Role.AUDITOR, Role.ACCOUNTANT, Role.RISK, Role.MIDDLE_OFFICE)
    fun exports(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(required = false) reportCode: String?,
    ): List<ReportExportView> = query.exports(legalEntityId, reportCode)

    /** The content of an earlier export, as it was handed out. */
    @GetMapping("/exports/{id}")
    fun export(
        @PathVariable id: UUID,
    ): ResponseEntity<String> = respond(query.export(id) ?: throw NotFoundException("ReportExport", id))

    @GetMapping("/emir/events")
    fun emirEvents(
        @RequestParam legalEntityId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<EmirEventView> = query.emirEvents(legalEntityId, from, to.plusDays(1))

    @GetMapping("/emir/completeness")
    fun emirCompleteness(
        @RequestParam legalEntityId: UUID,
    ): List<EmirCompleteness> = query.emirCompleteness(legalEntityId)

    @GetMapping("/emir/field-rules")
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK, Role.ACCOUNTANT, Role.AUDITOR)
    fun emirFieldRules(): List<EmirFieldRule> = query.emirFieldRules()

    @PostMapping("/emir/trades/{tradeId}/error")
    @AllowedRoles(Role.MIDDLE_OFFICE)
    fun reportError(
        @PathVariable tradeId: UUID,
        @RequestBody body: ErrorReport,
    ): EmirEventView = command.reportError(tradeId, body.reason)

    private fun respond(output: ReportOutput): ResponseEntity<String> {
        val e = output.export
        val csv = e.format == ReportFormat.CSV
        return ResponseEntity.ok()
            .contentType(if (csv) MediaType.parseMediaType("text/csv;charset=UTF-8") else MediaType.APPLICATION_JSON)
            .header("X-Report-Export", e.id.toString())
            .header("X-Report-Seq", e.seq.toString())
            .header("X-Content-Sha256", e.contentHash)
            .apply { if (csv) header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${e.reportCode.lowercase()}-${e.seq}.csv\"") }
            .body(output.content)
    }
}

data class ErrorReport(val reason: String)
