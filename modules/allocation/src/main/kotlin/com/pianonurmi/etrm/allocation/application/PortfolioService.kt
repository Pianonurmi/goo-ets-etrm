package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.PortfolioNode
import com.pianonurmi.etrm.allocation.api.PortfolioQuery
import com.pianonurmi.etrm.allocation.api.PortfolioView
import com.pianonurmi.etrm.allocation.domain.LotClass
import com.pianonurmi.etrm.allocation.domain.ObligationModel
import com.pianonurmi.etrm.allocation.domain.flow.CompressedFlowGraph
import com.pianonurmi.etrm.allocation.domain.flow.FlowDemand
import com.pianonurmi.etrm.allocation.domain.flow.FlowState
import com.pianonurmi.etrm.allocation.domain.flow.SupplyUnit
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.BitSet
import java.util.UUID
import kotlin.concurrent.withLock

/**
 * The GoO book by product bucket, netted through the eligibility flow (ADR 0037). Built on the entity's prepared
 * flow ([PortfolioFlowCache]): per bucket of the hierarchy, its certificates, the obligations only they can serve, a
 * max-flow between the two, and trials on the entity-wide flow for free supply and available to sell.
 */
@Service
class PortfolioService(
    private val flows: PortfolioFlowCache,
    private val buckets: ProductBucketQuery,
    private val criteria: CriteriaService,
    private val marks: GooMarks,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : PortfolioQuery {
    override fun portfolio(holderEntityId: UUID): PortfolioView {
        val actor = actorProvider.current()
        actor.requireRole(Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        actor.requireEntity(holderEntityId)

        val today = LocalDate.now(clock)
        val prepared = flows.prepare(holderEntityId)
        val context = criteria.contextFor(today)
        val hierarchy = buckets.hierarchy(context)
        val bound = buckets.bind(context)
        val boundByCode = bound.buckets.associate { (view, criterion) -> view.code to criterion }
        val classes = prepared.problem.classes
        val members = hierarchy.nodes.associate { node ->
            val criterion = boundByCode.getValue(node.code)
            node.code to BitSet(classes.size).apply { classes.forEach { if (criteria.evaluate(criterion, it.representative)) set(it.index) } }
        }
        val inside = insideObligations(prepared.problem.obligations, hierarchy.nodes.map { it.code }, boundByCode)

        // Trials on the shared flow, under its lock; no database work inside.
        val (residual, available) = prepared.lock.withLock {
            prepared.state.unitResidual() to members.mapValues { (_, bits) -> if (bits.isEmpty) 0L else prepared.state.headroom(bits) }
        }
        val marks = Marks(bound, today)
        val nodes = hierarchy.nodes.map { node ->
            val bits = members.getValue(node.code)
            val mine = inside.getValue(node.code)
            val long = bits.stream().mapToLong { classes[it].quantity }.sum()
            val short = mine.sumOf { it.demand }
            val covered = coveredWithin(prepared.problem.classes, prepared.problem.eligible, mine, bits, prepared.problem.obligations.size)
            val mark = marks.of(node.code, today.year)
            val cheapest = bits.stream().filter { residual[it] > 0 }.mapToObj { marks.valueOf(classes[it]) }.toList().filterNotNull().minOrNull()
            PortfolioNode(
                bucketCode = node.code,
                name = node.name,
                aliases = node.aliases,
                parents = node.parents,
                depth = node.depth,
                longMwh = long,
                shortMwh = short,
                coveredWithinMwh = covered,
                shortAfterNettingMwh = short - covered,
                naiveNetMwh = long - short,
                freeMwh = bits.stream().mapToLong { residual[it] }.sum(),
                availableToSellMwh = available.getValue(node.code),
                markEur = mark?.setScale(PRICE, RoundingMode.HALF_UP),
                cheapestToDeliverEur = cheapest?.setScale(PRICE, RoundingMode.HALF_UP),
                giveAwayPerMwhEur = if (mark != null && cheapest != null) (cheapest - mark).setScale(PRICE, RoundingMode.HALF_UP) else null,
            )
        }
        return PortfolioView(holderEntityId, prepared.stamp, clock.instant(), "EUR", nodes)
    }

    /** Obligations whose criteria are provably contained in each bucket; proved once per distinct criterion. */
    private fun insideObligations(
        obligations: List<ObligationModel>,
        codes: List<String>,
        boundByCode: Map<String, BoundCriterion>,
    ): Map<String, List<ObligationModel>> {
        val byCriterion = obligations.groupBy { criteria.toJson(it.obligation.criterion) }
        return codes.associateWith { code ->
            val bucket = boundByCode.getValue(code)
            byCriterion.values.filter { criteria.implies(it.first().bound, bucket) }.flatten()
        }
    }

    /** The most the bucket's own certificates can cover of the obligations only they can serve. */
    private fun coveredWithin(
        classes: List<LotClass>,
        eligible: List<BitSet>,
        obligations: List<ObligationModel>,
        members: BitSet,
        obligationCount: Int,
    ): Long {
        if (obligations.isEmpty() || members.isEmpty) return 0
        val graph = CompressedFlowGraph.build(
            units = classes.map { SupplyUnit.Held(it.index, it.quantity) },
            demands = obligations.map { FlowDemand(it.index, it.priority, it.demand) },
            obligationCount = obligationCount,
            eligibleUnits = { o -> (eligible[o].clone() as BitSet).apply { and(members) } },
        )
        return FlowState(graph).totalFlow
    }

    /** Official marks, looked up once per bucket and vintage; a class is worth the best price it qualifies for. */
    private inner class Marks(private val bound: BoundBuckets, private val asOf: LocalDate) {
        private val known = HashMap<Pair<String, Int>, BigDecimal?>()

        fun of(code: String, year: Int): BigDecimal? = known.getOrPut(code to year) { marks.mark(code, year, asOf) }

        fun valueOf(cls: LotClass): BigDecimal? = bound.matching(cls.representative).mapNotNull { of(it.code, cls.productionYear) }.maxOrNull()
    }

    private companion object {
        const val PRICE = 4
    }
}
