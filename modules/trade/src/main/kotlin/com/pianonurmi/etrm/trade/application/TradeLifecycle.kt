package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.DeliveryObligationChanged
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.OptionExpired
import com.pianonurmi.etrm.trade.api.OptionExpiryView
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeSettled
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeAggregate
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** A captured trade's way from approval through confirmation and deliveries to settlement, or an option's expiry. */
@Service
@Transactional
class TradeLifecycle(
    private val repository: TradeRepository,
    private val store: TradeStore,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun requireTradeOperator(tradeId: UUID) {
        actorProvider.authorise(store.find(tradeId).legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
    }

    fun approve(tradeId: UUID): TradeView {
        val actor = actorProvider.current().userId
        val current = store.locked(tradeId)
        actorProvider.authorise(current.legalEntityId, Role.MIDDLE_OFFICE, Role.RISK)
        if (current.requiresRiskApproval) actorProvider.current().requireRole(Role.RISK)
        val next = TradeAggregate(current).approve(actor, clock.instant())
        store.save(next, actor, "approved")
        events.publishEvent(TradeApproved(next))
        store.openObligationsIfDue(next)
        // The other side of an intercompany trade may have been confirmed while this one waited for approval: the
        // confirmation reached nothing then, so it is taken over now.
        val linked = next.intercompanyLinkId?.let(store::find)?.takeIf { it.status == TradeStatus.CONFIRMED && it.confirmationRef != null }
        if (linked != null) return confirmAs(next.id, ConfirmTrade(linked.confirmationRef!!, "INTERCOMPANY", linked.confirmedAt), actor)
        return next
    }

    fun reject(tradeId: UUID, reason: String): TradeView {
        val current = store.locked(tradeId)
        actorProvider.authorise(current.legalEntityId, Role.MIDDLE_OFFICE, Role.RISK)
        val next = TradeAggregate(current).reject()
        store.save(next, actorProvider.current().userId, "rejected: $reason")
        return next
    }

    fun confirm(tradeId: UUID, command: ConfirmTrade): TradeView {
        actorProvider.authorise(store.find(tradeId).legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        return confirmAs(tradeId, command, actorProvider.current().userId)
    }

    /** Confirmation on behalf of [actor], e.g. `system` when the counterparty's confirmation matched ours. */
    fun confirmAs(tradeId: UUID, command: ConfirmTrade, actor: String): TradeView {
        val current = store.locked(tradeId)
        val next = TradeAggregate(current).confirm(command.confirmationRef, command.confirmedAt ?: clock.instant())
        store.save(next, actor, "confirmed via ${command.method}")
        events.publishEvent(TradeConfirmed(next))
        if (!current.obligationsOpen) store.obligationsOf(next).forEach { events.publishEvent(DeliveryObligationOpened(it)) }
        next.intercompanyLinkId?.let { linkedId ->
            val linked = store.locked(linkedId)
            if (linked.status == TradeStatus.APPROVED) confirmAs(linkedId, command, actor)
        }
        return next
    }

    fun recordDelivery(tradeId: UUID, command: RecordDelivery): TradeView {
        val actor = actorProvider.current().userId
        val current = store.locked(tradeId)
        if (command.idempotencyKey != null && repository.deliveryRecorded(command.idempotencyKey)) return current
        val (next, schedule) = TradeAggregate(current).recordDelivery(command.scheduleId, command.quantity)
        store.save(next, actor, "delivery ${command.quantity} on ${command.deliveredOn}")
        val deliveryId = repository.appendDeliveryRecord(
            command.scheduleId,
            command.quantity,
            command.deliveredOn,
            command.reference,
            command.idempotencyKey,
            actor,
        )
        val obligation = store.obligationsOf(next).first { it.scheduleId == schedule.id }
        events.publishEvent(DeliveryRecorded(next, deliveryId, obligation, command.quantity, command.deliveredOn, command.reference))
        if (schedule.status == DeliveryStatus.DELIVERED) {
            events.publishEvent(DeliveryObligationClosed(obligation, "delivered"))
        } else {
            events.publishEvent(DeliveryObligationChanged(obligation))
        }
        return next
    }

    /** Records an option leg's outcome at expiry; the trade closes once every leg is decided. */
    fun recordExpiry(tradeId: UUID, legNo: Int, expiry: OptionExpiryView, future: TradeView?): TradeView {
        val next = TradeAggregate(store.locked(tradeId)).expire(legNo, expiry)
        store.save(next, SYSTEM_USER, "leg $legNo ${expiry.outcome.name.lowercase()} at ${expiry.settlementPrice.toPlainString()}")
        events.publishEvent(OptionExpired(next, legNo, expiry, future))
        return next
    }

    fun markSettled(tradeId: UUID): TradeView {
        val next = TradeAggregate(store.locked(tradeId)).markSettled()
        store.save(next, actorProvider.current().userId, "settled")
        events.publishEvent(TradeSettled(next))
        return next
    }
}
