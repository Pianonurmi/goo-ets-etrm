package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.BindingSupply
import com.pianonurmi.etrm.allocation.api.DemandHeadroom
import com.pianonurmi.etrm.allocation.api.HeadroomCheck
import com.pianonurmi.etrm.allocation.api.HeadroomDemand
import com.pianonurmi.etrm.allocation.api.HeadroomQuery
import com.pianonurmi.etrm.allocation.api.HeadroomResult
import com.pianonurmi.etrm.allocation.api.HeadroomRow
import com.pianonurmi.etrm.allocation.api.HeadroomTable
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.registry.api.TransferRoute
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.BitSet
import java.util.UUID
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
 * ```
 */
@ConfigurationProperties(prefix = "etrm.allocation.headroom")
data class AllocationHeadroomSettings(val months: Int = 6, val warmOnMiss: Boolean = true, val maxVariants: Int = 16)

/**
 * Available to sell (ADR 0036). Per entity, the held tier is prepared once: live lots, open obligations less what
 * approved plans have already reserved for them, and the solved compressed flow. A check is then a trial on that flow
 * (about a millisecond), rolled back afterwards.
 *
 * The prepared figures carry a stamp (the entity's holding stamp, its open obligations, the active buckets and the
 * date) and every read compares it with the database, so a change made through any pod is seen on the next read
 * without events. Trials share one flow, so they run one at a time per entry; the lock is held for in-memory work
 * only, never across database calls.
 */
@Service
@EnableConfigurationProperties(AllocationHeadroomSettings::class)
class HeadroomService(
    private val obligations: ObligationRepository,
    private val plans: AllocationPlanRepository,
    private val problemBuilder: ProblemBuilder,
    private val buckets: ProductBucketQuery,
    private val criteria: CriteriaService,
    private val lotQuery: LotQuery,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
    private val transactions: TransactionOperations = TransactionOperations.withoutTransaction(),
    private val settings: AllocationHeadroomSettings = AllocationHeadroomSettings(),
) : HeadroomQuery {
    private val log = LoggerFactory.getLogger(HeadroomService::class.java)

    private data class Key(val holderEntityId: UUID, val excluded: Set<UUID>)

    private data class EligibilityKey(val criterion: String, val deliveryDate: LocalDate, val targetRegistryCode: String?)

    private class Prepared(
        val stamp: String,
        val builtAt: Instant,
        val problem: AllocationProblem,
        val state: FlowState,
        val routeOf: (String, String) -> TransferRoute,
    ) {
        val lock = ReentrantLock()
        val eligibility = ConcurrentHashMap<EligibilityKey, BitSet>()

        @Volatile
        var table: HeadroomTable? = null
    }

    private val cache = ConcurrentHashMap<Key, Prepared>()
    private val building: MutableSet<Key> = ConcurrentHashMap.newKeySet()
    private val background = Executors.newVirtualThreadPerTaskExecutor()

    override fun check(command: HeadroomCheck): HeadroomResult {
        authorise(command.holderEntityId)
        return evaluate(prepare(command), command)
    }

    // No role check: pre-trade checks call it inside a capture the caller may already make.
    override fun checkIfReady(command: HeadroomCheck): HeadroomResult? {
        val key = transactions.execute { key(command) }
        val stamp = transactions.execute { stamp(key.holderEntityId) }
        val prepared = cache[key]?.takeIf { it.stamp == stamp }
        if (prepared == null) {
            warmInBackground(key)
            return null
        }
        return evaluate(prepared, command)
    }

    override fun table(holderEntityId: UUID): HeadroomTable {
        authorise(holderEntityId)
        val prepared = prepare(HeadroomCheck(holderEntityId, emptyList()))
        return prepared.table ?: tableOf(holderEntityId, prepared).also { prepared.table = it }
    }

    @PreDestroy
    fun close() {
        background.shutdownNow()
    }

    private fun authorise(holderEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        actor.requireEntity(holderEntityId)
    }

    /** Prepared figures for the command's entity and exclusions, built now when missing or out of date. */
    private fun prepare(command: HeadroomCheck): Prepared = transactions.execute {
        val key = key(command)
        val stamp = stamp(key.holderEntityId)
        cache[key]?.takeIf { it.stamp == stamp } ?: build(key, stamp)
    }

    /** Only exclusions that name an open obligation of the entity matter, so most amendments share the base entry. */
    private fun key(command: HeadroomCheck): Key {
        val excluded = command.excludeSourceRefs.filterTo(HashSet()) { ref ->
            obligations.bySource(ref)?.let { it.holderEntityId == command.holderEntityId && it.remainingMwh > 0 } == true
        }
        return Key(command.holderEntityId, excluded)
    }

    private fun stamp(holderEntityId: UUID): String {
        val catalogue = buckets.buckets(true).joinToString(",") { "${it.code}@${it.version}" }.hashCode()
        return "${lotQuery.holdingStamp(holderEntityId)}|${obligations.openStamp(holderEntityId)}|$catalogue|${LocalDate.now(clock)}"
    }

    private fun build(key: Key, stamp: String): Prepared {
        val started = System.nanoTime()
        val snapshot = lotQuery.snapshot(key.holderEntityId)
        val open = withoutInFlight(key.holderEntityId, obligations.open(key.holderEntityId).filter { it.sourceRef !in key.excluded })
        val built = problemBuilder.build(snapshot, open, emptyMap(), LocalDate.now(clock))
        val state = CoverageAnalyser(built.scoring::bestBucketCode).solve(built.problem)
        val prepared = Prepared(
            stamp = stamp,
            builtAt = clock.instant(),
            problem = built.problem,
            state = state,
            routeOf = problemBuilder.cachedRoutes(),
        )
        cache.merge(key, prepared) { old, new -> if (new.builtAt >= old.builtAt) new else old }
        evictVariants(key.holderEntityId)
        log.debug(
            "Available to sell for {} prepared in {} ms: {} classes, {} obligations",
            key.holderEntityId,
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
        val variants = cache.entries.filter { it.key.holderEntityId == holderEntityId && it.key.excluded.isNotEmpty() }
        if (variants.size > settings.maxVariants) {
            variants.sortedBy { it.value.builtAt }.take(variants.size - settings.maxVariants).forEach { cache.remove(it.key, it.value) }
        }
    }

    private fun warmInBackground(key: Key) {
        if (!settings.warmOnMiss || !building.add(key)) return
        background.execute {
            try {
                transactions.execute {
                    val stamp = stamp(key.holderEntityId)
                    if (cache[key]?.stamp != stamp) build(key, stamp)
                }
            } catch (e: Exception) {
                log.warn("Available to sell for {} could not be prepared", key.holderEntityId, e)
            } finally {
                building.remove(key)
            }
        }
    }

    private fun evaluate(prepared: Prepared, command: HeadroomCheck): HeadroomResult {
        // Eligibility may look up transfer routes (database), so it is settled before the flow is locked.
        val demands = command.demands.map { it to eligibility(prepared, it) }
        return prepared.lock.withLock {
            val each = demands.map { (demand, eligible) ->
                val detail = prepared.state.headroomDetail(eligible)
                val fits = detail.mwh >= demand.quantityMwh
                DemandHeadroom(
                    ref = demand.ref,
                    requestedMwh = demand.quantityMwh,
                    headroomMwh = detail.mwh,
                    freeMwh = detail.freeMwh,
                    fits = fits,
                    binding = if (fits) emptyList() else binding(prepared, detail.bindingAtoms, eligible),
                )
            }
            val requested = command.demands.sumOf { it.quantityMwh }
            val joint = prepared.state.jointHeadroom(demands.map { (demand, eligible) -> eligible to demand.quantityMwh })
            HeadroomResult(
                holderEntityId = command.holderEntityId,
                stamp = prepared.stamp,
                computedAt = clock.instant(),
                demands = each,
                requestedMwh = requested,
                jointMwh = joint,
                fits = joint?.let { it >= requested } ?: each.all { it.fits },
            )
        }
    }

    private fun eligibility(prepared: Prepared, demand: HeadroomDemand): BitSet =
        prepared.eligibility.getOrPut(EligibilityKey(criteria.toJson(demand.criterion), demand.deliveryDate, demand.targetRegistryCode)) {
            problemBuilder.eligible(prepared.problem.classes, demand.criterion, demand.deliveryDate, demand.targetRegistryCode, prepared.routeOf)
        }

    /** Binding atoms by bucket and production year, with the MWh of them the demand could use. */
    private fun binding(prepared: Prepared, atoms: IntArray, eligible: BitSet): List<BindingSupply> {
        val graph = prepared.state.graph
        val byKey = LinkedHashMap<Pair<String, Int>, Long>()
        atoms.forEach { a ->
            val atom = graph.atoms[a]
            val mwh = atom.unitIndexes.filter { eligible[it] }.sumOf { graph.units[it].quantityMwh }

            @Suppress("UNCHECKED_CAST")
            val key = atom.key as Pair<String, Int>
            byKey.merge(key, mwh, Long::plus)
        }
        return byKey.map { (key, mwh) -> BindingSupply(key.first, key.second, mwh) }
            .sortedWith(compareByDescending<BindingSupply> { it.eligibleMwh }.thenBy { it.bucketCode }.thenBy { it.productionYear })
    }

    /**
     * One row per bucket and vintage the entity holds, one figure per month-end. Bucket membership is evaluated once
     * per class; each cell is then the members of the vintage that outlive the date, and one trial.
     */
    private fun tableOf(holderEntityId: UUID, prepared: Prepared): HeadroomTable {
        val today = LocalDate.now(clock)
        val dates = (0 until settings.months).map { YearMonth.from(today).plusMonths(it.toLong()).atEndOfMonth() }
        val classes = prepared.problem.classes
        val bound = buckets.bind(criteria.contextFor(today))
        val members = bound.buckets.associate { (view, _) -> view.code to BitSet(classes.size) }
        classes.forEach { cls -> bound.matching(cls.representative).forEach { members.getValue(it.code).set(cls.index) } }
        val years = classes.map { it.productionYear }.distinct().sorted()
        val sortOrder = bound.buckets.associate { (view, _) -> view.code to view.sortOrder }
        val rows = prepared.lock.withLock {
            members.flatMap { (code, bits) ->
                years.mapNotNull { year ->
                    val ofYear = BitSet(classes.size).apply { bits.stream().filter { classes[it].productionYear == year }.forEach { set(it) } }
                    if (ofYear.isEmpty) return@mapNotNull null
                    HeadroomRow(
                        bucketCode = code,
                        vintageYear = year,
                        heldMwh = ofYear.stream().mapToLong { classes[it].quantity }.sum(),
                        headroomMwh = dates.map { date ->
                            val live = BitSet(classes.size).apply { ofYear.stream().filter { classes[it].expiryDate > date }.forEach { set(it) } }
                            if (live.isEmpty) 0 else prepared.state.headroom(live)
                        },
                    )
                }
            }
        }.sortedWith(compareBy<HeadroomRow> { sortOrder[it.bucketCode] ?: Int.MAX_VALUE }.thenBy { it.bucketCode }.thenBy { it.vintageYear })
        return HeadroomTable(holderEntityId, prepared.stamp, clock.instant(), dates, rows)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}
