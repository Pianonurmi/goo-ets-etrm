package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.trade.api.OptionExpired
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Option premiums in the books (ADR 0047): paid or received in full through the clearing member's cash when the
 * option is confirmed. A confirmation after an amendment posts the change from the version confirmed before, a
 * cancellation reverses what was posted, and the premium is released to the realised result at expiry.
 */
@Component
class OptionPostings(private val ledger: LedgerService, private val trades: TradeQuery, private val unposted: UnpostedEvents? = null) : PostingReplay {
    @EventListener
    fun on(event: TradeConfirmed) = guard(event, event.trade.legalEntityId) { handle(event) }

    private fun handle(event: TradeConfirmed) {
        val trade = event.trade
        if (trade.legs.none { it.option != null }) return
        post(trade, event.eventId, premiums(trade) - premiums(confirmedBefore(trade)), "Option ${trade.tradeNumber} premium")
    }

    @EventListener
    fun on(event: TradeCancelled) = guard(event, event.trade.legalEntityId) { handle(event) }

    private fun handle(event: TradeCancelled) {
        val trade = event.trade
        if (trade.legs.none { it.option != null }) return
        val posted = premiums(confirmedBefore(trade))
        post(trade, event.eventId, Premiums.ZERO - posted, "Option ${trade.tradeNumber} cancelled")
    }

    @EventListener
    fun on(event: OptionExpired) = guard(event, event.trade.legalEntityId) { handle(event) }

    private fun handle(event: OptionExpired) {
        val trade = event.trade
        val leg = trade.legs.first { it.legNo == event.legNo }
        val amount = premium(leg)
        ledger.post(
            PostingFacts(
                legalEntityId = trade.legalEntityId,
                eventType = EXPIRY_EVENT,
                eventId = event.eventId,
                postingDate = event.expiry.decidedOn,
                currency = leg.priceCurrency.currencyCode,
                sourceType = "TRADE",
                sourceRef = trade.tradeNumber,
                description = "Option ${trade.tradeNumber} leg ${leg.legNo} ${event.expiry.outcome.name.lowercase()}",
                amounts = mapOf(
                    "releasePaid" to if (leg.side == Side.BUY) amount else BigDecimal.ZERO,
                    "releaseReceived" to if (leg.side == Side.SELL) amount else BigDecimal.ZERO,
                ),
                dimensions = dimensions(trade),
            ),
        )
    }

    /** The latest earlier version of the trade that was confirmed: what the books hold for it. */
    private fun confirmedBefore(trade: TradeView): TradeView? = trades.versions(trade.id)
        .filter { it.version < trade.version && it.trade.confirmedAt != null && it.status != TradeStatus.CANCELLED }
        .maxByOrNull { it.version }?.trade

    private fun post(trade: TradeView, eventId: UUID, change: Premiums, description: String) {
        if (change.paid.signum() == 0 && change.received.signum() == 0) return
        ledger.post(
            PostingFacts(
                legalEntityId = trade.legalEntityId,
                eventType = PREMIUM_EVENT,
                eventId = eventId,
                postingDate = trade.tradeDate,
                currency = trade.legs.first { it.option != null }.priceCurrency.currencyCode,
                sourceType = "TRADE",
                sourceRef = trade.tradeNumber,
                description = description,
                amounts = mapOf(
                    "paid" to change.paid.max(BigDecimal.ZERO),
                    "paidReversal" to change.paid.negate().max(BigDecimal.ZERO),
                    "received" to change.received.max(BigDecimal.ZERO),
                    "receivedReversal" to change.received.negate().max(BigDecimal.ZERO),
                ),
                dimensions = dimensions(trade),
            ),
        )
    }

    /** Premiums of the option legs bought and sold. */
    private data class Premiums(val paid: BigDecimal, val received: BigDecimal) {
        operator fun minus(other: Premiums) = Premiums(paid - other.paid, received - other.received)

        companion object {
            val ZERO = Premiums(BigDecimal.ZERO, BigDecimal.ZERO)
        }
    }

    private fun premiums(trade: TradeView?): Premiums {
        val legs = trade?.legs.orEmpty().filter { it.option != null }
        return Premiums(
            legs.filter { it.side == Side.BUY }.fold(BigDecimal.ZERO) { sum, leg -> sum + premium(leg) },
            legs.filter { it.side == Side.SELL }.fold(BigDecimal.ZERO) { sum, leg -> sum + premium(leg) },
        )
    }

    private fun premium(leg: TradeLegView): BigDecimal = (leg.quantity * leg.price).setScale(2, RoundingMode.HALF_EVEN)

    private fun dimensions(trade: TradeView): Map<String, String?> = mapOf(
        "trade" to trade.tradeNumber,
        "book" to trade.bookId.toString(),
        "counterparty" to trade.counterpartyId.toString(),
    )

    override fun replay(event: DomainEvent): Boolean {
        when (event) {
            is TradeConfirmed -> on(event)
            is TradeCancelled -> on(event)
            is OptionExpired -> on(event)
            else -> return false
        }
        return true
    }

    private fun guard(event: DomainEvent, legalEntityId: UUID?, body: () -> Unit) {
        unposted?.guard(event, legalEntityId, body = body) ?: body()
    }

    companion object {
        const val PREMIUM_EVENT = "OptionPremium"
        const val EXPIRY_EVENT = "OptionExpired"
    }
}
