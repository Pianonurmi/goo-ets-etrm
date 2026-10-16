package com.pianonurmi.etrm.position.application

import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.position.api.BucketPosition
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the GoO position needs of a live lot: where it is bucketed, its vintage, status, size and expiry. */
data class PositionLot(val bucketCode: String?, val productionYear: Int, val status: LotStatus, val quantityMwh: Long, val expiryDate: LocalDate)

/** The netting behind the position reports, apart from the queries that feed it. */
object PositionMath {
    const val UNBUCKETED = "UNBUCKETED"
    private const val EXPIRY_WARNING_DAYS = 90L

    /**
     * Live lots by bucket and production year: held, reserved and in transfer, what expires between [today] and 90 days
     * later (a lot already past its expiry date is not expiring), and the lot count. Lots without a bucket are grouped as
     * [UNBUCKETED]. Sorted by bucket, then year.
     */
    fun inventory(lots: List<PositionLot>, today: LocalDate): List<BucketPosition> =
        lots.groupBy { (it.bucketCode ?: UNBUCKETED) to it.productionYear }
            .map { (key, group) ->
                BucketPosition(
                    bucketCode = key.first,
                    productionYear = key.second,
                    heldMwh = group.filter { it.status == LotStatus.HELD }.sumOf { it.quantityMwh },
                    reservedMwh = group.filter { it.status == LotStatus.RESERVED }.sumOf { it.quantityMwh },
                    inTransferMwh = group.filter { it.status == LotStatus.IN_TRANSFER }.sumOf { it.quantityMwh },
                    expiringWithin90DaysMwh = group.filter { ChronoUnit.DAYS.between(today, it.expiryDate) in 0..EXPIRY_WARNING_DAYS }
                        .sumOf { it.quantityMwh },
                    lotCount = group.size,
                )
            }
            .sortedWith(compareBy({ it.bucketCode }, { it.productionYear }))
}
