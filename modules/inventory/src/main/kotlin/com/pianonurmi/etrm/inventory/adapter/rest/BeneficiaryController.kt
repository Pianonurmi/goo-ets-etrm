package com.pianonurmi.etrm.inventory.adapter.rest

import com.pianonurmi.etrm.inventory.api.BeneficiaryCommand
import com.pianonurmi.etrm.inventory.api.BeneficiaryQuery
import com.pianonurmi.etrm.inventory.api.BeneficiaryView
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The beneficiary register for a data subject's request (`docs/operations/data-protection.md`): an administrator or
 * auditor reads an entry, an administrator asks for its erasure, and an auditor approves it in the inbox.
 */
@RestController
@RequestMapping("/api/v1/inventory/beneficiaries")
@Tag(name = "Inventory", description = "Certificate lots, allowance holdings and registry reconciliation")
class BeneficiaryController(
    private val command: BeneficiaryCommand,
    private val query: BeneficiaryQuery,
    private val actorProvider: ActorProvider,
) {
    @GetMapping("/{id}")
    fun beneficiary(
        @PathVariable id: UUID,
    ): BeneficiaryView {
        actorProvider.current().requireRole(Role.ADMIN, Role.AUDITOR)
        return query.beneficiary(id) ?: throw NotFoundException("Beneficiary", id)
    }

    @PostMapping("/{id}/erasure")
    fun requestErasure(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): BeneficiaryView = command.requestErasure(id, body.reason)
}
