package com.pianonurmi.etrm.allocation.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.time.LocalDateRange
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

data class ScoreBreakdown(
    val opportunityCost: BigDecimal,
    val transferCost: BigDecimal,
    val scarcity: BigDecimal,
    val urgencyBonus: BigDecimal,
    val total: BigDecimal,
    val bucketCode: String?,
)

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

    /** Four-eyes approval: hard-reserves lots, submits registry instructions. */
    fun approve(planId: UUID): AllocationPlanView

    fun reject(planId: UUID, reason: String): AllocationPlanView

    fun cancel(planId: UUID, reason: String): AllocationPlanView

    /** Expires PROPOSED plans whose soft reservation lapsed; returns the count. */
    fun expireStalePlans(now: Instant): Int
}

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
