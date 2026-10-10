package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.registry.api.TransferRoute
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

data class ScoringWeights(
    val scarcityWeight: BigDecimal = BigDecimal("1.0"),
    val urgencyWeight: BigDecimal = BigDecimal("2.0"),
    val urgencyHorizonDays: Long = 60,
)

/**
 * Cost of delivering a lot class to an obligation; lower is better. Opportunity cost is the mark of the most
 * specific bucket the class fits (falling back to that bucket's specificity as a proxy when no mark exists), plus
 * the transfer fee, plus scarcity (how many other obligations compete for this class), minus an urgency bonus for
 * classes that expire soon after the delivery date.
 */
class Scoring(
    private val buckets: BoundBuckets,
    private val marks: (bucketCode: String, productionYear: Int) -> BigDecimal?,
    private val routes: (fromRegistry: String, toRegistry: String) -> TransferRoute,
    private val weights: ScoringWeights = ScoringWeights(),
) {
    private val bucketCache = HashMap<Int, Pair<String?, BigDecimal>>()

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

    fun score(cls: LotClass, obl: ObligationModel, scarcity: Map<Int, BigDecimal>): ScoreBreakdown {
        val (bucketCode, opportunity) = bucketCache.getOrPut(cls.index) {
            val best = buckets.best(cls.representative)
            val mark = best?.let { marks(it.code, cls.productionYear) }
            best?.code to (mark ?: best?.specificity ?: BigDecimal.ZERO)
        }
        val targetRegistry = obl.obligation.targetRegistryCode ?: cls.registryCode
        val transfer = routes(cls.registryCode, targetRegistry).feePerMwh
        val classScarcity = (scarcity[cls.index] ?: BigDecimal.ZERO) * weights.scarcityWeight
        val daysToExpiry = ChronoUnit.DAYS.between(obl.obligation.deliveryDate, cls.expiryDate)
        val urgency = if (daysToExpiry in 0 until weights.urgencyHorizonDays) {
            weights.urgencyWeight * BigDecimal(
                weights.urgencyHorizonDays - daysToExpiry,
            ).divide(BigDecimal(weights.urgencyHorizonDays), 6, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        val total = (opportunity + transfer + classScarcity - urgency).setScale(6, RoundingMode.HALF_UP)
        return ScoreBreakdown(
            opportunity.setScale(6, RoundingMode.HALF_UP),
            transfer,
            classScarcity.setScale(6, RoundingMode.HALF_UP),
            urgency.setScale(6, RoundingMode.HALF_UP),
            total,
            bucketCode,
        )
    }

    fun bestBucketCode(cls: LotClass): String? = bucketCache[cls.index]?.first ?: buckets.best(cls.representative)?.code
}

/** Minimal view of the problem needed before scores exist (eligibility only). */
interface ProblemShape {
    val obligations: List<ObligationModel>

    fun eligibleSupply(obl: ObligationModel): Long

    fun eligibleClassIndexes(obl: ObligationModel): List<Int>
}
