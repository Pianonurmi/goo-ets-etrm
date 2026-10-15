package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeAggregate
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Captures trades: checked, submitted for approval when they need it, with the mirror of an intercompany trade. */
@Service
@Transactional
class TradeCapture(
    private val repository: TradeRepository,
    private val referenceData: ReferenceDataQuery,
    private val builder: TradeBuilder,
    private val policy: TradeApprovalPolicy,
    private val store: TradeStore,
    private val mirror: IntercompanyMirror,
    private val lifecycle: TradeLifecycle,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) {
    fun capture(command: CaptureTrade): TradeView {
        actorProvider.authorise(command.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        val actor = actorProvider.current().userId
        val counterparty = referenceData.requireCounterparty(command.counterpartyId)
        val trade = builder.build(command, actor).let { it.copy(approvalFindings = policy.runChecks(it)) }
        repository.insert(trade, actor, command.reason ?: "captured")
        val submitted = TradeAggregate(trade).submitForApproval(policy.requiresApproval(trade))
        repository.saveVersion(submitted, actor, "submitted")
        events.publishEvent(TradeCaptured(submitted))
        store.openObligationsIfDue(submitted)
        if (counterparty.counterpartyType == CounterpartyType.INTERCOMPANY) mirror.capture(command, submitted, counterparty.linkedLegalEntityId!!, actor)
        return submitted
    }

    /**
     * Captures, approves and confirms a trade the system makes on another's behalf: the future an option is exercised
     * into (ADR 0047). No pre-trade checks or approval: the exercise is not a decision.
     */
    fun captureConfirmed(command: CaptureTrade, reason: String, confirmationRef: String): TradeView {
        val trade = builder.build(command, SYSTEM_USER)
        repository.insert(trade, SYSTEM_USER, reason)
        val approved = TradeAggregate(trade).submitForApproval(requiresApproval = false)
        repository.saveVersion(approved, SYSTEM_USER, "approved: $reason")
        events.publishEvent(TradeCaptured(approved))
        store.openObligationsIfDue(approved)
        // Confirmed now: the lifecycle stamps the time.
        return lifecycle.confirmAs(approved.id, ConfirmTrade(confirmationRef, "SYSTEM", null), SYSTEM_USER)
    }
}
