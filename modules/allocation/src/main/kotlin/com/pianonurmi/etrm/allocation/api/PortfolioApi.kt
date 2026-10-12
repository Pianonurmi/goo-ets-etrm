package com.pianonurmi.etrm.allocation.api

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * One product bucket in the entity's GoO book, netted through the eligibility flow (ADR 0037). A specific certificate
 * can deliver a generic sale but not the reverse, so long and short of a bucket only net where the certificates
 * qualify:
 * - [longMwh]: held certificates inside the bucket;
 * - [shortMwh]: open obligations that only certificates inside the bucket can serve (their criteria are provably
 *   contained in it);
 * - [coveredWithinMwh]: of those, what the bucket's own certificates can cover (a max-flow inside the bucket);
 * - [shortAfterNettingMwh] = short − covered within: has to be bought or issued;
 * - [naiveNetMwh] = long + future − short: what a position that ignores eligibility shows. It can look flat or long while
 *   the bucket is short, when its certificates are the wrong ones for its obligations;
 * - [futureMwh]: purchases and expected issuance (P50) inside the bucket, at the view's tier; they join long in the
 *   netting, covered within and free figures;
 * - [freeMwh]: supply inside the bucket that no obligation uses in the entity-wide flow;
 * - [availableToSellMwh]: what a new sale of exactly this bucket, delivered today, could still receive;
 * - [markEur]: the bucket's mark for the current vintage; [cheapestToDeliverEur]: the lowest value among its free
 *   certificates (what such a sale would take); [giveAwayPerMwhEur] = cheapest to deliver − mark: the premium handed
 *   over if the bucket were sold now (management view, never booked).
 */
data class PortfolioNode(
    val bucketCode: String,
    val name: String,
    val aliases: List<String>,
    val parents: List<String>,
    val depth: Int,
    val longMwh: Long,
    val shortMwh: Long,
    val coveredWithinMwh: Long,
    val shortAfterNettingMwh: Long,
    val naiveNetMwh: Long,
    val futureMwh: Long,
    val freeMwh: Long,
    val availableToSellMwh: Long,
    val markEur: BigDecimal?,
    val cheapestToDeliverEur: BigDecimal?,
    val giveAwayPerMwhEur: BigDecimal?,
)

/** The bucket hierarchy with the entity's figures, parents before children, counting supply up to [tier]. */
data class PortfolioView(
    val holderEntityId: UUID,
    val tier: SupplyTier,
    val stamp: String,
    val computedAt: Instant,
    val currency: String,
    val nodes: List<PortfolioNode>,
)

interface PortfolioQuery {
    fun portfolio(holderEntityId: UUID, tier: SupplyTier = SupplyTier.HELD): PortfolioView
}
