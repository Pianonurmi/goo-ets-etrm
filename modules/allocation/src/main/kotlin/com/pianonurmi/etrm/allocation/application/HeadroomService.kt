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
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.LotClass
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
 * Available to sell (ADR 0036): a check is a trial on the entity's prepared flow ([PortfolioFlowCache]), about a
 * millisecond, rolled back afterwards. Trials share one flow, so they run one at a time per entry; the lock is held for
 * in-memory work only, never across database calls.
 */
@Service
@EnableConfigurationProperties(AllocationHeadroomSettings::class)
class HeadroomService(
    private val flows: PortfolioFlowCache,
    private val problemBuilder: ProblemBuilder,
    private val buckets: ProductBucketQuery,
    private val criteria: CriteriaService,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
    private val settings: AllocationHeadroomSettings = AllocationHeadroomSettings(),
) : HeadroomQuery {
    override fun check(command: HeadroomCheck): HeadroomResult {
        authorise(command.holderEntityId)
        return evaluate(flows.prepare(command.holderEntityId, command.excludeSourceRefs, command.tier), command)
    }

    // No role check: pre-trade checks call it inside a capture the caller may already make.
    override fun checkIfReady(command: HeadroomCheck): HeadroomResult? =
        flows.ifReady(command.holderEntityId, command.excludeSourceRefs, command.tier)?.let { evaluate(it, command) }

    override fun table(holderEntityId: UUID, tier: SupplyTier): HeadroomTable {
        authorise(holderEntityId)
        val prepared = flows.prepare(holderEntityId, tier = tier)
        return prepared.table ?: tableOf(holderEntityId, prepared).also { prepared.table = it }
    }

    private fun authorise(holderEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        actor.requireEntity(holderEntityId)
    }

    private fun evaluate(prepared: PreparedFlow, command: HeadroomCheck): HeadroomResult {
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
                tier = prepared.tier,
                stamp = prepared.stamp,
                computedAt = clock.instant(),
                demands = each,
                requestedMwh = requested,
                jointMwh = joint,
                fits = joint?.let { it >= requested } ?: each.all { it.fits },
            )
        }
    }

    private fun eligibility(prepared: PreparedFlow, demand: HeadroomDemand): BitSet =
        prepared.eligibility.getOrPut(Triple(criteria.toJson(demand.criterion), demand.deliveryDate, demand.targetRegistryCode)) {
            problemBuilder.eligible(prepared.problem.classes, demand.criterion, demand.deliveryDate, demand.targetRegistryCode, prepared.routeOf)
        }

    /** Binding atoms by bucket and production year, with the MWh of them the demand could use. */
    private fun binding(prepared: PreparedFlow, atoms: IntArray, eligible: BitSet): List<BindingSupply> {
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
     * One row per bucket and vintage the entity holds or expects, one figure per month-end. Bucket membership is
     * evaluated once per class (a purchase by containment); each cell is then the members of the vintage available by
     * the date and outliving it, and one trial.
     */
    private fun tableOf(holderEntityId: UUID, prepared: PreparedFlow): HeadroomTable {
        val today = LocalDate.now(clock)
        val dates = (0 until settings.months).map { YearMonth.from(today).plusMonths(it.toLong()).atEndOfMonth() }
        val classes = prepared.problem.classes
        val context = criteria.contextFor(today)
        val bound = buckets.bind(context)
        val members = bound.buckets.associate { (view, criterion) ->
            view.code to BitSet(classes.size).apply { classes.forEach { if (problemBuilder.inBucket(it, criterion) { context }) set(it.index) } }
        }
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
                        heldMwh = ofYear.stream().filter { classes[it].future == null }.mapToLong { classes[it].quantity }.sum(),
                        futureMwh = ofYear.stream().filter { classes[it].future != null }.mapToLong { classes[it].quantity }.sum(),
                        headroomMwh = dates.map { date ->
                            val live = BitSet(classes.size).apply { ofYear.stream().filter { usableOn(classes[it], date) }.forEach { set(it) } }
                            if (live.isEmpty) 0 else prepared.state.headroom(live)
                        },
                    )
                }
            }
        }.sortedWith(compareBy<HeadroomRow> { sortOrder[it.bucketCode] ?: Int.MAX_VALUE }.thenBy { it.bucketCode }.thenBy { it.vintageYear })
        return HeadroomTable(holderEntityId, prepared.tier, prepared.stamp, clock.instant(), dates, rows)
    }

    private fun usableOn(cls: LotClass, date: LocalDate): Boolean = cls.expiryDate > date && (cls.future?.availableFrom ?: date) <= date
}
