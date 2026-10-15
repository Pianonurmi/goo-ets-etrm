package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.HeadroomTable
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.LotClass
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ```yaml
 * etrm.allocation.headroom:
 *   months: 6             # month-ends in the available-to-sell table
 *   warm-on-miss: true    # a pre-trade check that finds no prepared figures starts preparing them
 *   max-variants: 16      # prepared figures kept per entity for amendments (their own schedules left out)
 *   patch: true           # held figures are patched from the changed lots and obligations (ADR 0043)
 *   full-build-after: 1h  # a patched index is built afresh at least this often (it does not see route changes)
 * ```
 */
@ConfigurationProperties(prefix = "etrm.allocation.headroom")
data class AllocationHeadroomSettings(
    val months: Int = 6,
    val warmOnMiss: Boolean = true,
    val maxVariants: Int = 16,
    val patch: Boolean = true,
    val fullBuildAfter: Duration = Duration.ofHours(1),
)

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
    /** Whether the figures were patched from the previous ones (ADR 0043) rather than built afresh. */
    val patched: Boolean = false,
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
    private val transactions: TransactionOperations,
    private val settings: AllocationHeadroomSettings,
    private val futureSupply: FutureSupplyProvider,
) {
    private val log = LoggerFactory.getLogger(PortfolioFlowCache::class.java)

    private data class Key(val holderEntityId: UUID, val excluded: Set<UUID>, val tier: SupplyTier)

    /** What a build reads besides the database: the stamp, its day and catalogue, and the future supply. */
    private class Stamped(val stamp: String, val day: LocalDate, val catalogue: Int, val future: List<ProblemBuilder.FutureLot>)

    /**
     * The index behind an entity's held figures, kept to be patched (ADR 0043). Only the build of the entity's base key
     * changes it (single flight); [lock] covers its in-memory changes and its publications, never I/O. [stamp] is the
     * stamp of what it holds; [bucketOf] remembers each class key's best bucket (a catalogue change builds afresh).
     */
    private class HeldIndex(
        val index: ProblemIndex,
        val day: LocalDate,
        val catalogue: Int,
        val builtAt: Instant,
        @Volatile var stamp: String,
    ) {
        val lock = ReentrantLock()
        val bucketOf = ConcurrentHashMap<String, String>()
    }

    private val cache = ConcurrentHashMap<Key, PreparedFlow>()
    private val heldIndexes = ConcurrentHashMap<UUID, HeldIndex>()
    private val inFlight = ConcurrentHashMap<Key, CompletableFuture<PreparedFlow>>()
    private val background = Executors.newVirtualThreadPerTaskExecutor()

    /** The entity's prepared flow, built now when missing or out of date. */
    fun prepare(holderEntityId: UUID, excludeSourceRefs: Set<UUID> = emptySet(), tier: SupplyTier = SupplyTier.HELD): PreparedFlow {
        val (key, stamped) = transactions.execute { key(holderEntityId, excludeSourceRefs, tier).let { it to stamp(it) } }
        return cache[key]?.takeIf { it.stamp == stamped.stamp } ?: buildOnce(key, stamped)
    }

    /**
     * The prepared flow when it is up to date, or when it can be patched now (held figures of an entity already
     * prepared, ADR 0043); otherwise null, and it is prepared in the background.
     */
    fun ifReady(holderEntityId: UUID, excludeSourceRefs: Set<UUID> = emptySet(), tier: SupplyTier = SupplyTier.HELD): PreparedFlow? {
        val (key, stamped) = transactions.execute { key(holderEntityId, excludeSourceRefs, tier).let { it to stamp(it) } }
        cache[key]?.takeIf { it.stamp == stamped.stamp }?.let { return it }
        if (key.tier == SupplyTier.HELD && patchable(heldIndexes[holderEntityId], stamped)) return buildOnce(key, stamped)
        warmInBackground(key)
        return null
    }

    /**
     * The held tier built afresh with the prices of [asOf], for valuation (ADR 0039); not cached. Holdings and open
     * obligations are the current ones.
     */
    fun buildAt(holderEntityId: UUID, asOf: LocalDate): Pair<ProblemBuilder.Built, String> = transactions.execute {
        val key = Key(holderEntityId, emptySet(), SupplyTier.HELD)
        val stamp = stamp(key).stamp
        val open = withoutInFlight(holderEntityId, obligations.open(holderEntityId))
        problemBuilder.build(lotQuery.snapshot(holderEntityId), open, emptyMap(), asOf) to stamp
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
        return Key(holderEntityId, excluded, tier)
    }

    private fun stamp(key: Key): Stamped {
        val holderEntityId = key.holderEntityId
        val catalogue = buckets.buckets(true).joinToString(",") { "${it.code}@${it.version}" }.hashCode()
        val day = LocalDate.now(clock)
        val held = "${lotQuery.holdingStamp(holderEntityId)}|${obligations.openStamp(holderEntityId)}|$catalogue|$day"
        if (key.tier == SupplyTier.HELD) return Stamped(held, day, catalogue, emptyList())
        // Purchases and forecasts have no stamp of their own here; the supply they amount to is the stamp.
        val future = futureSupply.supply(holderEntityId, Quantile.P50).filter { it.future.tier <= key.tier }
        val digest = future.joinToString(",") { "${it.future.ref}=${it.quantityMwh}@${it.future.availableFrom}" }.hashCode()
        return Stamped("$held|${key.tier}:$digest", day, catalogue, future)
    }

    /**
     * Builds [key] unless a build of it is already running. A second reader then waits for that build (outside the
     * cache's own transactions, so a reader without an outer one holds no connection) and takes its result when it
     * carries the stamp the reader saw; otherwise (an older build, or one that failed) it builds itself.
     */
    private fun buildOnce(key: Key, stamped: Stamped): PreparedFlow {
        val mine = CompletableFuture<PreparedFlow>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) {
            val result = runCatching { running.join() }.getOrNull()
            return result?.takeIf { it.stamp == stamped.stamp } ?: transactions.execute { build(key, stamped) }
        }
        try {
            return transactions.execute { build(key, stamped) }.also { mine.complete(it) }
        } finally {
            // Whatever ended the build, nobody may wait for it forever.
            if (!mine.isDone) mine.completeExceptionally(IllegalStateException("preparing the flow of ${key.holderEntityId} failed"))
            inFlight.remove(key, mine)
        }
    }

    private fun build(key: Key, stamped: Stamped): PreparedFlow = when {
        key.tier != SupplyTier.HELD -> buildFull(key, stamped)
        key.excluded.isNotEmpty() -> buildVariant(key, stamped)
        else -> patch(stamped, key) ?: buildHeld(key, stamped)
    }

    /** Beyond the held tier: built afresh every time (the future supply is read on every stamp anyway). */
    private fun buildFull(key: Key, stamped: Stamped): PreparedFlow {
        val started = System.nanoTime()
        val snapshot = lotQuery.snapshot(key.holderEntityId)
        val open = withoutInFlight(key.holderEntityId, obligations.open(key.holderEntityId).filter { it.sourceRef !in key.excluded })
        val read = System.nanoTime()
        val built = problemBuilder.build(snapshot, open, emptyMap(), stamped.day, future = stamped.future)
        return solved(key, stamped.stamp, built, built.scoring::bestBucketCode, patched = false, Timing(started, read, System.nanoTime()))
    }

    /** The held figures built afresh, keeping their index to patch later. */
    private fun buildHeld(key: Key, stamped: Stamped): PreparedFlow {
        val started = System.nanoTime()
        val snapshot = lotQuery.snapshot(key.holderEntityId)
        val open = withoutInFlight(key.holderEntityId, obligations.open(key.holderEntityId))
        val read = System.nanoTime()
        val held = HeldIndex(problemBuilder.index(snapshot.lots, open), stamped.day, stamped.catalogue, clock.instant(), stamped.stamp)
        heldIndexes[key.holderEntityId] = held
        return publish(key, held, stamped.stamp, patched = false, started, read)
    }

    /**
     * The held figures patched from the lots and obligations that changed since the index was last brought up to date
     * (ADR 0043), or null when a full build is due: no index, a new day, a catalogue change, the index too old, too
     * many changed lots or too many emptied classes.
     */
    private fun patch(stamped: Stamped, key: Key): PreparedFlow? {
        val held = heldIndexes[key.holderEntityId]?.takeIf { patchable(it, stamped) } ?: return null
        val started = System.nanoTime()
        val (changed, removed) = held.index.diff(lotQuery.liveVersions(key.holderEntityId))
        if (changed.size + removed.size > maxOf(MIN_PATCH_LOTS, held.index.lotCount / PATCH_SHARE)) return null
        val lots = lotQuery.lotsById(changed)
        val gone = removed + (changed - lots.mapTo(HashSet()) { it.id })
        val open = withoutInFlight(key.holderEntityId, obligations.open(key.holderEntityId))
        val read = System.nanoTime()
        val worn = held.lock.withLock {
            held.index.applyLots(lots, gone)
            held.index.setObligations(open)
            held.stamp = stamped.stamp
            held.index.tombstones > held.index.classCount / TOMBSTONE_SHARE
        }
        if (worn) return null
        log.debug("Portfolio flow for {}: {} lots changed, {} gone", key.holderEntityId, lots.size, gone.size)
        return publish(key, held, stamped.stamp, patched = true, started, read)
    }

    /** An amendment's figures: the entity's held index, brought up to date, without the amendment's own obligations. */
    private fun buildVariant(key: Key, stamped: Stamped): PreparedFlow {
        val base = Key(key.holderEntityId, emptySet(), SupplyTier.HELD)
        cache[base]?.takeIf { it.stamp == stamped.stamp } ?: buildOnce(base, stamped)
        val held = heldIndexes.getValue(key.holderEntityId)
        val started = System.nanoTime()
        return publish(key, held, held.stamp, patched = true, started, started)
    }

    private fun patchable(held: HeldIndex?, stamped: Stamped): Boolean =
        settings.patch && held != null && held.day == stamped.day && held.catalogue == stamped.catalogue &&
            Duration.between(held.builtAt, clock.instant()) < settings.fullBuildAfter

    private fun publish(key: Key, held: HeldIndex, stamp: String, patched: Boolean, started: Long, read: Long): PreparedFlow {
        val published = held.lock.withLock { held.index.publish(key.excluded) }
        val built = problemBuilder.problem(published, held.index.routeOf, held.day)
        val bucketOf = { cls: LotClass -> held.bucketOf.getOrPut(cls.key) { built.scoring.bestBucketCode(cls) ?: NO_BUCKET }.takeIf { it != NO_BUCKET } }
        return solved(key, stamp, built, bucketOf, patched, Timing(started, read, System.nanoTime()))
    }

    private class Timing(val started: Long, val read: Long, val problem: Long)

    private fun solved(key: Key, stamp: String, built: ProblemBuilder.Built, bucketOf: (LotClass) -> String?, patched: Boolean, timing: Timing): PreparedFlow {
        val prepared = PreparedFlow(
            tier = key.tier,
            stamp = stamp,
            builtAt = clock.instant(),
            problem = built.problem,
            scoring = built.scoring,
            state = CoverageAnalyser(bucketOf).solve(built.problem),
            routeOf = problemBuilder.cachedRoutes(),
            patched = patched,
        )
        cache.merge(key, prepared) { old, new -> if (new.builtAt >= old.builtAt) new else old }
        evictVariants(key.holderEntityId)
        val done = System.nanoTime()
        log.debug(
            "Portfolio flow for {} ({}{}) {} in {} ms (read {} ms, problem {} ms, flow {} ms): {} classes, {} obligations, {} groups, {} atoms",
            key.holderEntityId,
            key.tier,
            if (key.excluded.isEmpty()) "" else ", ${key.excluded.size} left out",
            if (patched) "patched" else "built",
            (done - timing.started) / NANOS_PER_MILLI,
            (timing.read - timing.started) / NANOS_PER_MILLI,
            (timing.problem - timing.read) / NANOS_PER_MILLI,
            (done - timing.problem) / NANOS_PER_MILLI,
            built.problem.classes.size,
            built.problem.obligations.size,
            prepared.state.graph.groups.size,
            prepared.state.graph.atoms.size,
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
        if (!settings.warmOnMiss || inFlight.containsKey(key)) return
        background.execute {
            try {
                val stamped = transactions.execute { stamp(key) }
                if (cache[key]?.stamp != stamped.stamp) buildOnce(key, stamped)
            } catch (e: RuntimeException) {
                log.warn("Portfolio flow for {} could not be prepared", key.holderEntityId, e)
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000

        /** A patch reads at most this share of the live lots (one in ten), or [MIN_PATCH_LOTS]; more is a full build. */
        const val PATCH_SHARE = 10
        const val MIN_PATCH_LOTS = 100

        /** Emptied classes beyond this share (one in five) make a full build, which drops them. */
        const val TOMBSTONE_SHARE = 5

        /** [HeldIndex.bucketOf] of a class with no bucket (the map takes no nulls). */
        const val NO_BUCKET = ""
    }
}
