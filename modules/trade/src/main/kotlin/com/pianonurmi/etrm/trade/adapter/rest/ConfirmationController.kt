package com.pianonurmi.etrm.trade.adapter.rest

import com.pianonurmi.etrm.trade.api.ConfirmationCommand
import com.pianonurmi.etrm.trade.api.ConfirmationQuery
import com.pianonurmi.etrm.trade.api.ConfirmationView
import com.pianonurmi.etrm.trade.api.ReceiveConfirmation
import com.pianonurmi.etrm.trade.api.TradeCommand
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Our confirmations (JSON terms, PDF under /api/v1/documents) and matching of the counterparty's. */
@RestController
@Tag(name = "Trade confirmations", description = "Issue our confirmation and match the counterparty's")
class ConfirmationController(
    private val command: ConfirmationCommand,
    private val query: ConfirmationQuery,
    private val trades: TradeCommand,
) {
    @GetMapping("/api/v1/trades/{tradeId}/confirmations")
    fun forTrade(
        @PathVariable tradeId: UUID,
    ): List<ConfirmationView> = query.forTrade(tradeId)

    /** Issues (or returns) our confirmation of the trade's current terms. */
    @PostMapping("/api/v1/trades/{tradeId}/confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(
        @PathVariable tradeId: UUID,
    ): ConfirmationView = trades.requireTradeOperator(tradeId).let { command.issue(tradeId) }

    @GetMapping("/api/v1/confirmations/{id}")
    fun confirmation(
        @PathVariable id: UUID,
    ): ConfirmationView = query.confirmation(id) ?: throw com.pianonurmi.etrm.platform.errors.NotFoundException("TradeConfirmation", id)

    /** The counterparty's confirmation as JSON (also used for manual entry with source MANUAL). */
    @PostMapping("/api/v1/confirmations/counterparty")
    @ResponseStatus(HttpStatus.CREATED)
    fun receive(
        @RequestBody body: ReceiveConfirmation,
    ): ConfirmationView = command.receive(body)

    /** The counterparty's confirmations as CSV: one line per delivery, grouped by confirmation_ref. */
    @PostMapping("/api/v1/confirmations/counterparty/csv")
    @ResponseStatus(HttpStatus.CREATED)
    fun receiveCsv(
        @RequestParam counterpartyId: UUID,
        @RequestBody content: String,
    ): List<ConfirmationView> = command.receiveCsv(counterpartyId, content)
}
