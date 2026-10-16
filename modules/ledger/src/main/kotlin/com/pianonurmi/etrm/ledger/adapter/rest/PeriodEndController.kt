package com.pianonurmi.etrm.ledger.adapter.rest

import com.pianonurmi.etrm.ledger.api.MtmPolicyView
import com.pianonurmi.etrm.ledger.api.NrvPolicyView
import com.pianonurmi.etrm.ledger.api.PeriodEndCommand
import com.pianonurmi.etrm.ledger.api.PeriodEndQuery
import com.pianonurmi.etrm.ledger.api.PeriodEndRunView
import com.pianonurmi.etrm.ledger.api.PolicyChangeStatus
import com.pianonurmi.etrm.ledger.api.PolicyChangeView
import com.pianonurmi.etrm.ledger.api.RequestPolicyChange
import com.pianonurmi.etrm.platform.async.AsyncRunView
import com.pianonurmi.etrm.platform.async.AsyncRuns
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
import java.util.UUID

@RestController
@RequestMapping("/api/v1/ledger/period-end")
@Tag(name = "Period end", description = "MtM, net realisable value, emission provision, grant release and FX revaluation; accounting policies")
class PeriodEndController(
    private val command: PeriodEndCommand,
    private val query: PeriodEndQuery,
    private val runs: AsyncRuns,
    private val actors: ActorProvider,
) {
    /** Starts a period-end run (202); poll `GET /api/v1/runs/{id}`, whose result id is the period-end run's. */
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @AllowedRoles(Role.ACCOUNTANT)
    fun run(
        @RequestBody body: RunPeriodEnd,
    ): AsyncRunView {
        actors.current().apply {
            requireRole(Role.ACCOUNTANT)
            requireEntity(body.legalEntityId)
        }
        return runs.start(RUN_KIND, body.legalEntityId) { command.run(body.legalEntityId, body.period).id }
    }

    @GetMapping("/runs")
    fun runs(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) period: YearMonth?,
    ): List<PeriodEndRunView> = query.runs(legalEntityId, period)

    @GetMapping("/policies/mtm")
    fun mtmPolicies(
        @RequestParam(required = false) legalEntityId: UUID?,
    ): List<MtmPolicyView> = query.mtmPolicies(legalEntityId)

    @GetMapping("/policies/nrv")
    fun nrvPolicy(
        @RequestParam(required = false) legalEntityId: UUID?,
    ): NrvPolicyView = query.nrvPolicy(legalEntityId)

    @GetMapping("/policy-changes")
    fun policyChanges(
        @RequestParam(required = false) status: PolicyChangeStatus?,
    ): List<PolicyChangeView> = query.policyChanges(status)

    @PostMapping("/policy-changes")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.ACCOUNTANT)
    fun requestPolicyChange(
        @RequestBody body: RequestPolicyChange,
    ): PolicyChangeView = command.requestPolicyChange(body)
}

private const val RUN_KIND = "PERIOD_END"

data class RunPeriodEnd(val legalEntityId: UUID, val period: YearMonth)
