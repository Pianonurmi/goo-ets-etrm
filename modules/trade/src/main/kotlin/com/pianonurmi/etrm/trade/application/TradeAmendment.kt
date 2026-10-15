package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeAggregate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Amends and cancels trades; an intercompany trade's mirror follows, or the change is refused. */
@Service
@Transactional
class TradeAmendment(
    private val builder: TradeBuilder,
    private val mirror: IntercompanyMirror,
    private val store: TradeStore,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) {
    fun amend(tradeId: UUID, command: AmendTrade): TradeView {
        val actor = actorProvider.current().userId
        val current = store.locked(tradeId)
        actorProvider.authorise(current.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        if (current.version != command.expectedVersion) throw StaleVersionException("Trade", tradeId, command.expectedVersion, current.version)
        val next = TradeAggregate(current).amend(builder.amendedState(current, command, runChecks = true))
        // An intercompany trade and its mirror change together or not at all: the mirror is worked out first, so a
        // refusal leaves both as they were.
        val linked = current.intercompanyLinkId?.let(store::locked)
        val linkedNext = linked?.let { mirror.amended(current, next, it, command) }
        saveAmended(current, next, actor, command.reason)
        if (linked != null && linkedNext != null) saveAmended(linked, linkedNext, actor, "${command.reason} (follows ${current.tradeNumber})")
        return next
    }

    private fun saveAmended(current: TradeView, next: TradeView, actor: String, reason: String) {
        val wasFirm = current.obligationsOpen
        store.save(next, actor, reason)
        events.publishEvent(TradeAmended(next, current.version))
        if (wasFirm) store.obligationsOf(current).forEach { events.publishEvent(DeliveryObligationClosed(it, "amended")) }
    }

    fun cancel(tradeId: UUID, reason: String): TradeView {
        val current = store.locked(tradeId)
        actorProvider.authorise(current.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        val next = TradeAggregate(current).cancel()
        // The mirror of an intercompany trade is cancelled with it, or the cancellation is refused: one entity must not
        // keep the obligation, exposure and position the other has cancelled.
        val linked = current.intercompanyLinkId?.let(store::locked)?.takeIf { it.status != TradeStatus.CANCELLED }
        val linkedNext = linked?.let { mirror.cancelled(current, it) }
        val actor = actorProvider.current().userId
        saveCancelled(current, next, actor, reason)
        if (linked != null && linkedNext != null) saveCancelled(linked, linkedNext, actor, "$reason (follows ${current.tradeNumber})")
        return next
    }

    private fun saveCancelled(current: TradeView, next: TradeView, actor: String, reason: String) {
        store.save(next, actor, "cancelled: $reason")
        events.publishEvent(TradeCancelled(next, reason))
        if (current.obligationsOpen) {
            store.obligationsOf(current).filter { it.status != DeliveryStatus.DELIVERED }
                .forEach { events.publishEvent(DeliveryObligationClosed(it, "cancelled")) }
        }
    }
}
