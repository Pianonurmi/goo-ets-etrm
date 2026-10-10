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
    val holdings: List<ProductHolding>,
    val heldAllowances: Long,
    val openPurchases: Long,
    val openSales: Long,
    val freeAllocationExpected: Long,
    val verifiedEmissions: Long?,
    val forecastEmissions: Long?,
) {
    val emissionsBasis: Long get() = verifiedEmissions ?: forecastEmissions ?: 0

    /** Allowances available after firm contracts and expected allocation minus emissions to surrender. */
    val netPosition: Long get() = heldAllowances + openPurchases - openSales + freeAllocationExpected - emissionsBasis

    val hedgeRatio: BigDecimal?
        get() = emissionsBasis.takeIf { it > 0 }?.let {
            BigDecimal(heldAllowances + openPurchases - openSales + freeAllocationExpected).divide(BigDecimal(it), 4, java.math.RoundingMode.HALF_UP)
        }
}

data class UpsertComplianceInput(
    val legalEntityId: UUID,
    val complianceYear: Int,
    val verifiedEmissionsTco2e: Long? = null,
    val forecastEmissionsTco2e: Long? = null,
    val freeAllocationExpected: Long? = null,
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
    fun upsertComplianceInput(command: UpsertComplianceInput)

    /** Persists today's GoO and ETS positions for the entity; returns the snapshots written. */
    fun takeSnapshots(legalEntityId: UUID, asOfDate: LocalDate): List<PositionSnapshotView>
}
