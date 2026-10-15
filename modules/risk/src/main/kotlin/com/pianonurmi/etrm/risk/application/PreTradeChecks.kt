package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.allocation.api.SaleProjection
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CounterpartyView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.UtilisationStatus
import com.pianonurmi.etrm.risk.domain.CounterpartyGroups
import com.pianonurmi.etrm.risk.domain.MeasurementGaps
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.FindingSeverity
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeView
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate

/** Counterparties that carry no bilateral credit risk to limit. */
private val NO_CREDIT_LIMIT = setOf(CounterpartyType.INTERCOMPANY, CounterpartyType.EXCHANGE, CounterpartyType.CLEARING_HOUSE)

/**
 * Findings for a trade about to be submitted: credit headroom after its potential future exposure (against the last
 * measured exposure), position, tenor and GoO limits it would exceed, and limits already in breach. Nothing is stored.
 */
@Service
@Transactional(readOnly = true)
class PreTradeChecks(
    private val repository: RiskRepository,
    private val referenceData: ReferenceDataQuery,
    private val positions: PositionFigures,
    private val credit: CreditFigures,
    /** GoO measures from allocation (ADR 0040); resolved when used. */
    private val goo: ObjectProvider<GooRiskQuery>,
    private val savepoints: Savepoints,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PreTradeChecks::class.java)

    fun preTradeFindings(trade: TradeView): List<ApprovalFinding> {
        val today = LocalDate.now(clock)
        val limits = repository.activeLimits(trade.legalEntityId, today)
        val counterparty = referenceData.counterparty(trade.counterpartyId)
        return creditFindings(trade, counterparty, limits, today) +
            limits.filter { it.type == LimitType.POSITION_NET && inScope(trade, it.scope) }.mapNotNull { positionFinding(trade, it) } +
            limits.filter { it.type == LimitType.TENOR_MAX_MONTHS && inScope(trade, it.scope) }.mapNotNull { tenorFinding(trade, it, today) } +
            limits.filter { it.type == LimitType.GOO_CONCENTRATION && trade.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN }
                .mapNotNull { concentrationFinding(trade, it) } +
            basisFindings(trade, limits.filter { it.type == LimitType.GOO_BASIS }) +
            limits.filter { (it.type == LimitType.STOP_LOSS_MTD && inScope(trade, it.scope)) || it.type == LimitType.COMPLIANCE_SHORT || it.type in GOO_LIMITS }
                .filter { repository.utilisation(it.id)?.status?.breached == true && relevant(trade, it) }
                .map {
                    if (repository.utilisation(it.id)?.status == UtilisationStatus.NOT_MEASURED) {
                        ApprovalFinding("LIMIT_NOT_MEASURED", FindingSeverity.WARN, "${it.type} limit ${it.id} could not be measured and counts as in breach")
                    } else {
                        ApprovalFinding("LIMIT_IN_BREACH", FindingSeverity.WARN, "${it.type} limit ${it.id} is in breach")
                    }
                }
    }

    private fun relevant(trade: TradeView, limit: RiskLimitView): Boolean = when (limit.type) {
        LimitType.COMPLIANCE_SHORT -> trade.productFamily == ProductFamily.EMISSION_ALLOWANCE
        in GOO_LIMITS -> trade.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN
        else -> true
    }

    /**
     * A GoO sale would hand over the premium of the certificates it takes (ADR 0040, increment 10): the deliveries are
     * projected on the entity's free certificates, cheapest first, and the give-away added to each basis limit's last
     * measure (only deliveries of the limit's vintage when it has one). While the figures are being prepared the check
     * says so and never blocks.
     */
    private fun basisFindings(trade: TradeView, limits: List<RiskLimitView>): List<ApprovalFinding> {
        if (limits.isEmpty() || trade.productFamily != ProductFamily.GUARANTEE_OF_ORIGIN || trade.legs.none { it.side == Side.SELL }) return emptyList()
        val projection = projectSale(trade)
            ?: return listOf(ApprovalFinding("GOO_BASIS", FindingSeverity.INFO, "Give-away not checked: the figures for this entity are being prepared"))
        return limits.mapNotNull { basisFinding(it, projection) }
    }

    /** Null while allocation prepares the figures; empty when allocation is not there or the projection fails. */
    private fun projectSale(trade: TradeView): SaleProjection? {
        val query = goo.ifAvailable ?: return SaleProjection(emptyList())
        return try {
            savepoints.attempt { query.projectSale(trade) }
        } catch (e: RuntimeException) {
            log.warn("GoO basis of trade {} could not be projected", trade.tradeNumber, e)
            SaleProjection(emptyList())
        }
    }

    private fun basisFinding(limit: RiskLimitView, projection: SaleProjection): ApprovalFinding? {
        val deliveries = projection.deliveries.filter { limit.scope.productionYear == null || it.vintageYear == limit.scope.productionYear }
        val added = if (limit.currency == EUR) {
            deliveries.mapNotNull { it.giveAwayEur }.fold(BigDecimal.ZERO, BigDecimal::add)
        } else {
            BigDecimal(deliveries.sumOf { it.giveAwayMwh })
        }
        if (added.signum() <= 0) return null
        val projected = (repository.utilisation(limit.id)?.value ?: BigDecimal.ZERO) + added
        val unit = if (limit.currency == EUR) "EUR" else "MWh"
        val vintage = limit.scope.productionYear?.let { " (vintage $it)" }.orEmpty()
        val text = "the sale would hand over ${added.setScale(0, RoundingMode.HALF_UP)} $unit$vintage of premium; give-away would be " +
            "${projected.setScale(0, RoundingMode.HALF_UP)} of ${limit.amount}"
        return when {
            projected > limit.amount -> ApprovalFinding("GOO_BASIS", FindingSeverity.REQUIRES_RISK_APPROVAL, "Over the limit: $text")
            projected * BigDecimal(PERCENT) >= limit.amount * BigDecimal(limit.warningPct) -> ApprovalFinding("GOO_BASIS", FindingSeverity.WARN, text)
            else -> null
        }
    }

    /**
     * A GoO purchase of the limit's bucket adds to its free certificates once delivered; a sale does not add to it.
     * With a vintage, only its deliveries marked at it count. Projected on the last measured value, in MWh (or at the bucket's mark for a limit in EUR).
     */
    private fun concentrationFinding(trade: TradeView, limit: RiskLimitView): ApprovalFinding? = try {
        savepoints.attempt { concentrationProjection(trade, limit) }
    } catch (e: RuntimeException) {
        log.warn("GoO concentration of trade {} against limit {} could not be checked", trade.tradeNumber, limit.id, e)
        concentrationNotChecked(limit, e.message ?: e.javaClass.simpleName)
    }

    /** The check could not run: the trade is not waved through as if it were within the limit. */
    private fun concentrationNotChecked(limit: RiskLimitView, reason: String) = ApprovalFinding(
        "GOO_CONCENTRATION_NOT_CHECKED",
        FindingSeverity.WARN,
        "Concentration of ${limit.scope.bucketCode} not checked against limit ${limit.id}: $reason",
    )

    private fun concentrationProjection(trade: TradeView, limit: RiskLimitView): ApprovalFinding? {
        val year = limit.scope.productionYear
        val query = goo.ifAvailable ?: return concentrationNotChecked(limit, "GoO measures are not available")
        // A limit on one vintage counts only the deliveries marked at it.
        val vintages = if (year == null) emptyMap() else query.vintages(trade)
        val bought = trade.legs.filter { it.side == Side.BUY && it.bucketCode == limit.scope.bucketCode }
            .sumOf { leg -> leg.deliveries.filter { year == null || vintages[it.id] == year }.sumOf { it.quantity - it.deliveredQuantity } }
        if (bought.signum() <= 0) return null
        val current = repository.utilisation(limit.id)?.value ?: BigDecimal.ZERO
        val added = if (limit.currency == EUR) {
            val mark = query.concentration(limit.legalEntityId!!, limit.scope.bucketCode!!, year)?.markEur
                ?: return concentrationNotChecked(limit, "no mark for ${limit.scope.bucketCode}")
            bought * mark
        } else {
            bought
        }
        val projected = current + added
        val text = "${limit.scope.bucketCode} free certificates would be ${projected.setScale(0, RoundingMode.HALF_UP)} of ${limit.amount}"
        return when {
            projected > limit.amount -> ApprovalFinding("GOO_CONCENTRATION", FindingSeverity.REQUIRES_RISK_APPROVAL, "Over the limit: $text")
            projected * BigDecimal(PERCENT) >= limit.amount * BigDecimal(limit.warningPct) -> ApprovalFinding("GOO_CONCENTRATION", FindingSeverity.WARN, text)
            else -> null
        }
    }

    private fun inScope(trade: TradeView, scope: LimitScope): Boolean =
        (scope.bookId == null || scope.bookId == trade.bookId) &&
            (scope.productFamily == null || scope.productFamily == trade.productFamily) &&
            (scope.allowanceProductCode == null || trade.legs.any { it.allowanceProductCode == scope.allowanceProductCode })

    private fun creditFindings(trade: TradeView, counterparty: CounterpartyView?, limits: List<RiskLimitView>, today: LocalDate): List<ApprovalFinding> {
        if (counterparty == null || counterparty.counterpartyType in NO_CREDIT_LIMIT) return emptyList()
        val ancestry = CounterpartyGroups.ancestry(counterparty.id, credit.parentMap()).toSet()
        val creditLimits = limits.filter { it.type == LimitType.CREDIT_EXPOSURE && it.scope.counterpartyId in ancestry }
        if (creditLimits.isEmpty()) return listOf(ApprovalFinding("NO_CREDIT_LIMIT", FindingSeverity.WARN, "No credit limit covers ${counterparty.code}"))
        val addOns = repository.pfeAddOns()
        return creditLimits.mapNotNull { limit ->
            val gaps = MeasurementGaps()
            val current = credit.creditUsage(limit, today, gaps).first
            val added = credit.pfe(trade, addOns, limit.currency!!, today, gaps)
            val projected = current + added
            val incomplete = if (gaps.isComplete) "" else "; the check is incomplete (${gaps.summary()})"
            val text = "credit exposure to ${counterparty.code} would be ${money(projected)} of ${limit.amount} ${limit.currency} " +
                "(PFE of this trade ${money(added)})$incomplete"
            when {
                projected > limit.amount -> ApprovalFinding("CREDIT_LIMIT", FindingSeverity.REQUIRES_RISK_APPROVAL, "Over the limit: $text")
                projected * BigDecimal(PERCENT) >= limit.amount * BigDecimal(limit.warningPct) ->
                    ApprovalFinding("CREDIT_LIMIT_WARNING", FindingSeverity.WARN, text)
                // Below the limit on figures that left something out: say so rather than pass quietly.
                !gaps.isComplete -> ApprovalFinding(
                    "CREDIT_CHECK_INCOMPLETE",
                    FindingSeverity.WARN,
                    "Credit check for ${counterparty.code} is incomplete: ${gaps.summary()}",
                )
                else -> null
            }
        }
    }

    private fun positionFinding(trade: TradeView, limit: RiskLimitView): ApprovalFinding? {
        val gaps = MeasurementGaps()
        val projected = (positions.positionNet(limit.legalEntityId!!, limit.scope, trade.id, gaps) + positions.netDeliveries(trade, limit.scope, gaps)).abs()
        return if (projected > limit.amount) {
            val incomplete = if (gaps.isComplete) "" else " (incomplete: ${gaps.summary()})"
            ApprovalFinding(
                "POSITION_LIMIT",
                FindingSeverity.REQUIRES_RISK_APPROVAL,
                "Net position would be ${projected.toPlainString()} against a limit of ${limit.amount}$incomplete",
            )
        } else {
            null
        }
    }

    private fun tenorFinding(trade: TradeView, limit: RiskLimitView, today: LocalDate): ApprovalFinding? {
        val months = positions.maxTenor(listOf(trade), limit.scope, today)
        return if (BigDecimal(months) > limit.amount) {
            ApprovalFinding("TENOR_LIMIT", FindingSeverity.REQUIRES_RISK_APPROVAL, "Delivers in $months months; the limit is ${limit.amount.toInt()} months")
        } else {
            null
        }
    }
}
