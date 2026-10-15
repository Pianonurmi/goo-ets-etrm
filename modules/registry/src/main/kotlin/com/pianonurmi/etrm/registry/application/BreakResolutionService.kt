package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.BreakAction
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotCorrection
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.ReconciliationCommand
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.registry.api.BreakResolutionCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Orchestrates break resolution: the correcting action (on lots, holdings or instructions) and closing the break run in
 * one transaction, so a break is never closed without its correction or corrected without being closed.
 */
@Service
@Transactional
class BreakResolutionService(
    private val reconciliation: ReconciliationCommand,
    private val reconciliationQuery: ReconciliationQuery,
    private val lots: LotCommand,
    private val lotQuery: LotQuery,
    private val allowances: AllowanceHoldingCommand,
    private val instructions: InstructionOutcomes,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : BreakResolutionCommand {
    override fun assign(breakId: UUID, assignee: String?): ReconciliationBreakView {
        val actor = operator()
        return reconciliation.assignBreak(breakId, assignee?.takeIf { it.isNotBlank() } ?: actor)
    }

    override fun confirmInstruction(breakId: UUID, externalRef: String?, note: String): ReconciliationBreakView {
        operator()
        val brk = open(breakId, BreakType.MISSING_IN_REGISTRY)
        val lot = lotOf(brk)
        val instructionId = lot.instructionRef
        if (lot.status != LotStatus.IN_TRANSFER || instructionId == null) throw ConflictException("Lot ${lot.id} is ${lot.status}, not in transfer")
        instructions.confirm(instructionId, externalRef)
        return reconciliation.resolveBreak(breakId, BreakAction.CONFIRM_INSTRUCTION, "${note.trim()} (instruction $instructionId confirmed)")
    }

    override fun recordReceipt(breakId: UUID, receipt: ReceiveLots, note: String): ReconciliationBreakView {
        operator()
        val brk = open(breakId, BreakType.MISSING_INTERNALLY)
        requireSameCertificates(brk, receipt, brk.range!!)
        val lot = lots.receive(receipt)
        return reconciliation.resolveBreak(breakId, BreakAction.RECORD_RECEIPT, "${note.trim()} (lot ${lot.id})")
    }

    override fun withdrawLot(breakId: UUID, note: String): ReconciliationBreakView {
        operator()
        val brk = open(breakId, BreakType.MISSING_IN_REGISTRY)
        val lot = heldLot(brk)
        val missing = isolate(lot, brk.range!!)
        lots.withdraw(missing.id, "reconciliation break $breakId: ${note.trim()}")
        return reconciliation.resolveBreak(breakId, BreakAction.WITHDRAW_LOT, "${note.trim()} (lot ${missing.id} withdrawn)")
    }

    override fun replaceLot(breakId: UUID, replacement: ReceiveLots, note: String): ReconciliationBreakView {
        operator()
        val brk = open(breakId, BreakType.ATTRIBUTE_MISMATCH)
        val lot = heldLot(brk)
        requireSameCertificates(brk, replacement, lot.range)
        if (replacement.holderEntityId != lot.holderEntityId) throw ValidationException("The replacement must stay with the holder of lot ${lot.id}")
        lots.withdraw(lot.id, "replaced after reconciliation break $breakId: ${note.trim()}")
        val replaced = lots.receive(replacement)
        return reconciliation.resolveBreak(breakId, BreakAction.REPLACE_LOT, "${note.trim()} (lot ${lot.id} replaced by ${replaced.id})")
    }

    override fun correctLot(breakId: UUID, correction: LotCorrection): ReconciliationBreakView {
        operator()
        val brk = open(breakId, null)
        val lot = lotOf(brk)
        lots.correct(lot.id, correction.copy(reason = "reconciliation break $breakId: ${correction.reason}"))
        return reconciliation.resolveBreak(breakId, BreakAction.CORRECT_LOT, correction.reason)
    }

    override fun correctHolding(breakId: UUID, note: String): ReconciliationBreakView {
        operator()
        val brk = open(breakId, BreakType.BALANCE_MISMATCH)
        val difference = brk.registryQuantity!! - brk.internalQuantity!!
        val account = referenceData.requireRegistryAccount(brk.registryAccountId!!)
        allowances.record(
            RecordAllowanceMovement(
                holderEntityId = account.holderId,
                registryAccountId = account.id,
                productCode = brk.productCode!!,
                type = AllowanceMovementType.CORRECTION,
                quantity = difference,
                effectiveDate = brk.lastSeenOn ?: LocalDate.now(clock),
                reference = "break:$breakId",
            ),
        )
        return reconciliation.resolveBreak(breakId, BreakAction.CORRECT_HOLDING, "${note.trim()} (corrected by $difference)")
    }

    override fun requestAcceptance(breakId: UUID, reason: String): ReconciliationBreakView {
        operator()
        return reconciliation.requestAcceptance(breakId, reason)
    }

    // ----------------------------------------------------------------------------------------------------------

    private fun operator(): String {
        val actor = actorProvider.current()
        actor.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
        return actor.userId
    }

    private fun open(breakId: UUID, type: BreakType?): ReconciliationBreakView {
        val brk = reconciliationQuery.requireBreak(breakId)
        if (brk.status != BreakStatus.OPEN && brk.status != BreakStatus.INVESTIGATING) {
            throw IllegalStateTransitionException("ReconciliationBreak", brk.status.name, BreakStatus.RESOLVED.name)
        }
        if (type != null && brk.type != type) throw ValidationException("This action applies to $type breaks; break $breakId is ${brk.type}")
        return brk
    }

    private fun lotOf(brk: ReconciliationBreakView): LotView =
        brk.lotId?.let(lotQuery::requireLot) ?: throw ValidationException("Break ${brk.id} concerns no lot of ours")

    private fun heldLot(brk: ReconciliationBreakView): LotView {
        val lot = lotOf(brk)
        if (lot.status != LotStatus.HELD) throw ConflictException("Lot ${lot.id} is ${lot.status}; release or settle it before correcting")
        return lot
    }

    private fun requireSameCertificates(brk: ReconciliationBreakView, receipt: ReceiveLots, range: CertificateRange) {
        if (receipt.registryAccountId != brk.registryAccountId) throw ValidationException("The certificates are on account ${brk.registryAccountId}")
        if (receipt.issuanceRef.trim() != brk.issuanceRef) throw ValidationException("The certificates belong to issuance ${brk.issuanceRef}")
        if (receipt.range != range) throw ValidationException("The certificates are ${range.start}-${range.end}")
    }

    /** Splits [lot] so that exactly [range] is one lot, and returns that lot. */
    private fun isolate(lot: LotView, range: CertificateRange): LotView {
        var target = lot
        if (range.start > target.range.start) {
            target = lots.split(target.id, (range.start - target.range.start).toLong()).retained
        }
        if (range.end < target.range.end) {
            target = lots.split(target.id, range.quantity).carved
        }
        return target
    }
}
