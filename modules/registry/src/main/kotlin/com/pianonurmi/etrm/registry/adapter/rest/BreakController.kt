package com.pianonurmi.etrm.registry.adapter.rest

import com.pianonurmi.etrm.inventory.api.LotCorrection
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
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
class BreakController(private val breaks: BreakResolutionCommand) {
    @PostMapping("/assign")
    fun assign(
        @PathVariable id: UUID,
        @RequestBody(required = false) body: AssignBreak?,
    ): ReconciliationBreakView = breaks.assign(id, body?.assignee)

    @PostMapping("/confirm-instruction")
    fun confirmInstruction(
        @PathVariable id: UUID,
        @RequestBody body: ConfirmInstructionForBreak,
    ): ReconciliationBreakView = breaks.confirmInstruction(id, body.externalRef, body.note)

    @PostMapping("/record-receipt")
    fun recordReceipt(
        @PathVariable id: UUID,
        @RequestBody body: ReceiveForBreak,
    ): ReconciliationBreakView = breaks.recordReceipt(id, body.receipt, body.note)

    @PostMapping("/withdraw-lot")
    fun withdrawLot(
        @PathVariable id: UUID,
        @RequestBody body: BreakNote,
    ): ReconciliationBreakView = breaks.withdrawLot(id, body.note)

    @PostMapping("/replace-lot")
    fun replaceLot(
        @PathVariable id: UUID,
        @RequestBody body: ReceiveForBreak,
    ): ReconciliationBreakView = breaks.replaceLot(id, body.receipt, body.note)

    @PostMapping("/correct-lot")
    fun correctLot(
        @PathVariable id: UUID,
        @RequestBody body: LotCorrection,
    ): ReconciliationBreakView = breaks.correctLot(id, body)

    @PostMapping("/correct-holding")
    fun correctHolding(
        @PathVariable id: UUID,
        @RequestBody body: BreakNote,
    ): ReconciliationBreakView = breaks.correctHolding(id, body.note)

    /** Opens a BREAK_ACCEPTANCE task for a second person. */
    @PostMapping("/accept")
    fun accept(
        @PathVariable id: UUID,
        @RequestBody body: BreakNote,
    ): ReconciliationBreakView = breaks.requestAcceptance(id, body.note)
}
