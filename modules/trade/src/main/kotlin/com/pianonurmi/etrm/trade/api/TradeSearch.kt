package com.pianonurmi.etrm.trade.api

import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductFamily
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Blotter search; results are newest first (trade date, then trade number) and paged with an opaque cursor. */
data class TradeSearch(
    val legalEntityId: UUID? = null,
    val bookId: UUID? = null,
    val counterpartyId: UUID? = null,
    val statuses: Set<TradeStatus>? = null,
    val productFamily: ProductFamily? = null,
    val tradeDateFrom: LocalDate? = null,
    val tradeDateTo: LocalDate? = null,
    /** Matches trade number, external reference or UTI, case-insensitively, anywhere in the text. */
    val q: String? = null,
    val limit: Int = 100,
    val cursor: String? = null,
    /** Restricts the search to these legal entities; set by the service from the user's scope. */
    val legalEntityIds: Set<UUID>? = null,
)

/** One blotter row: the trade header and its first leg, with the number of legs and the notional of all of them. */
data class TradeSummaryView(
    val id: UUID,
    val tradeNumber: String,
    val legalEntityId: UUID,
    val bookId: UUID,
    val counterpartyId: UUID,
    val productFamily: ProductFamily,
    val instrumentType: InstrumentType,
    val tradeDate: LocalDate,
    val status: TradeStatus,
    val trader: String,
    val externalRef: String?,
    val uti: String?,
    val version: Int,
    val createdAt: Instant,
    val side: Side,
    val product: String,
    val quantity: BigDecimal,
    val unit: String,
    val price: BigDecimal,
    val priceCurrency: String,
    val legCount: Int,
    /** Sum of quantity times price over the legs, in the first leg's currency. */
    val notional: BigDecimal,
)

data class TradePage(val items: List<TradeSummaryView>, val nextCursor: String?)
