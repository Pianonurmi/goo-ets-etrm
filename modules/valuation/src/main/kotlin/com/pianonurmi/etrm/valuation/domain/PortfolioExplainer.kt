package com.pianonurmi.etrm.valuation.domain

import com.pianonurmi.etrm.valuation.api.PortfolioBridgeView
import com.pianonurmi.etrm.valuation.api.PortfolioCause
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Splits the move of the portfolio value between two runs into its causes (ADR 0039). The causes add up to the
 * move exactly; [PortfolioCause.UNEXPLAINED] takes the rounding.
 * - trade MtM: the change in the GoO trades' official MtM;
 * - inventory price: certificates held in both runs (by bucket and year, the smaller quantity) at the change in
 *   their value per MWh; inventory in and out: the rest of the change in inventory value (received, delivered,
 *   cancelled, expired, or moved to another bucket);
 * - give-away, reallocation (transfer fees) and expiry (haircut): the change in each cost, with the sign it has on
 *   the value.
 */
object PortfolioExplainer {
    fun explain(prior: PortfolioBridgeView, current: PortfolioBridgeView): Map<PortfolioCause, BigDecimal> {
        val before = prior.buckets.associateBy { it.bucketCode to it.productionYear }
        val price = current.buckets.sumOf { now ->
            val then = before[now.bucketCode to now.productionYear]
            if (then == null || then.heldMwh <= 0 || now.heldMwh <= 0) {
                BigDecimal.ZERO
            } else {
                val change = now.valueEur.divide(BigDecimal(now.heldMwh), SCALE, RoundingMode.HALF_UP) -
                    then.valueEur.divide(BigDecimal(then.heldMwh), SCALE, RoundingMode.HALF_UP)
                change * BigDecimal(minOf(now.heldMwh, then.heldMwh))
            }
        }.setScale(2, RoundingMode.HALF_UP)
        val causes = linkedMapOf(
            PortfolioCause.TRADE_MTM to current.tradeMtm - prior.tradeMtm,
            PortfolioCause.INVENTORY_PRICE to price,
            PortfolioCause.INVENTORY_IN_OUT to current.inventoryValue - prior.inventoryValue - price,
            PortfolioCause.GIVE_AWAY to prior.giveAway - current.giveAway,
            PortfolioCause.REALLOCATION to prior.transferFees - current.transferFees,
            PortfolioCause.EXPIRY to prior.expiryHaircut - current.expiryHaircut,
        )
        causes[PortfolioCause.UNEXPLAINED] = current.portfolioValue - prior.portfolioValue - causes.values.fold(BigDecimal.ZERO, BigDecimal::add)
        return causes.mapValues { it.value.setScale(2, RoundingMode.HALF_UP) }
    }

    private const val SCALE = 10
}
