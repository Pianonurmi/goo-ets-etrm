package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.domain.CommitmentMemo
import com.pianonurmi.etrm.ledger.domain.CommitmentMemoRepository
import com.pianonurmi.etrm.ledger.domain.MemoMovementKind
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeView
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Off-balance commitment memos per delivery schedule. A confirmation posts the value still to be delivered, each
 * delivery releases its share of what was posted (the last one the remainder), and an amendment or cancellation
 * reverses whatever is still open. Legs without a fixed price get no memo until an indicative price exists.
 */
@Component
@Transactional
class CommitmentMemos(private val ledger: LedgerService, private val memos: CommitmentMemoRepository, private val clock: Clock) {
    @EventListener
    fun on(event: TradeConfirmed) {
        val trade = event.trade
        trade.legs.filter { it.priceType == PriceType.FIXED }.forEach { leg ->
            val created = leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.map { schedule ->
                CommitmentMemo(
                    Ids.newId(),
                    trade.legalEntityId,
                    trade.id,
                    trade.tradeNumber,
                    trade.version,
                    schedule.id,
                    leg.side.name,
                    trade.productFamily.name,
                    leg.priceCurrency.currencyCode,
                    schedule.remainingQuantity,
                    (schedule.remainingQuantity * leg.price).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO,
                )
            }.filter { it.amount.signum() > 0 && memos.insert(it, event.eventId) }
            if (created.isEmpty()) return@forEach
            ledger.post(
                PostingFacts(
                    legalEntityId = trade.legalEntityId,
                    eventType = "TradeConfirmed",
                    eventId = UUID.nameUUIDFromBytes("${event.eventId}:${leg.legNo}".toByteArray()),
                    postingDate = trade.tradeDate,
                    currency = leg.priceCurrency.currencyCode,
                    sourceType = "TRADE",
                    sourceRef = trade.tradeNumber,
                    description = "Trade ${trade.tradeNumber} leg ${leg.legNo} confirmed",
                    amounts = mapOf("notional" to created.sumOf { it.amount }, "quantity" to leg.quantity),
                    attributes = mapOf("side" to leg.side.name, "productFamily" to trade.productFamily.name),
                    dimensions = dimensions(trade) + mapOf("bucket" to leg.bucketCode),
                ),
            )
        }
    }

    /** A delivery releases its share of the schedule's memo; the delivery completing the schedule releases the rest. */
    @EventListener
    fun on(event: DeliveryRecorded) {
        val o = event.obligation
        memos.openForSchedule(o.scheduleId).forEach { memo ->
            val share = if (o.remainingQuantity.signum() <= 0) {
                memo.open
            } else {
                (memo.amount * event.quantity).divide(memo.quantity, 2, RoundingMode.HALF_UP).min(memo.open)
            }
            if (share.signum() <= 0 || !memos.move(event.eventId, memo.id, MemoMovementKind.RELEASE, share)) return@forEach
            ledger.post(
                PostingFacts(
                    legalEntityId = memo.legalEntityId,
                    eventType = "DeliveryRecorded",
                    eventId = UUID.nameUUIDFromBytes("${event.eventId}:${memo.id}".toByteArray()),
                    postingDate = event.deliveredOn,
                    currency = memo.currency,
                    sourceType = "DELIVERY",
                    sourceRef = event.deliveryId.toString(),
                    description = "Delivered ${event.quantity.toPlainString()} on ${o.tradeNumber}",
                    amounts = mapOf("deliveredValue" to share),
                    attributes = mapOf("side" to memo.side, "productFamily" to memo.productFamily),
                    dimensions = mapOf("trade" to o.tradeNumber, "counterparty" to o.counterpartyId.toString()),
                ),
            )
        }
    }

    @EventListener
    fun on(event: TradeAmended) = reverseOpen(event.trade, event.eventId, "amended to version ${event.trade.version}")

    @EventListener
    fun on(event: TradeCancelled) = reverseOpen(event.trade, event.eventId, "cancelled: ${event.reason}")

    private fun reverseOpen(trade: TradeView, eventId: UUID, why: String) {
        memos.openForTrade(trade.id)
            .filter { memos.move(eventId, it.id, MemoMovementKind.REVERSAL, it.open) }
            .groupBy { it.side to it.currency }
            .forEach { (key, group) ->
                val (side, currency) = key
                ledger.post(
                    PostingFacts(
                        legalEntityId = trade.legalEntityId,
                        eventType = "CommitmentReversed",
                        eventId = UUID.nameUUIDFromBytes("$eventId:$side:$currency".toByteArray()),
                        postingDate = LocalDate.now(clock),
                        currency = currency,
                        sourceType = "TRADE",
                        sourceRef = trade.tradeNumber,
                        description = "Trade ${trade.tradeNumber} $why: open commitment reversed",
                        amounts = mapOf("openCommitment" to group.sumOf { it.open }),
                        attributes = mapOf("side" to side, "productFamily" to trade.productFamily.name),
                        dimensions = dimensions(trade),
                    ),
                )
            }
    }

    private fun dimensions(trade: TradeView): Map<String, String?> = mapOf(
        "trade" to trade.tradeNumber,
        "book" to trade.bookId.toString(),
        "counterparty" to trade.counterpartyId.toString(),
    )
}
