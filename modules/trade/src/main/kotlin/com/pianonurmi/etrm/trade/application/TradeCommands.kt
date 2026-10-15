package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeView
import org.springframework.stereotype.Service
import java.util.UUID

/** The module's command API over capture, amendment and lifecycle, each of which runs its own transaction. */
@Service
class TradeCommands(
    private val capture: TradeCapture,
    private val amendment: TradeAmendment,
    private val lifecycle: TradeLifecycle,
) : TradeCommand {
    override fun capture(command: CaptureTrade): TradeView = capture.capture(command)

    override fun requireTradeOperator(tradeId: UUID) = lifecycle.requireTradeOperator(tradeId)

    override fun amend(tradeId: UUID, command: AmendTrade): TradeView = amendment.amend(tradeId, command)

    override fun approve(tradeId: UUID): TradeView = lifecycle.approve(tradeId)

    override fun reject(tradeId: UUID, reason: String): TradeView = lifecycle.reject(tradeId, reason)

    override fun confirm(tradeId: UUID, command: ConfirmTrade): TradeView = lifecycle.confirm(tradeId, command)

    override fun cancel(tradeId: UUID, reason: String): TradeView = amendment.cancel(tradeId, reason)

    override fun recordDelivery(tradeId: UUID, command: RecordDelivery): TradeView = lifecycle.recordDelivery(tradeId, command)

    override fun markSettled(tradeId: UUID): TradeView = lifecycle.markSettled(tradeId)
}
