package com.pianonurmi.etrm.etscompliance.adapter.rest

import com.pianonurmi.etrm.etscompliance.api.DeadlineView
import com.pianonurmi.etrm.etscompliance.api.EmissionRecordView
import com.pianonurmi.etrm.etscompliance.api.EntityCompliance
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceCommand
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceQuery
import com.pianonurmi.etrm.etscompliance.api.FreeAllocationView
import com.pianonurmi.etrm.etscompliance.api.InstallationCompliance
import com.pianonurmi.etrm.etscompliance.api.InstallationView
import com.pianonurmi.etrm.etscompliance.api.RecordEmissions
import com.pianonurmi.etrm.etscompliance.api.RecordVerifiedEmissions
import com.pianonurmi.etrm.etscompliance.api.RegisterInstallation
import com.pianonurmi.etrm.etscompliance.api.RequestSurrender
import com.pianonurmi.etrm.etscompliance.api.SetFreeAllocation
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestView
import com.pianonurmi.etrm.platform.errors.NotFoundException
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
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
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/ets")
@Tag(name = "ETS compliance", description = "Installations, emissions, free allocation, surrenders and deadlines")
class EtsComplianceController(private val query: EtsComplianceQuery, private val command: EtsComplianceCommand) {
    @GetMapping("/installations")
    fun installations(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<InstallationView> = query.installations(legalEntityId, activeOnly)

    @PostMapping("/installations")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @RequestBody body: RegisterInstallation,
    ): InstallationView = command.register(body)

    @GetMapping("/installations/{id}")
    fun installation(
        @PathVariable id: UUID,
    ): InstallationView = query.installation(id) ?: throw NotFoundException("Installation", id)

    @GetMapping("/installations/{id}/emissions")
    fun emissions(
        @PathVariable id: UUID,
        @RequestParam(required = false) year: Int?,
    ): List<EmissionRecordView> = query.emissions(id, year)

    @PostMapping("/installations/{id}/emissions")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordEmissions(
        @PathVariable id: UUID,
        @RequestBody body: RecordEmissions,
    ): EmissionRecordView = command.recordEmissions(id, body)

    @PostMapping("/installations/{id}/verified-emissions")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordVerifiedEmissions(
        @PathVariable id: UUID,
        @RequestBody body: RecordVerifiedEmissions,
    ): EmissionRecordView = command.recordVerifiedEmissions(id, body)

    @GetMapping("/installations/{id}/free-allocation/{year}")
    fun freeAllocation(
        @PathVariable id: UUID,
        @PathVariable year: Int,
    ): FreeAllocationView = query.freeAllocation(id, year) ?: throw NotFoundException("FreeAllocation", "$id/$year")

    @PutMapping("/installations/{id}/free-allocation/{year}")
    fun setFreeAllocation(
        @PathVariable id: UUID,
        @PathVariable year: Int,
        @RequestBody body: SetFreeAllocation,
    ): FreeAllocationView = command.setFreeAllocation(id, year, body)

    @GetMapping("/installations/{id}/compliance/{year}")
    fun installationCompliance(
        @PathVariable id: UUID,
        @PathVariable year: Int,
    ): InstallationCompliance = query.compliance(id, year)

    @GetMapping("/installations/{id}/surrenders")
    fun surrenders(
        @PathVariable id: UUID,
    ): List<SurrenderRequestView> = query.surrenderRequests(id)

    @PostMapping("/installations/{id}/surrenders")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestSurrender(
        @PathVariable id: UUID,
        @RequestBody body: RequestSurrender,
    ): SurrenderRequestView = command.requestSurrender(id, body)

    @GetMapping("/compliance")
    fun compliance(
        @RequestParam legalEntityId: UUID,
        @RequestParam year: Int,
    ): EntityCompliance = query.entityCompliance(legalEntityId, year)

    @GetMapping("/deadlines")
    fun deadlines(
        @RequestParam legalEntityId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): List<DeadlineView> = query.deadlines(legalEntityId, from, to)
}
