package com.pianonurmi.etrm.inventory.domain

import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import java.time.LocalDate
import java.util.UUID

/**
 * Aggregate wrapping the immutable [LotView] state. Transitions return the new state; persistence applies it with an
 * optimistic version check, and the service appends the corresponding journal event in the same transaction.
 */
class CertificateLot(state: LotView) {
    var state: LotView = state
        private set

    val id: UUID get() = state.id

    /** Splits the first [quantity] certificates into a new lot; the parent is closed with status SPLIT. */
    fun split(quantity: Long): Pair<LotView, LotView> {
        requireStatus(LotStatus.HELD, LotStatus.RESERVED)
        if (quantity <= 0 || quantity >= state.quantityMwh) {
            throw ValidationException("Split quantity $quantity must be between 1 and ${state.quantityMwh - 1}")
        }
        val (head, tail) = state.range.splitHead(quantity)
        val carved = child(head)
        val retained = child(tail)
        state = state.copy(status = LotStatus.SPLIT, version = state.version + 1)
        return carved to retained
    }

    fun reserve(reservationRef: UUID): LotView {
        requireStatus(LotStatus.HELD)
        return transition(state.copy(status = LotStatus.RESERVED, reservationRef = reservationRef))
    }

    fun release(reservationRef: UUID): LotView {
        requireStatus(LotStatus.RESERVED)
        if (state.reservationRef != reservationRef) throw ConflictException("Lot $id is reserved by ${state.reservationRef}, not $reservationRef")
        return transition(state.copy(status = LotStatus.HELD, reservationRef = null))
    }

    fun startTransfer(instructionRef: UUID): LotView {
        requireStatus(LotStatus.HELD, LotStatus.RESERVED)
        return transition(state.copy(status = LotStatus.IN_TRANSFER, instructionRef = instructionRef))
    }

    fun confirmTransferOut(instructionRef: UUID): LotView {
        requireStatus(LotStatus.IN_TRANSFER)
        requireInstruction(instructionRef)
        return transition(state.copy(status = LotStatus.TRANSFERRED_OUT, reservationRef = null))
    }

    fun failTransfer(instructionRef: UUID): LotView {
        requireStatus(LotStatus.IN_TRANSFER)
        requireInstruction(instructionRef)
        val back = if (state.reservationRef != null) LotStatus.RESERVED else LotStatus.HELD
        return transition(state.copy(status = back, instructionRef = null))
    }

    fun cancel(details: CancellationDetails): LotView {
        requireStatus(LotStatus.HELD, LotStatus.RESERVED, LotStatus.IN_TRANSFER)
        return transition(state.copy(status = LotStatus.CANCELLED, cancellation = details, reservationRef = null, instructionRef = null))
    }

    fun expire(asOf: LocalDate): LotView {
        requireStatus(LotStatus.HELD, LotStatus.RESERVED)
        if (asOf.isBefore(state.expiryDate)) throw ValidationException("Lot $id expires on ${state.expiryDate}, not before")
        return transition(state.copy(status = LotStatus.EXPIRED, reservationRef = null))
    }

    fun withdraw(): LotView {
        if (!state.status.live) throw IllegalStateTransitionException("Lot $id", state.status.name, LotStatus.WITHDRAWN.name)
        return transition(state.copy(status = LotStatus.WITHDRAWN, reservationRef = null, instructionRef = null))
    }

    fun correct(corrected: LotView): LotView {
        if (!state.status.live) throw IllegalStateTransitionException("Lot $id", state.status.name, "corrected")
        return transition(corrected)
    }

    private fun child(range: CertificateRange): LotView = state.copy(
        id = Ids.newId(),
        range = range,
        quantityMwh = range.quantity,
        parentLotId = state.id,
        version = 1,
        instructionRef = null,
    )

    private fun transition(next: LotView): LotView {
        state = next.copy(version = state.version + 1)
        return state
    }

    private fun requireStatus(vararg allowed: LotStatus) {
        if (state.status !in allowed) {
            throw IllegalStateTransitionException("Lot $id", state.status.name, "one of ${allowed.joinToString()}")
        }
    }

    private fun requireInstruction(instructionRef: UUID) {
        if (state.instructionRef != instructionRef) throw ConflictException("Lot $id is in transfer under ${state.instructionRef}, not $instructionRef")
    }
}
