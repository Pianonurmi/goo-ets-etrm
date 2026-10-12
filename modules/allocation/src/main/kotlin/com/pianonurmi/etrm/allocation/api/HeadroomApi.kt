package com.pianonurmi.etrm.allocation.api

import com.pianonurmi.etrm.product.api.criteria.Criterion
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A sale being considered: what it needs, by when and where to (ADR 0036). [ref] names it in the answer. */
data class HeadroomDemand(
    val criterion: Criterion,
    val quantityMwh: Long,
    val deliveryDate: LocalDate,
    val targetRegistryCode: String? = null,
    val ref: String? = null,
)

/**
 * How much of [demands] the entity can still sell from what it holds. [excludeSourceRefs]: obligations to leave out
 * of the existing demand, e.g. the delivery schedules of the trade being amended, which the new demands replace.
 * [tier]: the supply counted (ADR 0038); beyond the held tier, purchases and expected issuance (P50) count from the
 * day they are available.
 */
data class HeadroomCheck(
    val holderEntityId: UUID,
    val demands: List<HeadroomDemand>,
    val excludeSourceRefs: Set<UUID> = emptySet(),
    val tier: SupplyTier = SupplyTier.HELD,
)

/**
 * Available to sell (ADR 0036): the most each demand could receive from held certificates without taking anything
 * from existing obligations, rerouting their supply where another certificate serves them as well.
 * - [headroomMwh] per demand on its own, and [jointMwh] for all of them together (they may compete for the same lots);
 * - [freeMwh]: eligible supply nobody uses, before rerouting; headroom is at least that;
 * - [binding]: when a demand does not fit, the supply it would need more of, by bucket and production year.
 *
 * Side constraints of existing obligations (device caps, minimum shares) are not part of the flow, so the figures are
 * an upper bound.
 */
data class HeadroomResult(
    val holderEntityId: UUID,
    val tier: SupplyTier,
    val stamp: String,
    val computedAt: Instant,
    val demands: List<DemandHeadroom>,
    val requestedMwh: Long,
    /** Null when there are more demands than one joint check holds. */
    val jointMwh: Long?,
    val fits: Boolean,
)

data class DemandHeadroom(
    val ref: String?,
    val requestedMwh: Long,
    val headroomMwh: Long,
    val freeMwh: Long,
    val fits: Boolean,
    val binding: List<BindingSupply>,
)

/** Exhausted supply a short demand could use: more of it would raise the headroom. */
data class BindingSupply(val bucketCode: String, val productionYear: Int, val eligibleMwh: Long)

/**
 * Available to sell per product bucket and vintage, for delivery at each of the next month-ends. A cell is what one
 * more sale of that bucket and vintage, delivered on that date, could receive; cells do not add up (they share supply).
 */
data class HeadroomTable(
    val holderEntityId: UUID,
    val tier: SupplyTier,
    val stamp: String,
    val computedAt: Instant,
    val deliveryDates: List<LocalDate>,
    val rows: List<HeadroomRow>,
)

/**
 * [heldMwh]: held certificates of the bucket and vintage; [futureMwh]: purchases and expected issuance (P50) of it
 * counted at the table's tier; [headroomMwh]: one figure per delivery date.
 */
data class HeadroomRow(val bucketCode: String, val vintageYear: Int, val heldMwh: Long, val futureMwh: Long, val headroomMwh: List<Long>)

interface HeadroomQuery {
    fun check(command: HeadroomCheck): HeadroomResult

    fun table(holderEntityId: UUID, tier: SupplyTier = SupplyTier.HELD): HeadroomTable

    /**
     * [check] from figures already prepared, without building them: null when they are missing or out of date (a
     * build then starts in the background). For pre-trade checks, which must never hold up a capture.
     */
    fun checkIfReady(command: HeadroomCheck): HeadroomResult?
}
