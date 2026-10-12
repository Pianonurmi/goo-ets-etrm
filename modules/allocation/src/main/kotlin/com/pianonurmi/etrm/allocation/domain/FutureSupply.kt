package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.FutureSupplyKind
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.product.api.criteria.Criterion
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Supply not held yet (ADR 0038). It enters the problem as a lot class of its own whose lot is a stand-in, eligible
 * only for obligations delivered on or after [availableFrom]. Plans count on it with virtual lines, which are never
 * reserved or instructed.
 */
sealed interface FutureSupply {
    companion object {
        /** The registry of a stand-in lot when it is not known yet (a purchase without a delivery account). */
        const val UNKNOWN_REGISTRY = "UNKNOWN"
    }

    val tier: SupplyTier
    val kind: FutureSupplyKind
    val availableFrom: LocalDate

    /** Identifies the source across plans: `purchase:<schedule id>` or `issuance:<gsrn>:<month>`. */
    val ref: String
    val description: String

    /**
     * A forward purchase: the open quantity of a BUY delivery schedule. What will arrive is only known by its
     * criteria, so it serves an obligation whose criteria provably contain them.
     */
    data class Contracted(
        val scheduleId: UUID,
        val tradeNumber: String,
        val criterion: Criterion,
        val bucketCode: String?,
        override val availableFrom: LocalDate,
    ) : FutureSupply {
        override val tier: SupplyTier get() = SupplyTier.CONTRACTED
        override val kind: FutureSupplyKind get() = FutureSupplyKind.CONTRACTED
        override val ref: String get() = "purchase:$scheduleId"
        override val description: String get() = "purchase $tradeNumber"
    }

    /**
     * A device's expected issuance for a production month, less what has been issued: the stand-in lot carries the
     * device's attributes, so criteria are evaluated as for a held lot. [p50Mwh] is kept for the reliability premium.
     */
    data class Expected(
        val deviceId: UUID,
        val gsrn: String,
        val deviceName: String,
        val month: YearMonth,
        val p50Mwh: Long,
        val p90Mwh: Long,
        override val availableFrom: LocalDate,
    ) : FutureSupply {
        override val tier: SupplyTier get() = SupplyTier.EXPECTED
        override val kind: FutureSupplyKind get() = FutureSupplyKind.EXPECTED
        override val ref: String get() = "issuance:$gsrn:$month"
        override val description: String get() = "$deviceName, production $month"
    }
}
