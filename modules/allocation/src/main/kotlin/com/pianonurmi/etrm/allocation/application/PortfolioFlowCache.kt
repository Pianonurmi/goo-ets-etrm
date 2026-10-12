package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.HeadroomTable
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.allocation.domain.Scoring
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.registry.api.TransferRoute
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * ```yaml
 * etrm.allocation.headroom:
 *   months: 6             # month-ends in the available-to-sell table
 *   warm-on-miss: true    # a pre-trade check that finds no prepared figures starts preparing them
 *   max-variants: 16      # prepared figures kept per entity for amendments (their own schedules left out)
 * ```
 */
@ConfigurationProperties(prefix = "etrm.allocation.headroom")
data class AllocationHeadroomSettings(val months: Int = 6, val warmOnMiss: Boolean = true, val maxVariants: Int = 16)

/**
 * One entity's supply up to [tier], prepared for portfolio questions (ADR 0036, 0038): the problem (live lots, open
 * obligations less what approved plans have reserved, and beyond the held tier purchases and expected issuance at their
 * central P50 figure), its scoring and the solved flow. Trials on [state] run one at a time under
 * [lock], which covers in-memory work only.
 */
class PreparedFlow(
    val tier: SupplyTier,
    val stamp: String,
    val builtAt: Instant,
    val problem: AllocationProblem,
    val scoring: Scoring,
    val state: FlowState,
    val routeOf: (String, String) -> TransferRoute,
) {
    val lock = ReentrantLock()

    /** Eligibility of new demands, by (criterion JSON, delivery date, target registry). */
    val eligibility = ConcurrentHashMap<Triple<String, LocalDate, String?>, BitSet>()

    @Volatile
    var table: HeadroomTable? = null
}

/**
 * Prepared flows per entity and supply tier, and per set of left-out obligations (an amendment's own schedules). Every
 * read compares the entry's stamp with the database (the entity's holding stamp, its open obligations, the active
 * buckets, the date and, beyond the held tier, the future supply itself), so a change made through any pod is seen on
 * the next read without events.
 */
@Component
@EnableConfigurationProperties(AllocationHeadroomSettings::class)
class PortfolioFlowCache(
    private val obligations: ObligationRepository,
    private val plans: AllocationPlanRepository,
    private val problemBuilder: ProblemBuilder,
    private val buckets: ProductBucketQuery,
    private val lotQuery: LotQuery,
    private val clock: Clock,
    private val transactions: TransactionOperations = TransactionOperations.withoutTransaction(),
    private val settings: AllocationHeadroomSettings = AllocationHeadroomSettings(),
    private val futureSupply: FutureSupplyProvider? = null,
) {
    private val log = LoggerFactory.getLogger(PortfolioFlowCache::class.java)

    private data class Key(val holderEntityId: UUID, val excluded: Set<UUID>, val tier: SupplyTier)

    /** What a build reads besides the database: the stamp, and the future supply it was computed from. */
    private class Stamped(val stamp: String, val future: List<ProblemBuilder.FutureLot>)

    private val cache = ConcurrentHashMap<Key, PreparedFlow>()
    private val building: MutableSet<Key> = ConcurrentHashMap.newKeySet()
    private val background = Executors.newVirtualThreadPerTaskExecutor()

    /** The entity's prepared flow, built now when missing or out of date. */
    fun prepare(holderEntityId: UUID, excludeSourceRefs: Set<UUID> = emptySet(), tier: SupplyTier = SupplyTier.HELD): PreparedFlow =
        transactions.execute {
            val key = key(holderEntityId, excludeSourceRefs, tier)
            val stamped = stamp(key)
            cache[key]?.takeIf { it.stamp == stamped.stamp } ?: build(key, stamped)
        }

    /** The prepared flow when it is up to date; otherwise null, and it is prepared in the background. */
    fun ifReady(holderEntityId: UUID, excludeSourceRefs: Set<UUID> = emptySet(), tier: SupplyTier = SupplyTier.HELD): PreparedFlow? {
        val key = transactions.execute { key(holderEntityId, excludeSourceRefs, tier) }
        val stamp = transactions.execute { stamp(key) }.stamp
        return cache[key]?.takeIf { it.stamp == stamp } ?: run {
            warmInBackground(key)
            null
        }
    }

    @PreDestroy
    fun close() {
        background.shutdownNow()
    }

    /** Only exclusions that name an open obligation of the entity matter, so most amendments share the base entry. */
    private fun key(holderEntityId: UUID, excludeSourceRefs: Set<UUID>, tier: SupplyTier): Key {
        val excluded = excludeSourceRefs.filterTo(HashSet()) { ref ->
            obligations.bySource(ref)?.let { it.holderEntityId == holderEntityId && it.remainingMwh > 0 } == true
        }
        // Without a provider there is no future supply: every tier is the held one.
        return Key(holderEntityId, excluded, if (futureSupply == null) SupplyTier.HELD else tier)
    }

    private fun stamp(key: Key): Stamped {
        val holderEntityId = key.holderEntityId
        val catalogue = buckets.buckets(true).joinToString(",") { "${it.code}@${it.version}" }.hashCode()
        val held = "${lotQuery.holdingStamp(holderEntityId)}|${obligations.openStamp(holderEntityId)}|$catalogue|${LocalDate.now(clock)}"
        if (key.tier == SupplyTier.HELD || futureSupply == null) return Stamped(held, emptyList())
        // Purchases and forecasts have no stamp of their own here; the supply they amount to is the stamp.
        val future = futureSupply.supply(holderEntityId, Quantile.P50).filter { it.future.tier <= key.tier }
        val digest = future.joinToString(",") { "${it.future.ref}=${it.quantityMwh}@${it.future.availableFrom}" }.hashCode()
        return Stamped("$held|${key.tier}:$digest", future)
    }

    private fun build(key: Key, stamped: Stamped): PreparedFlow {
        val started = System.nanoTime()
        val snapshot = lotQuery.snapshot(key.holderEntityId)
        val open = withoutInFlight(key.holderEntityId, obligations.open(key.holderEntityId).filter { it.sourceRef !in key.excluded })
        val built = problemBuilder.build(snapshot, open, emptyMap(), LocalDate.now(clock), future = stamped.future)
        val prepared = PreparedFlow(
            tier = key.tier,
            stamp = stamped.stamp,
            builtAt = clock.instant(),
            problem = built.problem,
            scoring = built.scoring,
            state = CoverageAnalyser(built.scoring::bestBucketCode).solve(built.problem),
            routeOf = problemBuilder.cachedRoutes(),
        )
        cache.merge(key, prepared) { old, new -> if (new.builtAt >= old.builtAt) new else old }
        evictVariants(key.holderEntityId)
        log.debug(
            "Portfolio flow for {} ({}) prepared in {} ms: {} classes, {} obligations",
            key.holderEntityId,
            key.tier,
            (System.nanoTime() - started) / NANOS_PER_MILLI,
            built.problem.classes.size,
            built.problem.obligations.size,
        )
        return prepared
    }

    /**
     * Approved plans reserve lots for their obligations before the registry confirms delivery: those lots are no
     * longer held, and the obligations stay open. The reserved quantity counts as delivered here, so the obligations
     * do not compete for held supply they no longer need.
     */
    private fun withoutInFlight(holderEntityId: UUID, open: List<ObligationView>): List<ObligationView> {
        val inFlight = HashMap<UUID, Long>()
        plans.byEntity(holderEntityId, activeOnly = true).forEach { plan ->
            plan.lines.filter { it.status == LineStatus.RESERVED || it.status == LineStatus.INSTRUCTED }
                .forEach { inFlight.merge(it.obligationId, it.quantityMwh, Long::plus) }
        }
        return open.map { o -> inFlight[o.id]?.let { o.copy(fulfilledMwh = minOf(o.quantityMwh, o.fulfilledMwh + it)) } ?: o }
    }

    private fun evictVariants(holderEntityId: UUID) {
        val variants = cache.entries.filter { it.key.holderEntityId == holderEntityId && (it.key.excluded.isNotEmpty() || it.key.tier != SupplyTier.HELD) }
        if (variants.size > settings.maxVariants) {
            variants.sortedBy { it.value.builtAt }.take(variants.size - settings.maxVariants).forEach { cache.remove(it.key, it.value) }
        }
    }

    private fun warmInBackground(key: Key) {
        if (!settings.warmOnMiss || !building.add(key)) return
        background.execute {
            try {
                transactions.execute {
                    val stamped = stamp(key)
                    if (cache[key]?.stamp != stamped.stamp) build(key, stamped)
                }
            } catch (e: Exception) {
                log.warn("Portfolio flow for {} could not be prepared", key.holderEntityId, e)
            } finally {
                building.remove(key)
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}
