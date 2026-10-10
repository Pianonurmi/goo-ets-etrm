package com.pianonurmi.etrm.registry.api

import com.pianonurmi.etrm.inventory.api.LotCorrection
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import java.util.UUID

/**
 * Resolution of reconciliation breaks. Each action corrects our records to what the registry shows and closes the
 * break in the same transaction; accepting a break as it is needs a second person.
 */
interface BreakResolutionCommand {
    /** Takes the break into investigation (by the current user when [assignee] is null). */
    fun assign(breakId: UUID, assignee: String?): ReconciliationBreakView

    /**
     * MISSING_IN_REGISTRY on a lot in transfer: the operator has checked in the registry that the instruction was
     * executed (a lot wholly gone confirms itself from the statement; partly gone needs a person) and confirms it.
     */
    fun confirmInstruction(breakId: UUID, externalRef: String?, note: String): ReconciliationBreakView

    /** MISSING_INTERNALLY: record the certificates the registry shows (exactly the break's issuance and range). */
    fun recordReceipt(breakId: UUID, receipt: ReceiveLots, note: String): ReconciliationBreakView

    /** MISSING_IN_REGISTRY on a held lot: the certificates are gone; withdraw the missing range. */
    fun withdrawLot(breakId: UUID, note: String): ReconciliationBreakView

    /** ATTRIBUTE_MISMATCH: withdraw the lot and record it again with the registry's attributes (same range). */
    fun replaceLot(breakId: UUID, replacement: ReceiveLots, note: String): ReconciliationBreakView

    /** Any lot break whose cause is a correctable lot attribute (labels, support, expiry, cost). */
    fun correctLot(breakId: UUID, correction: LotCorrection): ReconciliationBreakView

    /** BALANCE_MISMATCH: book a CORRECTION movement so the holding equals the registry balance. */
    fun correctHolding(breakId: UUID, note: String): ReconciliationBreakView

    /** Asks a second person to accept the break as it is (BREAK_ACCEPTANCE task). */
    fun requestAcceptance(breakId: UUID, reason: String): ReconciliationBreakView
}

data class BreakNote(val note: String)

data class AssignBreak(val assignee: String? = null)

data class ConfirmInstructionForBreak(val externalRef: String? = null, val note: String)

data class ReceiveForBreak(val receipt: ReceiveLots, val note: String)
