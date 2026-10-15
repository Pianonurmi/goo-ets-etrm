package com.pianonurmi.etrm.risk.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.risk.api.CounterpartyExposureView
import com.pianonurmi.etrm.risk.api.CreditSupportView
import com.pianonurmi.etrm.risk.api.LimitBreachView
import com.pianonurmi.etrm.risk.api.LimitUtilisationView
import com.pianonurmi.etrm.risk.api.PfeAddOnView
import com.pianonurmi.etrm.risk.api.RequestCreditSupport
import com.pianonurmi.etrm.risk.api.RequestLimit
import com.pianonurmi.etrm.risk.api.RiskCommand
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.RiskQuery
import com.pianonurmi.etrm.risk.application.RiskQueries
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/risk")
@Tag(name = "Risk", description = "Limits, credit exposure, credit support, breaches and pre-trade checks")
class RiskController(
    private val query: RiskQuery,
    private val command: RiskCommand,
    private val risk: RiskQueries,
) {
    @GetMapping("/limits")
    fun limits(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<RiskLimitView> = query.limits(legalEntityId, activeOnly)

    @PostMapping("/limits")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.RISK)
    fun requestLimit(
        @RequestBody body: RequestLimit,
    ): RiskLimitView = command.requestLimit(body)

    @GetMapping("/limits/{id}")
    fun limit(
        @PathVariable id: UUID,
    ): RiskLimitView = query.limit(id) ?: throw NotFoundException("RiskLimit", id)

    @GetMapping("/limits/{id}/utilisation")
    fun utilisation(
        @PathVariable id: UUID,
    ): LimitUtilisationView = query.utilisation(id) ?: throw NotFoundException("LimitUtilisation", id)

    @PostMapping("/measure")
    @AllowedRoles(Role.RISK)
    fun measure(
        @RequestParam legalEntityId: UUID,
    ): List<LimitUtilisationView> = command.measure(legalEntityId)

    @GetMapping("/exposures")
    fun exposures(
        @RequestParam legalEntityId: UUID,
    ): List<CounterpartyExposureView> = query.exposures(legalEntityId)

    @GetMapping("/breaches")
    fun breaches(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(defaultValue = "true") openOnly: Boolean,
    ): List<LimitBreachView> = query.breaches(legalEntityId, openOnly)

    @GetMapping("/credit-support")
    fun creditSupport(
        @RequestParam counterpartyId: UUID,
    ): List<CreditSupportView> = query.creditSupport(counterpartyId)

    @PostMapping("/credit-support")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.RISK, Role.BACK_OFFICE)
    fun requestCreditSupport(
        @RequestBody body: RequestCreditSupport,
    ): CreditSupportView = command.requestCreditSupport(body)

    @GetMapping("/pfe-add-ons")
    fun pfeAddOns(): List<PfeAddOnView> = query.pfeAddOns()

    /** The pre-trade check against current limits for an existing trade (what-if, nothing is stored). */
    @PostMapping("/pre-trade-check")
    @AllowedRoles(Role.RISK, Role.MIDDLE_OFFICE, Role.TRADER, Role.AUDITOR)
    fun preTradeCheck(
        @RequestParam tradeId: UUID,
    ): List<ApprovalFinding> = risk.recheck(tradeId)
}
