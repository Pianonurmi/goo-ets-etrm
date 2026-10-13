package com.pianonurmi.etrm.allocation.api

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What an entity's held GoO book is worth once its open obligations are served from it (ADR 0039, management view):
 * each certificate at the best price it qualifies for, less what delivering the obligations costs on the
 * valuation-grade allocation (the most coverage, higher priority first, at the least cost):
 * - [giveAwayEur]: the premium handed over where a certificate worth more than the sale's own product delivers it;
 * - [transferFeesEur]: registry transfer fees to the target registries;
 * - [expiryHaircutEur]: value lost on certificates delivered close to their expiry.
 *
 * [netEur] = inventory value − give-away − transfer fees − expiry haircut. The trades' own MtM is added by valuation.
 * Holdings and obligations are the current ones; prices are those of [asOf]. Not booked: official MtM, the ledger and
 * credit stay on bucket curves.
 */
data class PortfolioValue(
    val holderEntityId: UUID,
    val asOf: LocalDate,
    val computedAt: Instant,
    val stamp: String,
    val currency: String,
    val heldMwh: Long,
    val inventoryValueEur: BigDecimal,
    val allocatedMwh: Long,
    val shortfallMwh: Long,
    val deliveredValueEur: BigDecimal,
    val giveAwayEur: BigDecimal,
    /** Delivered with certificates worth more than the sale's own product: the MWh behind [giveAwayEur]. */
    val giveAwayMwh: Long,
    val transferFeesEur: BigDecimal,
    val expiryHaircutEur: BigDecimal,
    val netEur: BigDecimal,
    /** Held certificates no bucket they qualify for has a price for: counted at zero. */
    val unpricedMwh: Long,
    val byBucket: List<InventoryBucketValue>,
    val byObligation: List<ObligationDeliveryValue>,
)

/** Held certificates by the bucket of their best price and production year, with what the allocation uses of them. */
data class InventoryBucketValue(
    val bucketCode: String?,
    val productionYear: Int,
    val heldMwh: Long,
    val valueEur: BigDecimal,
    val allocatedMwh: Long,
)

/**
 * One obligation on the valuation-grade allocation. [cheapestToDeliverEur] is the average value per MWh of the
 * certificates that deliver it (null when none do): what the sale actually costs the book, against its own
 * [saleMarkEur]. [sourceRef] is the delivery schedule for obligations from trades.
 */
data class ObligationDeliveryValue(
    val obligationId: UUID,
    val sourceRef: UUID?,
    val tradeRef: String?,
    val bucketCode: String?,
    val vintageYear: Int?,
    val demandMwh: Long,
    val allocatedMwh: Long,
    val cheapestToDeliverEur: BigDecimal?,
    val saleMarkEur: BigDecimal?,
    val giveAwayEur: BigDecimal?,
    val transferFeesEur: BigDecimal,
    /** MWh delivered with certificates worth more than the sale's own product (0 in values kept before it was added). */
    val giveAwayMwh: Long = 0,
)

/** Give-away handed over by plans approved in a period: the realised side of the give-away above. */
data class RealisedGiveAway(val holderEntityId: UUID, val from: LocalDate, val to: LocalDate, val totalEur: BigDecimal, val plans: List<PlanGiveAway>)

data class PlanGiveAway(val planId: UUID, val approvedAt: Instant, val mwh: Long, val giveAwayEur: BigDecimal)

/**
 * One sale of a plan approved in a period, as valued when the plan was made (ADR 0035): its delivered MWh, the value of
 * the certificates that delivered it, its own product's mark and the give-away. Plan-time figures: a line that fails
 * at the registry later is not taken out.
 */
data class GiveAwayLine(
    val planId: UUID,
    val approvedAt: Instant,
    val obligationId: UUID,
    val tradeRef: String?,
    val deliveryDate: LocalDate?,
    val saleBucketCode: String?,
    val vintageYear: Int?,
    val mwh: Long,
    val deliveredValueEur: BigDecimal,
    val saleMarkEur: BigDecimal?,
    val giveAwayEur: BigDecimal?,
)

interface PortfolioValueQuery {
    /** Null when the allocation cannot be solved here (the OR-Tools library is not available). */
    fun value(holderEntityId: UUID, asOf: LocalDate): PortfolioValue?

    /** Plans approved from [from] up to and excluding [to]. */
    fun realisedGiveAway(holderEntityId: UUID, from: LocalDate, to: LocalDate): RealisedGiveAway

    /**
     * The sales of the plans approved from [from] up to and excluding [to], one line each, by approval then delivery
     * date: what the give-away report lists. Readable by auditors too.
     */
    fun giveAwayLines(holderEntityId: UUID, from: LocalDate, to: LocalDate): List<GiveAwayLine>
}
