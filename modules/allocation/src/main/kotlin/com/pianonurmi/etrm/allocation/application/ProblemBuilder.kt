package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.LotClass
import com.pianonurmi.etrm.allocation.domain.LotClassifier
import com.pianonurmi.etrm.allocation.domain.ObligationModel
import com.pianonurmi.etrm.allocation.domain.ProblemShape
import com.pianonurmi.etrm.allocation.domain.Scoring
import com.pianonurmi.etrm.allocation.domain.ScoringWeights
import com.pianonurmi.etrm.inventory.api.LotSnapshot
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.api.TransferRouteQuery
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.BitSet
import java.util.UUID

/** Assembles an [AllocationProblem] from live inventory, open obligations, criteria, buckets, routes and marks. */
@Component
class ProblemBuilder(
    private val criteria: CriteriaService,
    private val buckets: ProductBucketQuery,
    private val routes: TransferRouteQuery,
    private val marks: GooMarks,
) {
    data class Built(val problem: AllocationProblem, val scoring: Scoring)

    fun build(
        snapshot: LotSnapshot,
        obligations: List<ObligationView>,
        softReserved: Map<UUID, Long>,
        asOf: LocalDate,
        weights: ScoringWeights = ScoringWeights(),
    ): Built {
        val classes = LotClassifier.classify(snapshot.lots, softReserved)
        val models = obligations.filter { it.remainingMwh > 0 }.mapIndexed { index, obligation ->
            val ctx = criteria.contextFor(obligation.deliveryDate)
            val (minShares, maxShare) = AllocationProblem.constraintsOf(obligation)
            ObligationModel(
                index = index,
                obligation = obligation,
                bound = criteria.bind(obligation.criterion, ctx),
                minShares = minShares.map { criteria.bind(it.criterion, ctx) to it.percent },
                maxSharePerDevice = maxShare,
                specificity = criteria.specificity(obligation.criterion),
            )
        }
        val routeCache = HashMap<Pair<String, String>, TransferRoute>()
        val routeOf: (String, String) -> TransferRoute = { from, to -> routeCache.getOrPut(from to to) { routes.route(from, to) } }

        val eligible = models.map { obl ->
            val bits = BitSet(classes.size)
            classes.forEach { cls ->
                val target = obl.obligation.targetRegistryCode ?: cls.registryCode
                if (cls.expiryDate > obl.obligation.deliveryDate &&
                    routeOf(cls.registryCode, target).feasible &&
                    criteria.evaluate(obl.bound, cls.representative)
                ) {
                    bits.set(cls.index)
                }
            }
            bits
        }

        val known = mutableMapOf<Pair<String, Int>, BigDecimal?>()
        val scoring = Scoring(
            buckets = buckets.bind(criteria.contextFor(asOf)),
            marks = { code, year -> known.getOrPut(code to year) { marks.mark(code, year, asOf) } },
            routes = routeOf,
            weights = weights,
        )
        val shape = object : ProblemShape {
            override val obligations: List<ObligationModel> = models

            override fun eligibleSupply(obl: ObligationModel): Long = eligible[obl.index].stream().mapToLong { classes[it].quantity }.sum()

            override fun eligibleClassIndexes(obl: ObligationModel): List<Int> = eligible[obl.index].stream().toArray().toList()
        }
        val scarcity = scoring.scarcityIndex(shape)
        val problem = AllocationProblem(classes, models, eligible) { cls: LotClass, obl: ObligationModel -> scoring.score(cls, obl, scarcity) }
        return Built(problem, scoring)
    }
}
