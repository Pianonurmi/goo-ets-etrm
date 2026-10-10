package com.pianonurmi.etrm.position.api

import com.pianonurmi.etrm.allocation.api.CoverageReport
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Inventory of one entity aggregated by product bucket and production year. */
data class BucketPosition(
    val bucketCode: String,
    val productionYear: Int,
    val heldMwh: Long,
    val reservedMwh: Long,
    val inTransferMwh: Long,
    val expiringWithin90DaysMwh: Long,
    val lotCount: Int,
) {
    val liveMwh: Long get() = heldMwh + reservedMwh + inTransferMwh
}

/** GoO position: inventory by bucket, open obligations with coverage, expiry ladder. Netting is a flow, not a sum. */
data class GooPositionReport(
    val legalEntityId: UUID,
    val asOf: Instant,
    val inventory: List<BucketPosition>,
    val totalLiveMwh: Long,
    val totalOpenObligationMwh: Long,
    val coverage: CoverageReport,
    val expiryLadder: Map<LocalDate, Long>,
)

data class ProductHolding(val productCode: String, val registryAccountId: UUID, val quantity: Long, val reserved: Long)

/** EU ETS compliance ladder for one entity and compliance year. */
data class ComplianceLadder(
    val legalEntityId: UUID,
    val complianceYear: Int,
    val asOf: Instant,
    /** Firm deliveries count only when they arrive by this date. */
    val surrenderDeadline: LocalDate,
    val holdings: List<ProductHolding>,
    val heldAllowances: Long,
    val openPurchases: Long,
    val openSales: Long,
    val freeAllocationExpected: Long,
    /** Free allocation for the year already issued, and therefore already in [heldAllowances]. */
    val freeAllocationReceived: Long,
    /** Allowances already surrendered for the year. */
    val surrendered: Long,
    /** Verified emissions when every installation's are verified and approved. */
    val verifiedEmissions: Long?,
    /** Each installation's best figure: verified, else monthly estimates, else forecast. */
    val estimatedEmissions: Long,
    val installations: List<InstallationLine> = emptyList(),
) {
    val emissionsBasis: Long get() = verifiedEmissions ?: estimatedEmissions

    /** Free allocation still to be issued for the year. */
    val freeAllocationOutstanding: Long get() = maxOf(0, freeAllocationExpected - freeAllocationReceived)

    /** Emissions still to be covered by a surrender. */
    val obligationOutstanding: Long get() = maxOf(0, emissionsBasis - surrendered)

    private val supply: Long get() = heldAllowances + openPurchases - openSales + freeAllocationOutstanding

    /** Allowances available after firm contracts and expected allocation minus emissions still to surrender. */
    val netPosition: Long get() = supply - obligationOutstanding

    val hedgeRatio: BigDecimal?
        get() = obligationOutstanding.takeIf { it > 0 }?.let { BigDecimal(supply).divide(BigDecimal(it), 4, java.math.RoundingMode.HALF_UP) }
}

/** One installation's figures in the ladder; basis and status are those of the etscompliance module. */
data class InstallationLine(
    val installationId: UUID,
    val name: String,
    val emissions: Long,
    val basis: String,
    val surrendered: Long,
    val held: Long,
    val status: String,
)

enum class SnapshotKind { GOO, ETS }

data class PositionSnapshotView(
    val id: UUID,
    val legalEntityId: UUID,
    val kind: SnapshotKind,
    val asOfDate: LocalDate,
    val takenAt: Instant,
    val data: Map<String, Any?>,
)

interface PositionQuery {
    fun gooPosition(legalEntityId: UUID): GooPositionReport

    fun complianceLadder(legalEntityId: UUID, complianceYear: Int): ComplianceLadder

    fun snapshot(legalEntityId: UUID, kind: SnapshotKind, asOfDate: LocalDate): PositionSnapshotView?

    fun snapshots(legalEntityId: UUID, kind: SnapshotKind, from: LocalDate, to: LocalDate): List<PositionSnapshotView>
}

interface PositionCommand {
    /** Persists today's GoO and ETS positions for the entity; returns the snapshots written. */
    fun takeSnapshots(legalEntityId: UUID, asOfDate: LocalDate): List<PositionSnapshotView>
}
