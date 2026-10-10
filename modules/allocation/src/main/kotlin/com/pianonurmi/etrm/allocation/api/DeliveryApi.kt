package com.pianonurmi.etrm.allocation.api

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import java.util.UUID

/** Delivery of allowances sold on a trade: one Union Registry transfer from our account to the buyer's account. */
data class DeliverAllowances(
    val scheduleId: UUID,
    /** Defaults to the whole remaining quantity of the delivery schedule. */
    val quantity: Long? = null,
    /** Defaults to one instruction per schedule; give a key per partial delivery. */
    val idempotencyKey: String? = null,
)

/** What arrived on one of our accounts: a certificate lot, or one movement on an allowance holding. */
sealed interface ReceiptSource {
    data class Lot(val lotId: UUID) : ReceiptSource

    data class AllowanceMovement(val holdingId: UUID, val movementSeq: Long) : ReceiptSource
}

/** Manual match of a receipt to a purchase delivery schedule when the reference did not name the trade. */
data class MatchReceipt(
    val scheduleId: UUID,
    val lotId: UUID? = null,
    val holdingId: UUID? = null,
    val movementSeq: Long? = null,
) {
    fun source(): ReceiptSource = when {
        lotId != null && holdingId == null -> ReceiptSource.Lot(lotId)
        lotId == null && holdingId != null && movementSeq != null -> ReceiptSource.AllowanceMovement(holdingId, movementSeq)
        else -> throw ValidationException("Give either lotId, or holdingId and movementSeq")
    }
}

/** A receipt recorded as delivery on a purchase schedule. */
data class ReceiptMatch(
    val scheduleId: UUID,
    val tradeId: UUID,
    val tradeNumber: String,
    val quantity: Long,
    val idempotencyKey: String,
)

interface DeliveryCommand {
    /** Submits the Union Registry transfer for an allowance sale; the trade delivery is recorded on confirmation. */
    fun deliverAllowances(command: DeliverAllowances): RegistryInstructionView

    /** Records a receipt against a purchase schedule (idempotent per receipt). */
    fun matchReceipt(command: MatchReceipt): ReceiptMatch
}
