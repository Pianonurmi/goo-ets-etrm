package com.pianonurmi.etrm.trade.domain

import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.referencedata.api.ObligationsOpenAt
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Trade lifecycle rules. State is the immutable [TradeView]; every transition yields a new version. */
class TradeAggregate(state: TradeView) {
    var state: TradeView = state
        private set

    fun submitForApproval(requiresApproval: Boolean): TradeView {
        requireStatus(TradeStatus.DRAFT)
        return transition(state.copy(status = if (requiresApproval) TradeStatus.PENDING_APPROVAL else TradeStatus.APPROVED))
    }

    fun approve(approver: String, at: Instant): TradeView {
        requireStatus(TradeStatus.PENDING_APPROVAL)
        if (approver == state.trader || approver == state.createdBy) {
            throw SegregationOfDutiesException("Trade ${state.tradeNumber} cannot be approved by its trader or the user who captured it ($approver)")
        }
        return transition(state.copy(status = TradeStatus.APPROVED, approvedBy = approver, approvedAt = at))
    }

    fun reject(): TradeView {
        requireStatus(TradeStatus.PENDING_APPROVAL)
        return transition(state.copy(status = TradeStatus.DRAFT, approvedBy = null, approvedAt = null))
    }

    /**
     * Records the confirmation. An approved trade becomes CONFIRMED; where obligations open at approval, deliveries may
     * already have started, and the confirmation is recorded without changing the delivery status.
     */
    fun confirm(confirmationRef: String, at: Instant): TradeView {
        if (state.confirmedAt != null) throw IllegalStateTransitionException("Trade ${state.tradeNumber}", "confirmed", "confirmed again")
        if (deliversBeforeConfirmation && state.status in setOf(TradeStatus.PARTIALLY_DELIVERED, TradeStatus.DELIVERED)) {
            return transition(state.copy(confirmedAt = at, confirmationRef = confirmationRef))
        }
        requireStatus(TradeStatus.APPROVED)
        return transition(state.copy(status = TradeStatus.CONFIRMED, confirmedAt = at, confirmationRef = confirmationRef))
    }

    private val deliversBeforeConfirmation: Boolean get() = state.obligationsOpenAt == ObligationsOpenAt.APPROVED

    /** Amendments re-open approval; a firm trade that is amended falls back to pending approval. */
    fun amend(amended: TradeView): TradeView {
        requireStatus(TradeStatus.DRAFT, TradeStatus.PENDING_APPROVAL, TradeStatus.APPROVED, TradeStatus.CONFIRMED)
        if (state.legs.flatMap { it.deliveries }.any { it.deliveredQuantity.signum() > 0 }) {
            throw ValidationException("Trade ${state.tradeNumber} has deliveries recorded; amend by a new trade and a cancellation of the remainder")
        }
        // An amended trade is approved and confirmed again on its new terms.
        return transition(amended.copy(status = TradeStatus.PENDING_APPROVAL, approvedBy = null, approvedAt = null, confirmedAt = null, confirmationRef = null))
    }

    fun cancel(): TradeView {
        if (state.status in setOf(TradeStatus.DELIVERED, TradeStatus.SETTLED, TradeStatus.CLOSED, TradeStatus.CANCELLED)) {
            throw IllegalStateTransitionException("Trade ${state.tradeNumber}", state.status.name, TradeStatus.CANCELLED.name)
        }
        val legs = state.legs.map { leg ->
            leg.copy(deliveries = leg.deliveries.map { d -> if (d.status == DeliveryStatus.DELIVERED) d else d.copy(status = DeliveryStatus.CANCELLED) })
        }
        return transition(state.copy(status = TradeStatus.CANCELLED, legs = legs))
    }

    fun recordDelivery(scheduleId: UUID, quantity: BigDecimal): Pair<TradeView, DeliveryScheduleView> {
        if (deliversBeforeConfirmation) {
            requireStatus(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)
        } else {
            requireStatus(TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)
        }
        if (quantity.signum() <= 0) throw ValidationException("Delivered quantity must be positive")
        var updatedSchedule: DeliveryScheduleView? = null
        val legs = state.legs.map { leg ->
            leg.copy(
                deliveries = leg.deliveries.map { d ->
                    if (d.id != scheduleId) {
                        d
                    } else {
                        if (d.status == DeliveryStatus.CANCELLED || d.status == DeliveryStatus.DELIVERED) {
                            throw IllegalStateTransitionException("Delivery ${d.id}", d.status.name, "delivered")
                        }
                        val total = d.deliveredQuantity + quantity
                        if (total > d.quantity) throw ValidationException("Delivery of $quantity exceeds remaining ${d.remainingQuantity} on schedule ${d.id}")
                        d.copy(
                            deliveredQuantity = total,
                            status = if (total.compareTo(d.quantity) == 0) DeliveryStatus.DELIVERED else DeliveryStatus.PARTIALLY_DELIVERED,
                        ).also { updatedSchedule = it }
                    }
                },
            )
        }
        val schedule = updatedSchedule ?: throw ValidationException("Delivery schedule $scheduleId does not belong to trade ${state.tradeNumber}")
        val all = legs.flatMap { it.deliveries }
        val status = when {
            all.all { it.status == DeliveryStatus.DELIVERED || it.status == DeliveryStatus.CANCELLED } -> TradeStatus.DELIVERED
            else -> TradeStatus.PARTIALLY_DELIVERED
        }
        return transition(state.copy(status = status, legs = legs)) to schedule
    }

    fun markSettled(): TradeView {
        requireStatus(TradeStatus.DELIVERED)
        return transition(state.copy(status = TradeStatus.SETTLED))
    }

    private fun transition(next: TradeView): TradeView {
        state = next.copy(version = state.version + 1)
        return state
    }

    private fun requireStatus(vararg allowed: TradeStatus) {
        if (state.status !in allowed) {
            throw IllegalStateTransitionException("Trade ${state.tradeNumber}", state.status.name, "one of ${allowed.joinToString()}")
        }
    }
}
