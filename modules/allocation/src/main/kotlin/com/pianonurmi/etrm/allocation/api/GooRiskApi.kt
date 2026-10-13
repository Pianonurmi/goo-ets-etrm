package com.pianonurmi.etrm.allocation.api

import com.pianonurmi.etrm.trade.api.TradeView
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Held certificates of a bucket that no obligation uses (ADR 0040): the book's open exposure to that product. */
data class GooConcentration(val bucketCode: String, val freeMwh: Long, val markEur: BigDecimal?) {
    /** The free certificates at the bucket's mark for the current vintage; null when it has none. */
    val valueEur: BigDecimal? get() = markEur?.let { (it * BigDecimal(freeMwh)).setScale(2, java.math.RoundingMode.HALF_UP) }
}

/**
 * Sales delivered with certificates worth more than their own product, on the valuation-grade allocation (ADR 0039):
 * the MWh and the give-away of the sales that hand some over (a gain on another sale does not offset it).
 */
data class GooBasis(val mwh: Long, val giveAwayEur: BigDecimal)

/**
 * A GoO sale's deliveries served from the free certificates that qualify, cheapest first (ADR 0040, increment 10):
 * the give-away they would hand over if the trade were done. Per delivery, with the vintage it is marked at.
 */
data class SaleProjection(val deliveries: List<DeliveryGiveAway>) {
    val giveAwayMwh: Long get() = deliveries.sumOf { it.giveAwayMwh }
}

/**
 * One delivery of a projected sale. [coveredMwh] is what the free certificates can serve of [mwh]; [giveAwayMwh] is
 * the part served with certificates worth more than the sale's own product, and [giveAwayEur] what that premium comes
 * to (null when the sale's product has no mark). Only premium counts: cheaper certificates do not offset it.
 */
data class DeliveryGiveAway(
    val scheduleId: UUID,
    val deliveryDate: LocalDate,
    val vintageYear: Int,
    val bucketCode: String?,
    val mwh: Long,
    val coveredMwh: Long,
    val giveAwayMwh: Long,
    val giveAwayEur: BigDecimal?,
)

/**
 * GoO measures for risk limits (ADR 0040), on the entity's held book netted through the eligibility flow. For
 * measuring only: no role check, the caller (risk) decides who sees the figures. [productionYear] narrows a measure to
 * one vintage: certificates produced in that year, or sales marked at it.
 */
interface GooRiskQuery {
    /** Null when no active bucket has the code. The mark is that of [productionYear], or of the current year. */
    fun concentration(holderEntityId: UUID, bucketCode: String, productionYear: Int? = null): GooConcentration?

    /** Held certificates expiring within [horizonDays] that no obligation uses, optionally only those of a bucket. */
    fun unusedExpiringMwh(holderEntityId: UUID, horizonDays: Int, bucketCode: String? = null, productionYear: Int? = null): Long

    /** Null when the allocation cannot be solved here (OR-Tools not available). */
    fun basis(holderEntityId: UUID, productionYear: Int? = null): GooBasis?

    /** The vintage each delivery of a GoO trade is marked at, by schedule id (the delivery year when the criteria leave it open). */
    fun vintages(trade: TradeView): Map<UUID, Int>

    /**
     * What a GoO trade's sales would hand over, from the entity's prepared held figures; null while they are being
     * prepared (they are then prepared in the background). The trade's own schedules are left out of existing demand.
     */
    fun projectSale(trade: TradeView): SaleProjection?
}
