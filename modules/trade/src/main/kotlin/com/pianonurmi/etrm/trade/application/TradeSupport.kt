package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

/** The user the system acts as: an option's exercise, a matched confirmation. */
internal const val SYSTEM_USER = "system"

/** The current user needs one of [roles] and access to the trade's entity. */
internal fun ActorProvider.authorise(legalEntityId: UUID, vararg roles: Role) {
    val actor = current()
    actor.requireRole(*roles)
    actor.requireEntity(legalEntityId)
}

/** Loads and saves trade versions and announces the obligations they open. */
@Component
class TradeStore(private val repository: TradeRepository, private val events: ApplicationEventPublisher) {
    fun find(id: UUID): TradeView = repository.find(id) ?: throw NotFoundException("Trade", id)

    fun locked(id: UUID): TradeView = repository.lock(id) ?: throw NotFoundException("Trade", id)

    fun save(trade: TradeView, actor: String, reason: String?) {
        if (!repository.saveVersion(trade, actor, reason)) throw StaleVersionException("Trade", trade.id, trade.version - 1, -1)
    }

    /** Approved trades of entities whose obligations open at approval oblige delivery from now on. */
    fun openObligationsIfDue(trade: TradeView) {
        if (trade.status == TradeStatus.APPROVED && trade.obligationsOpen) obligationsOf(trade).forEach { events.publishEvent(DeliveryObligationOpened(it)) }
    }

    fun obligationsOf(trade: TradeView): List<DeliveryObligationView> = trade.legs.flatMap { leg ->
        leg.deliveries.map { d ->
            DeliveryObligationView(
                scheduleId = d.id,
                tradeId = trade.id,
                tradeNumber = trade.tradeNumber,
                legId = leg.id,
                legalEntityId = trade.legalEntityId,
                bookId = trade.bookId,
                counterpartyId = trade.counterpartyId,
                side = leg.side,
                productFamily = trade.productFamily,
                energyCarrier = leg.energyCarrier,
                allowanceProductCode = leg.allowanceProductCode,
                criteriaId = leg.criteriaId,
                criterion = leg.criterion,
                quantity = d.quantity,
                deliveredQuantity = d.deliveredQuantity,
                deliveryWindow = d.deliveryWindow,
                deliveryDate = d.deliveryDate,
                deliveryAccountId = leg.deliveryAccountId,
                counterpartyAccountRef = leg.counterpartyAccountRef,
                counterpartyAccountId = leg.counterpartyAccountId,
                price = leg.price,
                priceCurrency = leg.priceCurrency,
                priceType = leg.priceType,
                status = d.status,
                priceIndex = leg.priceIndex,
                bucketCode = leg.bucketCode,
                bucketMatch = leg.bucketMatch,
            )
        }
    }
}

/** Whether a trade needs a second person's approval, and the pre-trade checks of every module. */
@Component
class TradeApprovalPolicy(
    private val fxRates: FxRates,
    /** Trades with notional at or above this amount (in [thresholdCurrency]) need four-eyes approval; 0 means every trade. */
    @Value("\${etrm.trade.approval-threshold:0}") private val approvalThreshold: BigDecimal,
    @Value("\${etrm.trade.approval-threshold-currency:EUR}") private val thresholdCurrency: String,
    /** Pre-trade checks of other modules, resolved when used (they read trades themselves). */
    private val checks: ObjectProvider<TradeApprovalCheck>,
) {
    /**
     * Whether a trade needs a second person's approval: its notional, converted at the trade date's rate, against the
     * threshold. A trade whose currency cannot be converted is treated as above it.
     */
    fun requiresApproval(trade: TradeView): Boolean {
        if (approvalThreshold.signum() == 0 || trade.requiresRiskApproval) return true
        val converted = trade.legs.map { leg ->
            fxRates.rate(trade.tradeDate, leg.priceCurrency.currencyCode, thresholdCurrency)?.let { leg.notional * it.rate } ?: return true
        }
        return converted.fold(BigDecimal.ZERO, BigDecimal::add) >= approvalThreshold
    }

    fun runChecks(trade: TradeView): List<ApprovalFinding> = checks.orderedStream().toList().flatMap { it.check(trade) }
}
