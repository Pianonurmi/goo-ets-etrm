package com.pianonurmi.etrm.allocation.api

import java.math.BigDecimal
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
 * GoO measures for risk limits (ADR 0040), on the entity's held book netted through the eligibility flow. For
 * measuring only: no role check, the caller (risk) decides who sees the figures.
 */
interface GooRiskQuery {
    /** Null when no active bucket has the code. */
    fun concentration(holderEntityId: UUID, bucketCode: String): GooConcentration?

    /** Held certificates expiring within [horizonDays] that no obligation uses, optionally only those of a bucket. */
    fun unusedExpiringMwh(holderEntityId: UUID, horizonDays: Int, bucketCode: String? = null): Long

    /** Null when the allocation cannot be solved here (OR-Tools not available). */
    fun basis(holderEntityId: UUID): GooBasis?
}
