package com.pianonurmi.etrm.trade.adapter.rest

import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradePage
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeSearch
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeVersionView
import com.pianonurmi.etrm.trade.api.TradeView
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/trades")
@Tag(name = "Trades", description = "Deal capture, approval, confirmation, delivery and settlement")
class TradeController(private val command: TradeCommand, private val query: TradeQuery) {
    @GetMapping
    fun trades(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(required = false) bookId: UUID?,
        @RequestParam(required = false) counterpartyId: UUID?,
        @RequestParam(required = false) status: Set<TradeStatus>?,
        @RequestParam(required = false) productFamily: ProductFamily?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(defaultValue = "200") limit: Int,
    ): List<TradeView> = query.visibleTrades(TradeFilter(legalEntityId, bookId, counterpartyId, status, productFamily, from, to, limit))

    /** Blotter search with an opaque cursor; `q` matches trade number, external reference or UTI. */
    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) legalEntityId: UUID?,
        @RequestParam(required = false) bookId: UUID?,
        @RequestParam(required = false) counterpartyId: UUID?,
        @RequestParam(required = false) status: Set<TradeStatus>?,
        @RequestParam(required = false) productFamily: ProductFamily?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): TradePage = query.searchTrades(TradeSearch(legalEntityId, bookId, counterpartyId, status, productFamily, from, to, q, limit, cursor))

    @GetMapping("/{id}")
    fun trade(
        @PathVariable id: UUID,
    ): TradeView = query.requireVisibleTrade(id)

    @GetMapping("/{id}/versions")
    fun versions(
        @PathVariable id: UUID,
    ): List<TradeVersionView> = query.requireVisibleTrade(id).let { query.versions(id) }

    @GetMapping("/obligations")
    fun obligations(
        @RequestParam legalEntityId: UUID,
        @RequestParam(required = false) side: Side?,
        @RequestParam(required = false) productFamily: ProductFamily?,
    ) =
        query.openObligations(legalEntityId, side, productFamily)

    @GetMapping("/obligations/{scheduleId}")
    fun obligation(
        @PathVariable scheduleId: UUID,
    ): DeliveryObligationView? = query.obligation(scheduleId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun capture(
        @Valid @RequestBody body: CaptureTrade,
    ): TradeView = command.capture(body)

    @PutMapping("/{id}")
    fun amend(
        @PathVariable id: UUID,
        @Valid @RequestBody body: AmendTrade,
    ): TradeView = command.amend(id, body)

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
    ): TradeView = command.approve(id)

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): TradeView = command.reject(id, body.reason)

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable id: UUID,
        @RequestBody body: ConfirmTrade,
    ): TradeView = command.confirm(id, body)

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): TradeView = command.cancel(id, body.reason)

    @PostMapping("/{id}/deliveries")
    fun recordDelivery(
        @PathVariable id: UUID,
        @RequestBody body: RecordDelivery,
    ): TradeView = command.requireTradeOperator(id).let { command.recordDelivery(id, body) }

    @PostMapping("/{id}/settle")
    fun settle(
        @PathVariable id: UUID,
    ): TradeView = command.requireTradeOperator(id).let { command.markSettled(id) }
}

data class ReasonRequest(val reason: String)
