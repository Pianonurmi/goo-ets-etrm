package com.pianonurmi.etrm.allocation.adapter.rest

import com.pianonurmi.etrm.allocation.api.AllocationCommand
import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.AllocationPlanView
import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.allocation.api.AllocationWhatIf
import com.pianonurmi.etrm.allocation.api.CoverageReport
import com.pianonurmi.etrm.allocation.api.FutureSupplyQuery
import com.pianonurmi.etrm.allocation.api.FutureSupplyView
import com.pianonurmi.etrm.allocation.api.HeadroomCheck
import com.pianonurmi.etrm.allocation.api.HeadroomQuery
import com.pianonurmi.etrm.allocation.api.HeadroomResult
import com.pianonurmi.etrm.allocation.api.HeadroomTable
import com.pianonurmi.etrm.allocation.api.ObligationCommand
import com.pianonurmi.etrm.allocation.api.ObligationQuery
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.OpenObligation
import com.pianonurmi.etrm.allocation.api.PortfolioQuery
import com.pianonurmi.etrm.allocation.api.PortfolioView
import com.pianonurmi.etrm.allocation.api.ProposeAllocation
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
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
    private val actorProvider: ActorProvider,
    private val headroom: HeadroomQuery,
    private val portfolioQuery: PortfolioQuery,
    private val futureSupplyQuery: FutureSupplyQuery,
) {
    /** The GoO book by product bucket, netted through the eligibility flow (ADR 0037). */
    @GetMapping("/portfolio")
    fun portfolio(
        @RequestParam holderEntityId: UUID,
        @RequestParam(defaultValue = "HELD") tier: SupplyTier,
    ): PortfolioView = portfolioQuery.portfolio(holderEntityId, tier)

    /** Purchases and expected issuance still to come, and what they add to coverage by delivery month (ADR 0038). */
    @GetMapping("/future-supply")
    fun futureSupply(
        @RequestParam holderEntityId: UUID,
    ): FutureSupplyView = futureSupplyQuery.futureSupply(holderEntityId)

    /** Available to sell (ADR 0036): how much of each demand the entity could still sell, by default from held certificates. */
    @PostMapping("/headroom/check")
    fun headroomCheck(
        @RequestBody body: HeadroomCheck,
    ): HeadroomResult = headroom.check(body)

    /** Available to sell per product bucket and vintage, for delivery at each of the next month-ends. */
    @GetMapping("/headroom")
    fun headroomTable(
        @RequestParam holderEntityId: UUID,
        @RequestParam(defaultValue = "HELD") tier: SupplyTier,
    ): HeadroomTable = headroom.table(holderEntityId, tier)

    @GetMapping("/obligations")
    fun obligations(
        @RequestParam holderEntityId: UUID,
    ): List<ObligationView> = obligationQuery.open(holderEntityId)

    @GetMapping("/obligations/{id}")
    fun obligation(
        @PathVariable id: UUID,
    ): ObligationView = obligationQuery.requireObligation(id).also { actorProvider.current().requireEntity(it.holderEntityId) }

    @PostMapping("/obligations")
    @ResponseStatus(HttpStatus.CREATED)
    fun openObligation(
        @Valid @RequestBody body: OpenObligation,
    ): ObligationView {
        obligationOperator(body.holderEntityId)
        return obligations.open(body)
    }

    @PostMapping("/obligations/{id}/close")
    fun closeObligation(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): ObligationView {
        obligationOperator(obligationQuery.requireObligation(id).holderEntityId)
        return obligations.close(id, body.reason)
    }

    @PutMapping("/obligations/{id}/constraints")
    fun setConstraints(
        @PathVariable id: UUID,
        @RequestBody body: List<AllocationConstraint>,
    ): ObligationView {
        obligationOperator(obligationQuery.requireObligation(id).holderEntityId)
        return obligations.setConstraints(id, body)
    }

    /**
     * Manual obligations are kept by middle and back office on their entities. The guard sits here because module
     * flows (trades, compliance) open and close obligations as SYSTEM.
     */
    private fun obligationOperator(holderEntityId: java.util.UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        actor.requireEntity(holderEntityId)
    }

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

    /** What proposing now would give; stores and reserves nothing. */
    @PostMapping("/plans/what-if")
    fun whatIf(
        @RequestBody body: ProposeAllocation,
    ): AllocationWhatIf = allocation.whatIf(body)

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
