package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.AvailableLot
import com.pianonurmi.etrm.allocation.domain.FutureSupply
import com.pianonurmi.etrm.allocation.domain.LotClass
import com.pianonurmi.etrm.allocation.domain.LotClassifier
import com.pianonurmi.etrm.allocation.domain.ObligationModel
import com.pianonurmi.etrm.allocation.domain.ProblemShape
import com.pianonurmi.etrm.allocation.domain.Scoring
import com.pianonurmi.etrm.allocation.domain.ValuePolicy
import com.pianonurmi.etrm.inventory.api.LotSnapshot
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.api.TransferRouteQuery
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.BitSet
import java.util.UUID

/** Assembles an [AllocationProblem] from live inventory, open obligations, criteria, buckets, routes and marks. */
@Component
@EnableConfigurationProperties(AllocationValueSettings::class)
class ProblemBuilder(
    private val criteria: CriteriaService,
    private val buckets: ProductBucketQuery,
    private val routes: TransferRouteQuery,
    private val marks: GooMarks,
    private val valueSettings: AllocationValueSettings = AllocationValueSettings(),
) {
    data class Built(val problem: AllocationProblem, val scoring: Scoring)

    /** Supply still to come (ADR 0038) with its stand-in lot and the quantity a plan may count on. */
    data class FutureLot(val future: FutureSupply, val lot: LotView, val quantityMwh: Long)

    private data class EligibilityKey(val criterion: String, val deliveryDate: LocalDate, val targetRegistryCode: String?)

    /**
     * The classes a demand may take, by the same rule as an obligation's: it expires after the delivery date, a
     * feasible route reaches the target registry (its own when none is given) and it satisfies the criterion.
     */
    fun eligible(
        classes: List<LotClass>,
        criterion: Criterion,
        deliveryDate: LocalDate,
        targetRegistryCode: String?,
        routeOf: (String, String) -> TransferRoute = cachedRoutes(),
    ): BitSet = eligibleClasses(classes, criteria.bind(criterion, criteria.contextFor(deliveryDate)), deliveryDate, targetRegistryCode, routeOf)

    /** Route lookups remembered for the lifetime of the returned function (one build, one cache entry). */
    fun cachedRoutes(): (String, String) -> TransferRoute {
        val cache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, TransferRoute>()
        return { from, to -> cache.getOrPut(from to to) { routes.route(from, to) } }
    }

    private fun eligibleClasses(
        classes: List<LotClass>,
        bound: BoundCriterion,
        deliveryDate: LocalDate,
        targetRegistryCode: String?,
        routeOf: (String, String) -> TransferRoute,
    ): BitSet {
        val bits = BitSet(classes.size)
        val context by lazy { criteria.contextFor(deliveryDate) }
        classes.forEach { cls ->
            if (cls.expiryDate > deliveryDate &&
                cls.sourceRegistry(targetRegistryCode).let { routeOf(it, targetRegistryCode ?: it).feasible } &&
                satisfies(cls, bound, deliveryDate) { context }
            ) {
                bits.set(cls.index)
            }
        }
        return bits
    }

    /**
     * Whether a class can deliver against [bound] on [deliveryDate]. Held lots and expected issuance are evaluated on
     * their (stand-in) lot; a purchase is only known by its criteria, so they must provably lie inside [bound] (ADR
     * 0016). Future supply serves only deliveries from the day it is available.
     */
    fun satisfies(cls: LotClass, bound: BoundCriterion, deliveryDate: LocalDate, context: () -> BindContext): Boolean =
        when (val future = cls.future) {
            null -> criteria.evaluate(bound, cls.representative)
            is FutureSupply.Expected -> future.availableFrom <= deliveryDate && criteria.evaluate(bound, cls.representative)
            is FutureSupply.Contracted -> future.availableFrom <= deliveryDate && criteria.implies(criteria.bind(future.criterion, context()), bound)
        }

    /** Whether a class belongs in a bucket, whatever the date: [satisfies] without the availability of future supply. */
    fun inBucket(cls: LotClass, bucket: BoundCriterion, context: () -> BindContext): Boolean =
        when (val future = cls.future) {
            is FutureSupply.Contracted -> criteria.implies(criteria.bind(future.criterion, context()), bucket)
            else -> criteria.evaluate(bucket, cls.representative)
        }

    fun build(
        snapshot: LotSnapshot,
        obligations: List<ObligationView>,
        softReserved: Map<UUID, Long>,
        asOf: LocalDate,
        policy: ValuePolicy = valueSettings.toDomain(),
        future: List<FutureLot> = emptyList(),
    ): Built {
        val held = LotClassifier.classify(snapshot.lots, softReserved)
        val classes = held + future.filter { it.quantityMwh > 0 }.mapIndexed { i, f ->
            LotClass(held.size + i, "future:${f.future.ref}", listOf(AvailableLot(f.lot, f.quantityMwh)), f.future)
        }
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
        val routeOf = cachedRoutes()

        // Eligibility depends only on the criterion, the delivery date and the target registry: obligations that share
        // all three share one row (the criteria evaluation over every class is the dominant cost at scale).
        val rows = HashMap<EligibilityKey, BitSet>()
        val eligible = models.map { obl ->
            val key = EligibilityKey(criteria.toJson(obl.obligation.criterion), obl.obligation.deliveryDate, obl.obligation.targetRegistryCode)
            rows.getOrPut(key) { eligibleClasses(classes, obl.bound, obl.obligation.deliveryDate, obl.obligation.targetRegistryCode, routeOf) }
        }

        val known = mutableMapOf<Pair<String, Int>, BigDecimal?>()
        val scoring = Scoring(
            buckets = buckets.bind(criteria.contextFor(asOf)),
            marks = { code, year -> known.getOrPut(code to year) { marks.mark(code, year, asOf) } },
            routes = routeOf,
            policy = policy,
        )
        val shape = object : ProblemShape {
            override val obligations: List<ObligationModel> = models

            override fun eligibleSupply(obl: ObligationModel): Long = eligible[obl.index].stream().mapToLong { classes[it].quantity }.sum()

            override fun eligibleClassIndexes(obl: ObligationModel): List<Int> = eligible[obl.index].stream().toArray().toList()
        }
        val scarcity = scoring.scarcityIndex(shape)
        val problem = AllocationProblem(
            classes,
            models,
            eligible,
            scoring = { cls: LotClass, obl: ObligationModel -> scoring.score(cls, obl, scarcity) },
            // A score depends on the class, the delivery date (urgency) and the target registry (transfer fee) only.
            scoreKey = { obl -> obl.obligation.deliveryDate to obl.obligation.targetRegistryCode },
        )
        return Built(problem, scoring)
    }
}
