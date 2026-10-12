package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.registry.api.TransferRoute
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

/**
 * How allocation values certificates (ADR 0035). [liquidityHorizonDays]: a lot that expires within this many days of
 * the delivery date is worth proportionally less, because it lapses if it is not delivered.
 */
data class ValuePolicy(val liquidityHorizonDays: Long = 60) {
    init {
        require(liquidityHorizonDays > 0) { "the liquidity horizon must be at least one day" }
    }
}

/**
 * The cost of delivering a lot class to an obligation, in EUR per MWh; lower is better (ADR 0035).
 *
 * - **Value of the lot**: the best price it qualifies for, the highest mark among every bucket the lot satisfies (a
 *   Nordic wind lot is worth the higher of NORDIC_WIND and EU_WIND). Spending it gives that value up.
 * - **Expiry haircut**: a lot expiring within the liquidity horizon after delivery is worth `days / horizon` of it.
 * - **Cost** = value after the haircut + the transfer fee to the target registry.
 * - **Give-away** = value of the lot − mark of the sale's own bucket: the premium handed over when a more valuable
 *   (more specific) certificate delivers a sale. Informational; it does not change the cost.
 * - **Scarcity** (demand / eligible supply over the obligations competing for the class) is kept as a tie-break
 *   between classes of equal cost, not added to it: it has no unit.
 *
 * A lot with no priced bucket at all is valued at zero and flagged `priced = false`.
 */
class Scoring(
    private val buckets: BoundBuckets,
    private val marks: (bucketCode: String, productionYear: Int) -> BigDecimal?,
    private val routes: (fromRegistry: String, toRegistry: String) -> TransferRoute,
    private val policy: ValuePolicy = ValuePolicy(),
) {
    private val valueCache = HashMap<Int, Pair<String?, BigDecimal?>>()
    private val saleMarkCache = HashMap<Int, BigDecimal?>()

    fun scarcityIndex(problem: ProblemShape): Map<Int, BigDecimal> {
        val scarcity = HashMap<Int, BigDecimal>()
        problem.obligations.forEach { obl ->
            val supply = problem.eligibleSupply(obl)
            if (supply <= 0) return@forEach
            val pressure = BigDecimal(obl.demand).divide(BigDecimal(supply), 6, RoundingMode.HALF_UP)
            problem.eligibleClassIndexes(obl).forEach { c -> scarcity.merge(c, pressure, BigDecimal::add) }
        }
        return scarcity
    }

    /** The best-priced bucket the class qualifies for and its mark; the mark is null when no bucket of it is priced. */
    fun value(cls: LotClass): Pair<String?, BigDecimal?> = valueCache.getOrPut(cls.index) {
        // A purchase is only known by its criteria: it is worth its own bucket's mark for the year it arrives.
        (cls.future as? FutureSupply.Contracted)?.let { return@getOrPut it.bucketCode to it.bucketCode?.let { code -> marks(code, it.availableFrom.year) } }
        buckets.matching(cls.representative)
            .mapNotNull { bucket -> marks(bucket.code, cls.productionYear)?.let { bucket.code to it } }
            .maxWithOrNull(compareBy<Pair<String, BigDecimal>> { it.second }.thenByDescending { it.first })
            ?: (buckets.best(cls.representative)?.code to null)
    }

    /** The mark of the obligation's own bucket at its vintage, or null when it has no bucket or the bucket no price. */
    fun saleMark(obl: ObligationModel): BigDecimal? = saleMarkCache.getOrPut(obl.index) {
        val bucket = obl.obligation.bucketCode ?: return@getOrPut null
        marks(bucket, obl.obligation.vintageYear ?: obl.obligation.deliveryDate.year)
    }

    fun score(cls: LotClass, obl: ObligationModel, scarcity: Map<Int, BigDecimal>): ScoreBreakdown {
        val (bucketCode, mark) = value(cls)
        val worth = mark ?: BigDecimal.ZERO
        val source = cls.sourceRegistry(obl.obligation.targetRegistryCode)
        val transfer = routes(source, obl.obligation.targetRegistryCode ?: source).feePerMwh
        val daysToExpiry = ChronoUnit.DAYS.between(obl.obligation.deliveryDate, cls.expiryDate).coerceAtLeast(0)
        val retained = if (daysToExpiry >= policy.liquidityHorizonDays) {
            worth
        } else {
            worth * BigDecimal(daysToExpiry).divide(BigDecimal(policy.liquidityHorizonDays), 6, RoundingMode.HALF_UP)
        }
        val haircut = worth - retained
        val premium = futurePremium(cls, worth)
        val saleMark = saleMark(obl)
        return ScoreBreakdown(
            opportunityCost = worth.setScale(SCALE, RoundingMode.HALF_UP),
            transferCost = transfer.setScale(SCALE, RoundingMode.HALF_UP),
            scarcity = (scarcity[cls.index] ?: BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP),
            urgencyBonus = haircut.setScale(SCALE, RoundingMode.HALF_UP),
            total = (retained + transfer + (premium ?: BigDecimal.ZERO)).setScale(SCALE, RoundingMode.HALF_UP),
            bucketCode = bucketCode,
            saleMarkEur = saleMark?.setScale(SCALE, RoundingMode.HALF_UP),
            giveAwayPerMwh = if (mark != null && saleMark != null) (mark - saleMark).setScale(SCALE, RoundingMode.HALF_UP) else null,
            unit = ScoreBreakdown.EUR_PER_MWH,
            priced = mark != null,
            reliabilityPremium = premium?.setScale(SCALE, RoundingMode.HALF_UP),
        )
    }

    /**
     * Supply still to come costs a little more than held lots of the same value, so held lots win ties; expected
     * issuance counted at P90 also carries the share of value between its P50 and P90 (ADR 0038). Null for held lots.
     */
    private fun futurePremium(cls: LotClass, worth: BigDecimal): BigDecimal? = when (val future = cls.future) {
        null -> null
        is FutureSupply.Contracted -> FUTURE_TIE_BREAK
        is FutureSupply.Expected -> FUTURE_TIE_BREAK + if (future.p50Mwh > 0) {
            worth * BigDecimal(future.p50Mwh - future.p90Mwh).divide(BigDecimal(future.p50Mwh), SCALE, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
    }

    fun bestBucketCode(cls: LotClass): String? =
        (cls.future as? FutureSupply.Contracted)?.bucketCode ?: buckets.best(cls.representative)?.code

    private companion object {
        const val SCALE = 6
        val FUTURE_TIE_BREAK = BigDecimal("0.0001")
    }
}

/** Minimal view of the problem needed before scores exist (eligibility only). */
interface ProblemShape {
    val obligations: List<ObligationModel>

    fun eligibleSupply(obl: ObligationModel): Long

    fun eligibleClassIndexes(obl: ObligationModel): List<Int>
}
