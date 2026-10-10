package com.pianonurmi.etrm.trade.domain

import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.trade.api.ConfirmationView
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradePage
import com.pianonurmi.etrm.trade.api.TradeSearch
import com.pianonurmi.etrm.trade.api.TradeVersionView
import com.pianonurmi.etrm.trade.api.TradeView
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

interface TradeRepository {
    fun nextTradeNumber(year: Int): String

    fun find(id: UUID): TradeView?

    fun lock(id: UUID): TradeView?

    fun byNumber(tradeNumber: String): TradeView?

    fun list(filter: TradeFilter): List<TradeView>

    /** Blotter rows in one query, keyset-paged on trade date and trade number. */
    fun search(search: TradeSearch): TradePage

    /** Inserts the trade with version 1 and its first version row. */
    fun insert(trade: TradeView, recordedBy: String, reason: String?)

    /** Stores the new version (header, legs, deliveries) and closes the previous version row; optimistic on version. */
    fun saveVersion(trade: TradeView, recordedBy: String, reason: String?): Boolean

    fun versions(tradeId: UUID): List<TradeVersionView>

    /** Appends a delivery record and returns its id. */
    fun appendDeliveryRecord(
        scheduleId: UUID,
        quantity: BigDecimal,
        deliveredOn: LocalDate,
        reference: String?,
        idempotencyKey: String?,
        recordedBy: String,
    ): UUID

    /** True when a delivery with this idempotency key has already been recorded. */
    fun deliveryRecorded(idempotencyKey: String): Boolean

    fun openObligations(legalEntityId: UUID, side: Side?, productFamily: ProductFamily?): List<DeliveryObligationView>

    fun obligation(scheduleId: UUID): DeliveryObligationView?
}

interface ConfirmationRepository {
    fun insert(view: ConfirmationView)

    fun update(view: ConfirmationView)

    fun find(id: UUID, forUpdate: Boolean = false): ConfirmationView?

    fun forTrade(tradeId: UUID): List<ConfirmationView>

    fun theirs(tradeId: UUID, reference: String): ConfirmationView?
}
