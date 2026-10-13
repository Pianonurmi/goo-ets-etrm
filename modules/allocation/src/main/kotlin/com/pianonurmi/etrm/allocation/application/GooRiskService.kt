package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.DeliveryGiveAway
import com.pianonurmi.etrm.allocation.api.GooBasis
import com.pianonurmi.etrm.allocation.api.GooConcentration
import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.allocation.api.SaleProjection
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.VintageYears
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeView
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.concurrent.withLock

/** GoO measures for risk limits (ADR 0040), from the entity's prepared held flow and its valuation-grade allocation. */
@Service
class GooRiskService(
    private val flows: PortfolioFlowCache,
    private val problemBuilder: ProblemBuilder,
    private val buckets: ProductBucketQuery,
    private val criteria: CriteriaService,
    private val marks: GooMarks,
    private val values: PortfolioValueService,
    private val clock: Clock,
) : GooRiskQuery {
    override fun concentration(holderEntityId: UUID, bucketCode: String, productionYear: Int?): GooConcentration? {
        val today = LocalDate.now(clock)
        val context = criteria.contextFor(today)
        val bucket = buckets.bind(context).buckets.firstOrNull { (view, _) -> view.code == bucketCode }?.second ?: return null
        val prepared = flows.prepare(holderEntityId)
        val members = prepared.problem.classes
            .filter { (productionYear == null || it.productionYear == productionYear) && problemBuilder.inBucket(it, bucket) { context } }
            .map { it.index }
        val residual = prepared.lock.withLock { prepared.state.unitResidual() }
        return GooConcentration(bucketCode, members.sumOf { residual[it] }, marks.mark(bucketCode, productionYear ?: today.year, today))
    }

    override fun unusedExpiringMwh(holderEntityId: UUID, horizonDays: Int, bucketCode: String?, productionYear: Int?): Long {
        val today = LocalDate.now(clock)
        val context = criteria.contextFor(today)
        val bucket = bucketCode?.let { code -> buckets.bind(context).buckets.firstOrNull { (view, _) -> view.code == code }?.second ?: return 0 }
        val horizon = today.plusDays(horizonDays.toLong())
        val prepared = flows.prepare(holderEntityId)
        val expiring = prepared.problem.classes
            .filter { it.expiryDate <= horizon && (productionYear == null || it.productionYear == productionYear) }
            .filter { bucket == null || problemBuilder.inBucket(it, bucket) { context } }
            .map { it.index }
        val residual = prepared.lock.withLock { prepared.state.unitResidual() }
        return expiring.sumOf { residual[it] }
    }

    /**
     * Only give-away actually handed over counts: a sale delivered with an older, cheaper vintage that still qualifies
     * gains, and that gain does not offset another sale's give-away. With [productionYear], only sales marked at it.
     */
    override fun basis(holderEntityId: UUID, productionYear: Int?): GooBasis? = values.compute(holderEntityId, LocalDate.now(clock))?.let { value ->
        if (productionYear == null) {
            GooBasis(value.giveAwayMwh, value.byObligation.mapNotNull { it.giveAwayEur }.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add))
        } else {
            val sales = value.byObligation.filter { it.vintageYear == productionYear }
            GooBasis(sales.sumOf { it.giveAwayMwh }, sales.mapNotNull { it.giveAwayEur }.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add))
        }
    }

    override fun vintages(trade: TradeView): Map<UUID, Int> = trade.legs.filter { it.criterion != null }.flatMap { leg ->
        leg.deliveries.map { d -> d.id to VintageYears.of(criteria.bind(leg.criterion!!, criteria.contextFor(d.deliveryDate)), d.deliveryDate) }
    }.toMap()

    /**
     * Each delivery of the trade's sales takes the free certificates it qualifies for, cheapest first (in delivery date
     * order, so deliveries do not count the same certificate twice). The premium over the sale's own mark is the
     * give-away; certificates without a mark count as worth nothing.
     */
    override fun projectSale(trade: TradeView): SaleProjection? {
        val sales = trade.legs.filter { it.side == Side.SELL && it.criterion != null }
        if (trade.productFamily != ProductFamily.GUARANTEE_OF_ORIGIN || sales.isEmpty()) return SaleProjection(emptyList())
        val own = trade.legs.flatMap { leg -> leg.deliveries.map { it.id } }.toSet()
        val prepared = flows.ifReady(trade.legalEntityId, own) ?: return null
        val today = LocalDate.now(clock)
        val vintages = vintages(trade)
        val (free, worth) = prepared.lock.withLock {
            prepared.state.unitResidual() to prepared.problem.classes.map { prepared.scoring.value(it).second ?: BigDecimal.ZERO }
        }
        val deliveries = sales.flatMap { leg -> leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.map { leg to it } }
            .sortedWith(compareBy({ it.second.deliveryDate }, { it.first.legNo }, { it.second.scheduleNo }))
        return SaleProjection(
            deliveries.map { (leg, delivery) ->
                val vintage = vintages.getValue(delivery.id)
                val mwh = delivery.remainingQuantity.setScale(0, RoundingMode.UP).longValueExact()
                val target = ObligationService.parseAccountRef(leg.counterpartyAccountRef).first
                val eligible = prepared.eligibility.getOrPut(Triple(criteria.toJson(leg.criterion!!), delivery.deliveryDate, target)) {
                    problemBuilder.eligible(prepared.problem.classes, leg.criterion!!, delivery.deliveryDate, target, prepared.routeOf)
                }
                val saleMark = leg.bucketCode?.let { marks.mark(it, vintage, today) }
                var left = mwh
                var premiumMwh = 0L
                var premium = BigDecimal.ZERO
                eligible.stream().boxed().toList()
                    .filter { free[it] > 0 }
                    .sortedWith(compareBy<Int>({ worth[it] }, { prepared.problem.classes[it].expiryDate }, { prepared.problem.classes[it].key }))
                    .forEach { c ->
                        if (left == 0L) return@forEach
                        val take = minOf(left, free[c])
                        free[c] -= take
                        left -= take
                        if (saleMark != null && worth[c] > saleMark) {
                            premiumMwh += take
                            premium += (worth[c] - saleMark) * BigDecimal(take)
                        }
                    }
                DeliveryGiveAway(
                    scheduleId = delivery.id,
                    deliveryDate = delivery.deliveryDate,
                    vintageYear = vintage,
                    bucketCode = leg.bucketCode,
                    mwh = mwh,
                    coveredMwh = mwh - left,
                    giveAwayMwh = premiumMwh,
                    giveAwayEur = saleMark?.let { premium.setScale(2, RoundingMode.HALF_UP) },
                )
            },
        )
    }
}
