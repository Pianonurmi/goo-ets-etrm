package com.pianonurmi.etrm.registry.adapter.rest

import com.pianonurmi.etrm.inventory.api.LotCorrection
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.registry.api.AssignBreak
import com.pianonurmi.etrm.registry.api.BreakNote
import com.pianonurmi.etrm.registry.api.BreakResolutionCommand
import com.pianonurmi.etrm.registry.api.ConfirmInstructionForBreak
import com.pianonurmi.etrm.registry.api.ReceiveForBreak
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Break resolution; listing and ageing are under /api/v1/inventory/breaks. */
@RestController
@RequestMapping("/api/v1/registry/breaks/{id}")
@Tag(name = "Reconciliation breaks", description = "Investigate, correct or accept reconciliation breaks")
class BreakController(
    private val breaks: BreakResolutionCommand,
    private val reconciliation: ReconciliationQuery,
    private val access: RegistryAccess,
) {
    /** Break work needs registry operator or back office rights on the account holder's entity. */
    private fun guard(id: UUID) = access.importer(reconciliation.requireBreak(id).registryAccountId)

    @PostMapping("/assign")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun assign(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: AssignBreak?,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.assign(id, body?.assignee) }

    @PostMapping("/confirm-instruction")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun confirmInstruction(
        @PathVariable id: UUID,
        @RequestBody body: ConfirmInstructionForBreak,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.confirmInstruction(id, body.externalRef, body.note) }

    @PostMapping("/record-receipt")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun recordReceipt(
        @PathVariable id: UUID,
        @RequestBody body: ReceiveForBreak,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.recordReceipt(id, body.receipt, body.note) }

    @PostMapping("/withdraw-lot")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun withdrawLot(
        @PathVariable id: UUID,
        @RequestBody body: BreakNote,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.withdrawLot(id, body.note) }

    @PostMapping("/replace-lot")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun replaceLot(
        @PathVariable id: UUID,
        @RequestBody body: ReceiveForBreak,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.replaceLot(id, body.receipt, body.note) }

    @PostMapping("/correct-lot")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun correctLot(
        @PathVariable id: UUID,
        @RequestBody body: LotCorrection,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.correctLot(id, body) }

    @PostMapping("/correct-holding")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun correctHolding(
        @PathVariable id: UUID,
        @RequestBody body: BreakNote,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.correctHolding(id, body.note) }

    /** Opens a BREAK_ACCEPTANCE task for a second person. */
    @PostMapping("/accept")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun accept(
        @PathVariable id: UUID,
        @RequestBody body: BreakNote,
    ): ReconciliationBreakView = guard(id).let { _ -> breaks.requestAcceptance(id, body.note) }
}
