package com.pianonurmi.etrm.allocation.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.BucketMatchKind
import com.pianonurmi.etrm.product.api.criteria.Criterion
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ObligationKind(val defaultPriority: Int) {
    SALE(1),
    INTERCOMPANY(2),
    DISCLOSURE(3),
    OWN_CONSUMPTION(4),
}

enum class ObligationStatus { OPEN, FULFILLED, CLOSED }

/** Per-obligation side constraints on how the quantity may be composed. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(AllocationConstraint.MaxSharePerDevice::class, name = "maxSharePerDevice"),
    JsonSubTypes.Type(AllocationConstraint.MinShare::class, name = "minShare"),
)
sealed interface AllocationConstraint {
    /** No single production device may supply more than [percent] of the obligation. */
    data class MaxSharePerDevice(val percent: Int) : AllocationConstraint {
        init {
            require(percent in 1..100) { "percent must be 1..100" }
        }
    }

    /** At least [percent] of the obligation must come from lots matching [criterion]. */
    data class MinShare(val criterion: Criterion, val percent: Int) : AllocationConstraint {
        init {
            require(percent in 1..100) { "percent must be 1..100" }
        }
    }
}

data class ObligationView(
    val id: UUID,
    val kind: ObligationKind,
    val holderEntityId: UUID,
    val bookId: UUID?,
    val counterpartyId: UUID?,
    val tradeRef: String?,
    val sourceRef: UUID?,
    val criterion: Criterion,
    val criteriaSummary: String,
    val quantityMwh: Long,
    val fulfilledMwh: Long,
    val deliveryWindow: LocalDateRange,
    val deliveryDate: LocalDate,
    val targetRegistryCode: String?,
    val targetAccountRef: String?,
    val cancellation: CancellationDetails?,
    val priorityClass: Int,
    val constraints: List<AllocationConstraint>,
    val status: ObligationStatus,
    val version: Int,
    /** The product bucket of the criterion and how it was found; null when no bucket contains it (or before it was recorded). */
    val bucketCode: String? = null,
    val bucketMatch: BucketMatchKind? = null,
    /** The production year the delivery is marked at (see `VintageYears`); null on obligations from before it was recorded. */
    val vintageYear: Int? = null,
) {
    val remainingMwh: Long get() = quantityMwh - fulfilledMwh
}

data class OpenObligation(
    val kind: ObligationKind,
    val holderEntityId: UUID,
    val bookId: UUID? = null,
    val counterpartyId: UUID? = null,
    val tradeRef: String? = null,
    /** Trade delivery schedule id or consumption record id; makes upserts idempotent. */
    val sourceRef: UUID? = null,
    val criterion: Criterion,
    val quantityMwh: Long,
    val deliveryWindow: LocalDateRange,
    val deliveryDate: LocalDate,
    val targetRegistryCode: String? = null,
    val targetAccountRef: String? = null,
    val cancellation: CancellationDetails? = null,
    val priorityClass: Int? = null,
    val constraints: List<AllocationConstraint> = emptyList(),
    /** The product bucket when the caller knows it (obligations from trades); otherwise it is looked up from the criterion. */
    val bucketCode: String? = null,
    val bucketMatch: BucketMatchKind? = null,
)

interface ObligationCommand {
    /** Creates or updates (by sourceRef) an obligation. */
    fun open(command: OpenObligation): ObligationView

    fun close(obligationId: UUID, reason: String): ObligationView

    fun setConstraints(obligationId: UUID, constraints: List<AllocationConstraint>): ObligationView
}

interface ObligationQuery {
    fun obligation(id: UUID): ObligationView?

    fun requireObligation(id: UUID): ObligationView

    fun bySource(sourceRef: UUID): ObligationView?

    fun open(holderEntityId: UUID): List<ObligationView>
}

enum class SolverKind { GREEDY, EXACT }

enum class PlanStatus {
    PROPOSED,
    APPROVED,
    RESERVED,
    INSTRUCTED,
    CONFIRMED,
    PARTIALLY_FAILED,
    CLOSED,
    REJECTED,
    EXPIRED,
    STALE,
    CANCELLED,
    ;

    val active: Boolean get() = this in setOf(PROPOSED, APPROVED, RESERVED, INSTRUCTED)
}

enum class LineStatus { PROPOSED, RESERVED, INSTRUCTED, CONFIRMED, FAILED, CANCELLED }

/**
 * What delivering one MWh of a lot class to an obligation costs. Since ADR 0035 ([unit] `EUR_PER_MWH`):
 * - [opportunityCost]: the lot's value, the best mark among the buckets it qualifies for ([bucketCode]);
 * - [urgencyBonus]: the expiry haircut taken off that value;
 * - [transferCost]: the registry transfer fee;
 * - [total]: value − haircut + fee, what the solvers minimise;
 * - [scarcity]: demand / eligible supply of the competing obligations, a tie-break only;
 * - [saleMarkEur] and [giveAwayPerMwh]: the mark of the sale's own bucket and the premium given away (value − that).
 *
 * Plans from before ADR 0035 carry unit `SCORE`: a mixed score without the EUR fields.
 */
data class ScoreBreakdown(
    val opportunityCost: BigDecimal,
    val transferCost: BigDecimal,
    val scarcity: BigDecimal,
    val urgencyBonus: BigDecimal,
    val total: BigDecimal,
    val bucketCode: String?,
    val saleMarkEur: BigDecimal? = null,
    val giveAwayPerMwh: BigDecimal? = null,
    val unit: String = SCORE,
    /** False when no bucket the lot qualifies for has a price: its value counts as zero. */
    val priced: Boolean = true,
) {
    companion object {
        const val SCORE = "SCORE"
        const val EUR_PER_MWH = "EUR_PER_MWH"
    }
}

data class AllocationLineView(
    val id: UUID,
    val lineNo: Int,
    val obligationId: UUID,
    val lotId: UUID,
    val lotClassKey: String,
    val quantityMwh: Long,
    val score: BigDecimal,
    val breakdown: ScoreBreakdown,
    val status: LineStatus,
    val reservedLotId: UUID?,
    val instructionId: UUID?,
    val externalRef: String?,
    val failureReason: String?,
)

data class ObligationCoverage(
    val obligationId: UUID,
    val demandMwh: Long,
    val coveredMwh: Long,
    val eligibleSupplyMwh: Long,
    val eligibleClassCount: Int,
) {
    val shortfallMwh: Long get() = demandMwh - coveredMwh
}

/** Obligations that are jointly short: together they need more than the lots any of them can use. */
data class ShortageGroup(val obligationIds: List<UUID>, val demandMwh: Long, val eligibleSupplyMwh: Long, val shortfallMwh: Long)

data class BucketSurplus(val bucketCode: String, val productionYear: Int, val surplusMwh: Long)

data class CoverageReport(
    val holderEntityId: UUID,
    val snapshotSeq: Long,
    val asOf: Instant,
    val totalDemandMwh: Long,
    val totalCoveredMwh: Long,
    val obligations: List<ObligationCoverage>,
    val shortageGroups: List<ShortageGroup>,
    val surplusByBucket: List<BucketSurplus>,
) {
    val totalShortfallMwh: Long get() = totalDemandMwh - totalCoveredMwh
}

data class AllocationPlanView(
    val id: UUID,
    val idempotencyKey: String,
    val holderEntityId: UUID,
    val snapshotSeq: Long,
    val solver: SolverKind,
    val status: PlanStatus,
    val proposedBy: String,
    val proposedAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val decidedBy: String?,
    val decisionReason: String?,
    val reservationExpiresAt: Instant,
    val objective: Map<String, Any?>,
    val coverage: CoverageReport?,
    val lines: List<AllocationLineView>,
    val version: Int,
    /** The plan in EUR (ADR 0035); null for plans from before it. */
    val value: PlanValue? = null,
    val pricing: PlanPricing? = null,
) {
    val allocatedMwh: Long get() = lines.filter { it.status != LineStatus.CANCELLED && it.status != LineStatus.FAILED }.sumOf { it.quantityMwh }
}

data class ProposeAllocation(
    val holderEntityId: UUID,
    /** Null allocates every open obligation of the entity. */
    val obligationIds: Set<UUID>? = null,
    val solver: SolverKind = SolverKind.GREEDY,
    val idempotencyKey: String,
    val softReservationHours: Int = 48,
    /** Delivery date used to bind criteria for coverage; defaults to each obligation's own date. */
    val asOf: LocalDate? = null,
)

interface AllocationCommand {
    fun propose(command: ProposeAllocation): AllocationPlanView

    /** The plan [propose] would make now, without storing it or reserving anything (the idempotency key is ignored). */
    fun whatIf(command: ProposeAllocation): AllocationWhatIf

    /** Four-eyes approval: hard-reserves lots, submits registry instructions. */
    fun approve(planId: UUID): AllocationPlanView

    fun reject(planId: UUID, reason: String): AllocationPlanView

    fun cancel(planId: UUID, reason: String): AllocationPlanView

    /** Expires PROPOSED plans whose soft reservation lapsed; returns the count. */
    fun expireStalePlans(now: Instant): Int
}

/** A computed but unsaved allocation: what proposing now would give. */
data class AllocationWhatIf(
    val holderEntityId: UUID,
    val snapshotSeq: Long,
    val solver: SolverKind,
    val computedAt: Instant,
    val objective: Map<String, Any?>,
    val coverage: CoverageReport,
    val lines: List<AllocationLineView>,
    val value: PlanValue? = null,
    val pricing: PlanPricing? = null,
)

/**
 * A plan in EUR (ADR 0035), from its lines' score breakdowns:
 * - [deliveredValueEur]: what the delivered certificates are worth at the best price each qualifies for;
 * - [expiryHaircutEur]: the part of that value lost to near expiry (those lots lapse unless delivered);
 * - [transferFeesEur]: registry transfer fees;
 * - [totalCostEur] = delivered value − haircut + fees, what the solver minimised;
 * - [giveAwayEur]: the premium handed over where a certificate worth more than the sale's own product delivers it
 *   (value − the sale bucket's mark, summed); negative when cheaper certificates deliver;
 * - [unpricedMwh]: delivered MWh whose lots have no priced bucket (valued at zero).
 */
data class PlanValue(
    val currency: String,
    val totalCostEur: BigDecimal,
    val deliveredValueEur: BigDecimal,
    val expiryHaircutEur: BigDecimal,
    val transferFeesEur: BigDecimal,
    val giveAwayEur: BigDecimal,
    val unpricedMwh: Long,
    val byObligation: List<ObligationValue>,
)

/** One obligation's share of a [PlanValue]. [giveAwayEur] is null when the sale has no priced bucket. */
data class ObligationValue(
    val obligationId: UUID,
    val saleBucketCode: String?,
    val saleMarkEur: BigDecimal?,
    val mwh: Long,
    val deliveredValueEur: BigDecimal,
    val averageDeliveredMarkEur: BigDecimal?,
    val giveAwayEur: BigDecimal?,
)

enum class PricingStatus {
    /** The pricing LP was solved to optimality; its duals are the shadow prices. */
    OPTIMAL,

    /** Not priced: no OR-Tools library, the problem is above the size limit, or nothing was allocated. */
    SKIPPED,

    /** The LP was not solved (time limit or numerical trouble). */
    FAILED,
}

/**
 * Shadow prices of a plan (ADR 0035): the duals of a linear program that delivers each obligation's planned coverage
 * at the lowest cost, under the same supply, device caps and minimum shares. They are *indicative*: computed in
 * floating point, not unique when the LP is degenerate, and never booked.
 *
 * - [lpCostEur]: the lowest cost of the plan's coverage; [gapEur] = plan cost − LP cost is what a better choice of
 *   lots could still save (zero for an optimal exact plan);
 * - per obligation, the marginal cost: what one more MWh delivered to it would cost, rerouting included;
 * - per bucket and production year, the scarcity rent: how much the plan's cost would fall with one more MWh of that
 *   supply.
 */
data class PlanPricing(
    val status: PricingStatus,
    val reason: String? = null,
    val currency: String = "EUR",
    val lpCostEur: BigDecimal? = null,
    val planCostEur: BigDecimal? = null,
    val gapEur: BigDecimal? = null,
    val obligations: List<ObligationShadowPrice> = emptyList(),
    val supply: List<SupplyShadowPrice> = emptyList(),
)

/** [short]: the obligation is not fully covered, so its marginal MWh may not be available at that cost or at all. */
data class ObligationShadowPrice(val obligationId: UUID, val coveredMwh: Long, val short: Boolean, val marginalCostEur: BigDecimal)

/** Scarcity rent of supply in one bucket and production year: quantity-weighted average and highest over its classes. */
data class SupplyShadowPrice(
    val bucketCode: String,
    val productionYear: Int,
    val supplyMwh: Long,
    val usedMwh: Long,
    val averageRentEur: BigDecimal,
    val maxRentEur: BigDecimal,
)

interface AllocationQuery {
    fun plan(id: UUID): AllocationPlanView?

    fun requirePlan(id: UUID): AllocationPlanView

    fun plans(holderEntityId: UUID, activeOnly: Boolean = true): List<AllocationPlanView>

    /** Coverage analysis of open obligations against live inventory; commits nothing. */
    fun coverage(holderEntityId: UUID, obligationIds: Set<UUID>? = null): CoverageReport
}

// --------------------------------------------------------------------------------------------------------------
// Events
// --------------------------------------------------------------------------------------------------------------

data class ObligationOpened(val obligation: ObligationView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class ObligationChanged(val obligation: ObligationView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class ObligationClosed(val obligation: ObligationView, val reason: String, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

sealed interface AllocationEvent : DomainEvent {
    val plan: AllocationPlanView
}

data class AllocationProposed(override val plan: AllocationPlanView, val metadata: EventMetadata = EventMetadata()) : AllocationEvent, DomainEvent by metadata

data class AllocationApproved(override val plan: AllocationPlanView, val metadata: EventMetadata = EventMetadata()) : AllocationEvent, DomainEvent by metadata

data class AllocationRejected(override val plan: AllocationPlanView, val reason: String, val metadata: EventMetadata = EventMetadata()) :
    AllocationEvent, DomainEvent by metadata

data class AllocationStale(override val plan: AllocationPlanView, val reason: String, val metadata: EventMetadata = EventMetadata()) :
    AllocationEvent, DomainEvent by metadata

data class AllocationLineConfirmed(
    override val plan: AllocationPlanView,
    val line: AllocationLineView,
    val obligation: ObligationView,
    val metadata: EventMetadata = EventMetadata(),
) : AllocationEvent, DomainEvent by metadata

data class AllocationCompleted(override val plan: AllocationPlanView, val metadata: EventMetadata = EventMetadata()) : AllocationEvent, DomainEvent by metadata
