package com.pianonurmi.etrm.ledger.adapter.rest

import com.pianonurmi.etrm.ledger.api.HedgeCommand
import com.pianonurmi.etrm.ledger.api.HedgeDetail
import com.pianonurmi.etrm.ledger.api.HedgeQuery
import com.pianonurmi.etrm.ledger.api.HedgeRelationshipView
import com.pianonurmi.etrm.ledger.api.HedgeStatus
import com.pianonurmi.etrm.ledger.api.RequestHedgeDesignation
import com.pianonurmi.etrm.ledger.api.RequestHedgeDiscontinuation
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
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

/** Roles and entity scope are checked in [com.pianonurmi.etrm.ledger.application.HedgeService]. */
@RestController
@RequestMapping("/api/v1/ledger/hedges")
@Tag(name = "Hedge accounting", description = "Cash flow hedges: designation and discontinuation under four eyes, effectiveness per official valuation")
class HedgeController(private val command: HedgeCommand, private val query: HedgeQuery) {
    @GetMapping
    fun hedges(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) status: HedgeStatus?,
    ): List<HedgeRelationshipView> = query.hedges(legalEntityId, status)

    @GetMapping("/{id}")
    fun hedge(
        @PathVariable id: UUID,
    ): HedgeDetail = query.hedge(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.ACCOUNTANT)
    fun requestDesignation(
        @RequestBody body: RequestHedgeDesignation,
    ): HedgeRelationshipView = command.requestDesignation(body)

    @PostMapping("/{id}/discontinuation")
    @AllowedRoles(Role.ACCOUNTANT)
    fun requestDiscontinuation(
        @PathVariable id: UUID,
        @RequestBody body: RequestHedgeDiscontinuation,
    ): HedgeRelationshipView = command.requestDiscontinuation(id, body)
}
