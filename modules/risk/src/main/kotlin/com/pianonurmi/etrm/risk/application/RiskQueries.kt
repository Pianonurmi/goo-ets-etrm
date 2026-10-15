package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.risk.api.CounterpartyExposureView
import com.pianonurmi.etrm.risk.api.CreditSupportView
import com.pianonurmi.etrm.risk.api.LimitBreachView
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitUtilisationView
import com.pianonurmi.etrm.risk.api.PfeAddOnView
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.RiskQuery
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeQuery
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Limits, utilisation, exposures and breaches as the reader may see them, and the pre-trade recheck of a trade. */
@Service
@Transactional(readOnly = true)
class RiskQueries(
    private val repository: RiskRepository,
    private val trades: TradeQuery,
    /** Every module's pre-trade check (this one's through [RiskApprovalCheck]), for [recheck]; resolved when used. */
    private val checks: ObjectProvider<TradeApprovalCheck>,
    private val preTrade: PreTradeChecks,
    private val actorProvider: ActorProvider,
) : RiskQuery {
    private fun readable(legalEntityId: UUID?) {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.TRADER, Role.AUDITOR)
        legalEntityId?.let(actor::requireEntity)
    }

    /**
     * The pre-trade checks of an existing trade, as they would run on submission (what-if, nothing is stored): this
     * module's limits and credit, and the other modules' checks, such as available to sell (allocation).
     */
    fun recheck(tradeId: UUID): List<ApprovalFinding> {
        val trade = trades.requireTrade(tradeId)
        readable(trade.legalEntityId)
        return checks.orderedStream().toList().takeIf { it.isNotEmpty() }?.flatMap { it.check(trade) } ?: preTrade.preTradeFindings(trade)
    }

    override fun limits(legalEntityId: UUID?, activeOnly: Boolean): List<RiskLimitView> {
        readable(legalEntityId)
        return repository.limits(legalEntityId, includeGroupWide = true, statuses = if (activeOnly) setOf(LimitStatus.ACTIVE) else null)
            .filter { actorProvider.current().canAccess(it.legalEntityId) }
    }

    override fun limit(id: UUID): RiskLimitView? = repository.limit(id)?.also { readable(it.legalEntityId) }

    override fun utilisation(limitId: UUID): LimitUtilisationView? {
        readable((repository.limit(limitId) ?: throw NotFoundException("RiskLimit", limitId)).legalEntityId)
        return repository.utilisation(limitId)
    }

    override fun exposures(legalEntityId: UUID): List<CounterpartyExposureView> {
        readable(legalEntityId)
        return repository.exposures(legalEntityId)
    }

    override fun breaches(legalEntityId: UUID?, openOnly: Boolean): List<LimitBreachView> {
        readable(legalEntityId)
        val ids = repository.limits(legalEntityId, includeGroupWide = true, statuses = null).filter {
            actorProvider.current().canAccess(it.legalEntityId)
        }.map { it.id }
        return if (ids.isEmpty()) emptyList() else repository.breaches(ids.toSet(), openOnly)
    }

    override fun creditSupport(counterpartyId: UUID): List<CreditSupportView> {
        readable(null)
        return repository.creditSupportFor(counterpartyId).filter { actorProvider.current().canAccess(it.legalEntityId) }
    }

    override fun pfeAddOns(): List<PfeAddOnView> = repository.pfeAddOns()
}
