package com.pianonurmi.etrm.allocation.api

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Supply still to come (ADR 0038): a purchase's open quantity, or what an own device is expected to have issued for a
 * production month less what already has been. [quantityMwh] is the central figure (P50), [p90Mwh] the cautious one
 * plans count on; for a purchase they are the same.
 */
data class FutureSupplyItem(
    val kind: FutureSupplyKind,
    val ref: String,
    val description: String,
    val availableFrom: LocalDate,
    val quantityMwh: Long,
    val p90Mwh: Long,
    val bucketCode: String?,
    val tradeNumber: String?,
    val deviceGsrn: String?,
    val productionMonth: YearMonth?,
)

/** Open obligations delivered in [month], and how much of them each supply tier covers, cumulatively. */
data class FutureCoverageMonth(
    val month: YearMonth,
    val demandMwh: Long,
    val coveredHeldMwh: Long,
    val coveredWithContractedMwh: Long,
    val coveredWithExpectedMwh: Long,
)

/** The entity's future supply and what it adds to coverage; figures at P50, from the prepared flow. */
data class FutureSupplyView(
    val holderEntityId: UUID,
    val stamp: String,
    val computedAt: Instant,
    val items: List<FutureSupplyItem>,
    val months: List<FutureCoverageMonth>,
    val totalDemandMwh: Long,
    val coveredHeldMwh: Long,
    val coveredWithContractedMwh: Long,
    val coveredWithExpectedMwh: Long,
)

interface FutureSupplyQuery {
    fun futureSupply(holderEntityId: UUID): FutureSupplyView
}
