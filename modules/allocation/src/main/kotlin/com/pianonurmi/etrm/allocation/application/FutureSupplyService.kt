package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.FutureCoverageMonth
import com.pianonurmi.etrm.allocation.api.FutureSupplyItem
import com.pianonurmi.etrm.allocation.api.FutureSupplyQuery
import com.pianonurmi.etrm.allocation.api.FutureSupplyView
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.FutureSupply
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.YearMonth
import java.util.UUID

/** Future supply and its effect on coverage (ADR 0038), from the entity's prepared flow at the expected tier. */
@Service
class FutureSupplyService(
    private val flows: PortfolioFlowCache,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : FutureSupplyQuery {
    override fun futureSupply(holderEntityId: UUID): FutureSupplyView {
        val actor = actorProvider.current()
        actor.requireRole(Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        actor.requireEntity(holderEntityId)

        val prepared = flows.prepare(holderEntityId, tier = SupplyTier.EXPECTED)
        val problem = prepared.problem
        val report = CoverageAnalyser(prepared.scoring::bestBucketCode).analyse(problem, holderEntityId, 0, clock.instant())
        val items = problem.classes.mapNotNull { cls ->
            when (val future = cls.future) {
                null -> null
                is FutureSupply.Contracted -> FutureSupplyItem(
                    kind = future.kind,
                    ref = future.ref,
                    description = future.description,
                    availableFrom = future.availableFrom,
                    quantityMwh = cls.quantity,
                    p90Mwh = cls.quantity,
                    bucketCode = future.bucketCode,
                    tradeNumber = future.tradeNumber,
                    deviceGsrn = null,
                    productionMonth = null,
                )
                is FutureSupply.Expected -> FutureSupplyItem(
                    kind = future.kind,
                    ref = future.ref,
                    description = future.description,
                    availableFrom = future.availableFrom,
                    quantityMwh = cls.quantity,
                    p90Mwh = minOf(future.p90Mwh, cls.quantity),
                    bucketCode = prepared.scoring.bestBucketCode(cls),
                    tradeNumber = null,
                    deviceGsrn = future.gsrn,
                    productionMonth = future.month,
                )
            }
        }.sortedWith(compareBy({ it.availableFrom }, { it.ref }))
        val monthOf = problem.obligations.associate { it.id to YearMonth.from(it.obligation.deliveryDate) }
        val months = report.obligations.groupBy { monthOf.getValue(it.obligationId) }.toSortedMap().map { (month, rows) ->
            FutureCoverageMonth(
                month = month,
                demandMwh = rows.sumOf { it.demandMwh },
                coveredHeldMwh = rows.sumOf { it.coveredMwh },
                coveredWithContractedMwh = rows.sumOf { it.coveredWithContractedMwh ?: it.coveredMwh },
                coveredWithExpectedMwh = rows.sumOf { it.coveredWithExpectedMwh ?: it.coveredMwh },
            )
        }
        return FutureSupplyView(
            holderEntityId = holderEntityId,
            stamp = prepared.stamp,
            computedAt = clock.instant(),
            items = items,
            months = months,
            totalDemandMwh = months.sumOf { it.demandMwh },
            coveredHeldMwh = months.sumOf { it.coveredHeldMwh },
            coveredWithContractedMwh = months.sumOf { it.coveredWithContractedMwh },
            coveredWithExpectedMwh = months.sumOf { it.coveredWithExpectedMwh },
        )
    }
}
