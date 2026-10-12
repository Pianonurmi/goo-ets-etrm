package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.HeadroomCheck
import com.pianonurmi.etrm.allocation.api.HeadroomDemand
import com.pianonurmi.etrm.allocation.api.HeadroomQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.FindingSeverity
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeView
import org.springframework.stereotype.Component
import java.math.RoundingMode

/**
 * Pre-trade check of GoO sales against what the entity can still sell from held certificates (ADR 0036). It reads
 * prepared figures only: when none are ready it says so (INFO) and they are prepared in the background, so a capture
 * is never held up. A sale that does not fit is a warning, not a block: it may be covered by a purchase or by issuance.
 */
@Component
class HeadroomTradeCheck(private val headroom: HeadroomQuery) : TradeApprovalCheck {
    override fun check(trade: TradeView): List<ApprovalFinding> {
        if (trade.productFamily != ProductFamily.GUARANTEE_OF_ORIGIN) return emptyList()
        val demands = trade.legs.filter { it.side == Side.SELL && it.criterion != null }.flatMap { leg ->
            leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.map { delivery ->
                HeadroomDemand(
                    criterion = leg.criterion!!,
                    quantityMwh = delivery.remainingQuantity.setScale(0, RoundingMode.UP).longValueExact(),
                    deliveryDate = delivery.deliveryDate,
                    targetRegistryCode = ObligationService.parseAccountRef(leg.counterpartyAccountRef).first,
                    ref = "leg ${leg.legNo}, delivery ${delivery.scheduleNo}",
                )
            }
        }
        if (demands.isEmpty()) return emptyList()
        // The trade's own schedules are left out of existing demand: an amendment replaces them.
        val ownSchedules = trade.legs.flatMap { leg -> leg.deliveries.map { it.id } }.toSet()
        val result = headroom.checkIfReady(HeadroomCheck(trade.legalEntityId, demands, ownSchedules))
            ?: return listOf(ApprovalFinding(CODE, FindingSeverity.INFO, "Available to sell not checked: the figures for this entity are being prepared"))
        val short = result.demands.filterNot { it.fits }.map { d ->
            val limits = d.binding.take(2).joinToString(", ") { "${it.bucketCode} ${it.productionYear}" }
            ApprovalFinding(
                CODE,
                FindingSeverity.WARN,
                "Sells ${d.requestedMwh} MWh (${d.ref}) but only ${d.headroomMwh} MWh are available without taking certificates from other " +
                    "sales" + if (limits.isEmpty()) "" else "; limited by $limits",
            )
        }
        val joint = result.jointMwh
        return when {
            short.isNotEmpty() -> short
            joint != null && joint < result.requestedMwh -> listOf(
                ApprovalFinding(
                    CODE,
                    FindingSeverity.WARN,
                    "Each delivery fits on its own, but together they need ${result.requestedMwh} MWh and only $joint MWh are available",
                ),
            )
            else -> emptyList()
        }
    }

    private companion object {
        const val CODE = "GOO_HEADROOM"
    }
}
