package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.GooBasis
import com.pianonurmi.etrm.allocation.api.GooConcentration
import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import org.springframework.stereotype.Service
import java.math.BigDecimal
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
    override fun concentration(holderEntityId: UUID, bucketCode: String): GooConcentration? {
        val today = LocalDate.now(clock)
        val context = criteria.contextFor(today)
        val bucket = buckets.bind(context).buckets.firstOrNull { (view, _) -> view.code == bucketCode }?.second ?: return null
        val prepared = flows.prepare(holderEntityId)
        val members = prepared.problem.classes.filter { problemBuilder.inBucket(it, bucket) { context } }.map { it.index }
        val residual = prepared.lock.withLock { prepared.state.unitResidual() }
        return GooConcentration(bucketCode, members.sumOf { residual[it] }, marks.mark(bucketCode, today.year, today))
    }

    override fun unusedExpiringMwh(holderEntityId: UUID, horizonDays: Int, bucketCode: String?): Long {
        val today = LocalDate.now(clock)
        val context = criteria.contextFor(today)
        val bucket = bucketCode?.let { code -> buckets.bind(context).buckets.firstOrNull { (view, _) -> view.code == code }?.second ?: return 0 }
        val horizon = today.plusDays(horizonDays.toLong())
        val prepared = flows.prepare(holderEntityId)
        val expiring = prepared.problem.classes
            .filter { it.expiryDate <= horizon && (bucket == null || problemBuilder.inBucket(it, bucket) { context }) }
            .map { it.index }
        val residual = prepared.lock.withLock { prepared.state.unitResidual() }
        return expiring.sumOf { residual[it] }
    }

    /**
     * Only give-away actually handed over counts: a sale delivered with an older, cheaper vintage that still qualifies
     * gains, and that gain does not offset another sale's give-away.
     */
    override fun basis(holderEntityId: UUID): GooBasis? = values.compute(holderEntityId, LocalDate.now(clock))?.let { value ->
        GooBasis(value.giveAwayMwh, value.byObligation.mapNotNull { it.giveAwayEur }.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add))
    }
}
