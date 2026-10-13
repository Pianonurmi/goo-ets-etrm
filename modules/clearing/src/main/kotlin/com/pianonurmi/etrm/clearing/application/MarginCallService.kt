package com.pianonurmi.etrm.clearing.application

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.ClearingStatementView
import com.pianonurmi.etrm.clearing.api.MarginCallClosed
import com.pianonurmi.etrm.clearing.api.MarginCallOpened
import com.pianonurmi.etrm.clearing.api.MarginCallQuery
import com.pianonurmi.etrm.clearing.api.MarginCallStatus
import com.pianonurmi.etrm.clearing.api.MarginCallView
import com.pianonurmi.etrm.clearing.api.MarginPostedView
import com.pianonurmi.etrm.clearing.domain.ClearingRepository
import com.pianonurmi.etrm.clearing.domain.ClearingStatementRepository
import com.pianonurmi.etrm.clearing.domain.MarginCallRepository
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Margin calls (ADR 0046): the account's latest statement shows less cash at the member than the initial margin
 * required. The call replaces any earlier open one (the shortfall already includes it); a second person approves the
 * payment from the bank (the ledger books it) or disputes the call with a reason.
 */
@Service
@Transactional
class MarginCallService(
    private val accounts: ClearingRepository,
    private val statements: ClearingStatementRepository,
    private val repository: MarginCallRepository,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : MarginCallQuery {
    /** Called with the statement of the account's latest day once a file is imported. */
    fun evaluate(account: ClearingAccountView, statement: ClearingStatementView, userId: String) {
        val shortfall = statement.cashBalance?.let { statement.initialMargin - it } ?: return
        val open = repository.openCall(account.id)
        if (open != null && open.statementId == statement.id) return
        val now = clock.instant()
        if (open != null) {
            val replaced = open.copy(
                status = MarginCallStatus.CANCELLED,
                decidedBy = userId,
                decidedAt = now,
                reason = "Replaced by the statement of ${statement.businessDate} (version ${statement.version})",
            )
            repository.update(replaced)
            events.publishEvent(MarginCallClosed(replaced))
        }
        if (shortfall.signum() <= 0) return
        val call = MarginCallView(
            id = Ids.newId(),
            accountId = account.id,
            legalEntityId = account.legalEntityId,
            businessDate = statement.businessDate,
            statementId = statement.id,
            amount = shortfall,
            currency = account.currency,
            status = MarginCallStatus.OPEN,
            requestedBy = userId,
            requestedAt = now,
            decidedBy = null,
            decidedAt = null,
            reason = null,
        )
        repository.insert(call)
        events.publishEvent(MarginCallOpened(call))
    }

    /** The inbox decision: [pay] approves the payment from the bank, otherwise the call is disputed. */
    fun decide(callId: UUID, decidedBy: String, pay: Boolean, reason: String?): MarginCallView {
        val call = repository.call(callId, lock = true) ?: throw NotFoundException("MarginCall", callId)
        if (call.status != MarginCallStatus.OPEN) throw ValidationException("The margin call of ${call.businessDate} is ${call.status}")
        if (decidedBy == call.requestedBy) {
            throw SegregationOfDutiesException(
                "A margin call is paid or disputed by someone other than who imported its statement",
            )
        }
        if (!pay && reason.isNullOrBlank()) throw ValidationException("Say why the margin call is disputed")
        val decided = call.copy(
            status = if (pay) MarginCallStatus.PAID else MarginCallStatus.DISPUTED,
            decidedBy = decidedBy,
            decidedAt = clock.instant(),
            reason = reason?.trim()?.ifEmpty { null },
        )
        repository.update(decided)
        events.publishEvent(MarginCallClosed(decided))
        return decided
    }

    private fun readable(legalEntityId: UUID?) {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK, Role.AUDITOR, Role.TRADER, Role.SYSTEM)
        legalEntityId?.let(actor::requireEntity)
    }

    @Transactional(readOnly = true)
    override fun marginCalls(legalEntityId: UUID?, status: MarginCallStatus?): List<MarginCallView> {
        readable(legalEntityId)
        return repository.calls(legalEntityId, status).filter { actorProvider.current().canAccess(it.legalEntityId) }
    }

    @Transactional(readOnly = true)
    override fun marginCall(id: UUID): MarginCallView? = repository.call(id)?.also { readable(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun marginPosted(legalEntityId: UUID): List<MarginPostedView> {
        readable(legalEntityId)
        val open = repository.calls(legalEntityId, MarginCallStatus.OPEN).groupBy { it.accountId }
        return accounts.accounts(legalEntityId).filter { it.active }.map { account ->
            val latest = statements.latestStatementDate(account.id)?.let { statements.currentStatement(account.id, it) }
            MarginPostedView(
                accountId = account.id,
                clearingHouseId = account.clearingHouseId,
                currency = account.currency,
                initialMargin = account.initialMargin,
                cashBalance = latest?.cashBalance,
                openCalls = open[account.id].orEmpty().fold(BigDecimal.ZERO.setScale(2)) { sum, c -> sum + c.amount },
            )
        }
    }
}
