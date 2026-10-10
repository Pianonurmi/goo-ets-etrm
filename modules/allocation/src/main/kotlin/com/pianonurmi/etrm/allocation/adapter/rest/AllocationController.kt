package com.pianonurmi.etrm.allocation.adapter.rest

import com.pianonurmi.etrm.allocation.api.AllocationCommand
import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.AllocationPlanView
import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.allocation.api.CoverageReport
import com.pianonurmi.etrm.allocation.api.ObligationCommand
import com.pianonurmi.etrm.allocation.api.ObligationQuery
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.OpenObligation
import com.pianonurmi.etrm.allocation.api.ProposeAllocation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/allocation")
@Tag(name = "Allocation", description = "Obligations, coverage analysis and allocation plans")
class AllocationController(
    private val allocation: AllocationCommand,
    private val allocationQuery: AllocationQuery,
    private val obligations: ObligationCommand,
    private val obligationQuery: ObligationQuery,
) {
    @GetMapping("/obligations")
    fun obligations(
        @RequestParam holderEntityId: UUID,
    ): List<ObligationView> = obligationQuery.open(holderEntityId)

    @GetMapping("/obligations/{id}")
    fun obligation(
        @PathVariable id: UUID,
    ): ObligationView = obligationQuery.requireObligation(id)

    @PostMapping("/obligations")
    @ResponseStatus(HttpStatus.CREATED)
    fun openObligation(
        @Valid @RequestBody body: OpenObligation,
    ): ObligationView = obligations.open(body)

    @PostMapping("/obligations/{id}/close")
    fun closeObligation(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): ObligationView = obligations.close(id, body.reason)

    @PutMapping("/obligations/{id}/constraints")
    fun setConstraints(
        @PathVariable id: UUID,
        @RequestBody body: List<AllocationConstraint>,
    ): ObligationView = obligations.setConstraints(id, body)

    @GetMapping("/coverage")
    fun coverage(
        @RequestParam holderEntityId: UUID,
        @RequestParam(required = false) obligationId: Set<UUID>?,
    ): CoverageReport =
        allocationQuery.coverage(holderEntityId, obligationId)

    @GetMapping("/plans")
    fun plans(
        @RequestParam holderEntityId: UUID,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<AllocationPlanView> =
        allocationQuery.plans(holderEntityId, activeOnly)

    @GetMapping("/plans/{id}")
    fun plan(
        @PathVariable id: UUID,
    ): AllocationPlanView = allocationQuery.requirePlan(id)

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    fun propose(
        @Valid @RequestBody body: ProposeAllocation,
    ): AllocationPlanView = allocation.propose(body)

    @PostMapping("/plans/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
    ): AllocationPlanView = allocation.approve(id)

    @PostMapping("/plans/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): AllocationPlanView = allocation.reject(id, body.reason)

    @PostMapping("/plans/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): AllocationPlanView = allocation.cancel(id, body.reason)
}

data class ReasonRequest(val reason: String)
