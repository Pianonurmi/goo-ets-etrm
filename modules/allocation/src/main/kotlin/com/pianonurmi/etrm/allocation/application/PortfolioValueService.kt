package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.InventoryBucketValue
import com.pianonurmi.etrm.allocation.api.ObligationDeliveryValue
import com.pianonurmi.etrm.allocation.api.PlanGiveAway
import com.pianonurmi.etrm.allocation.api.PlanStatus
import com.pianonurmi.etrm.allocation.api.PortfolioValue
import com.pianonurmi.etrm.allocation.api.PortfolioValueQuery
import com.pianonurmi.etrm.allocation.api.RealisedGiveAway
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.PortfolioValuer
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Portfolio value (ADR 0039): the entity's held book at the prices of a date, on the valuation-grade allocation
 * ([PortfolioValuer]). A management view; valuation shows it next to its run, nothing books it.
 */
@Service
class PortfolioValueService(
    private val flows: PortfolioFlowCache,
    private val plans: AllocationPlanRepository,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : PortfolioValueQuery {
    override fun value(holderEntityId: UUID, asOf: LocalDate): PortfolioValue? {
        authorise(holderEntityId)
        val (built, stamp) = flows.buildAt(holderEntityId, asOf)
        val problem = built.problem
        val assignments = PortfolioValuer.allocate(problem) ?: return null
        val scoring = built.scoring

        val worth = problem.classes.associate { it.index to (scoring.value(it).second ?: BigDecimal.ZERO) }
        val allocatedByClass = HashMap<Int, Long>()
        assignments.forEach { allocatedByClass.merge(it.classIndex, it.quantityMwh, Long::plus) }
        val byBucket = problem.classes.groupBy { scoring.value(it).first to it.productionYear }.map { (key, classes) ->
            InventoryBucketValue(
                bucketCode = key.first,
                productionYear = key.second,
                heldMwh = classes.sumOf { it.quantity },
                valueEur = money(classes.sumOf { worth.getValue(it.index) * BigDecimal(it.quantity) }),
                allocatedMwh = classes.sumOf { allocatedByClass[it.index] ?: 0L },
            )
        }.sortedWith(compareBy<InventoryBucketValue> { it.bucketCode ?: "~" }.thenBy { it.productionYear })

        val byObligationIndex = assignments.groupBy { it.obligationIndex }
        val byObligation = problem.obligations.filter { it.demand > 0 }.map { obl ->
            val mine = byObligationIndex[obl.index].orEmpty()
            val mwh = mine.sumOf { it.quantityMwh }
            val delivered = mine.sumOf { it.score.opportunityCost * BigDecimal(it.quantityMwh) }
            val saleMark = scoring.saleMark(obl)
            ObligationDeliveryValue(
                obligationId = obl.id,
                sourceRef = obl.obligation.sourceRef,
                tradeRef = obl.obligation.tradeRef,
                bucketCode = obl.obligation.bucketCode,
                vintageYear = obl.obligation.vintageYear,
                demandMwh = obl.demand,
                allocatedMwh = mwh,
                cheapestToDeliverEur = if (mwh > 0) delivered.divide(BigDecimal(mwh), PRICE, RoundingMode.HALF_UP) else null,
                saleMarkEur = saleMark?.setScale(PRICE, RoundingMode.HALF_UP),
                giveAwayEur = saleMark?.let { money(mine.sumOf { (it.score.giveAwayPerMwh ?: BigDecimal.ZERO) * BigDecimal(it.quantityMwh) }) },
                transferFeesEur = money(mine.sumOf { it.score.transferCost * BigDecimal(it.quantityMwh) }),
            )
        }

        fun total(of: (com.pianonurmi.etrm.allocation.api.ScoreBreakdown) -> BigDecimal) =
            money(assignments.sumOf { of(it.score) * BigDecimal(it.quantityMwh) })
        val inventory = money(problem.classes.sumOf { worth.getValue(it.index) * BigDecimal(it.quantity) })
        val giveAway = total { it.giveAwayPerMwh ?: BigDecimal.ZERO }
        val fees = total { it.transferCost }
        val haircut = total { it.urgencyBonus }
        val allocated = assignments.sumOf { it.quantityMwh }
        return PortfolioValue(
            holderEntityId = holderEntityId,
            asOf = asOf,
            computedAt = clock.instant(),
            stamp = stamp,
            currency = EUR,
            heldMwh = problem.classes.sumOf { it.quantity },
            inventoryValueEur = inventory,
            allocatedMwh = allocated,
            shortfallMwh = problem.obligations.sumOf { it.demand } - allocated,
            deliveredValueEur = total { it.opportunityCost },
            giveAwayEur = giveAway,
            transferFeesEur = fees,
            expiryHaircutEur = haircut,
            netEur = inventory - giveAway - fees - haircut,
            unpricedMwh = problem.classes.filter { scoring.value(it).second == null }.sumOf { it.quantity },
            byBucket = byBucket,
            byObligation = byObligation,
        )
    }

    override fun realisedGiveAway(holderEntityId: UUID, from: LocalDate, to: LocalDate): RealisedGiveAway {
        authorise(holderEntityId)
        val start = from.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = to.atStartOfDay().toInstant(ZoneOffset.UTC)
        val approved = plans.byEntity(holderEntityId, activeOnly = false)
            .filter { it.status in REALISED && it.approvedAt?.let { at -> at >= start && at < end } == true && it.value != null }
            .map { PlanGiveAway(it.id, it.approvedAt!!, it.allocatedMwh, it.value!!.giveAwayEur) }
            .sortedBy { it.approvedAt }
        return RealisedGiveAway(holderEntityId, from, to, money(approved.sumOf { it.giveAwayEur }), approved)
    }

    private fun authorise(holderEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.ACCOUNTANT, Role.TRADER, Role.SYSTEM)
        actor.requireEntity(holderEntityId)
    }

    private fun money(value: BigDecimal) = value.setScale(2, RoundingMode.HALF_UP)

    private companion object {
        const val EUR = "EUR"
        const val PRICE = 4
        val REALISED =
            setOf(PlanStatus.APPROVED, PlanStatus.RESERVED, PlanStatus.INSTRUCTED, PlanStatus.CONFIRMED, PlanStatus.PARTIALLY_FAILED, PlanStatus.CLOSED)
    }
}
