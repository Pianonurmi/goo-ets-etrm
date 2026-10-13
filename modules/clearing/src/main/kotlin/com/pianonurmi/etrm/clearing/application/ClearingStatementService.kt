package com.pianonurmi.etrm.clearing.application

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.ClearingBreakClosed
import com.pianonurmi.etrm.clearing.api.ClearingBreakOpened
import com.pianonurmi.etrm.clearing.api.ClearingBreakStatus
import com.pianonurmi.etrm.clearing.api.ClearingBreakView
import com.pianonurmi.etrm.clearing.api.ClearingStatementCommand
import com.pianonurmi.etrm.clearing.api.ClearingStatementImported
import com.pianonurmi.etrm.clearing.api.ClearingStatementQuery
import com.pianonurmi.etrm.clearing.api.ClearingStatementView
import com.pianonurmi.etrm.clearing.api.StatementImportView
import com.pianonurmi.etrm.clearing.api.VariationMarginComputed
import com.pianonurmi.etrm.clearing.domain.ClearingRepository
import com.pianonurmi.etrm.clearing.domain.ClearingStatementRepository
import com.pianonurmi.etrm.clearing.domain.MarginReconciliation
import com.pianonurmi.etrm.clearing.domain.StatementFile
import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The clearing member's daily statements (ADR 0046): imported from the member's file, versioned when re-sent with other
 * figures, and reconciled against the computed variation margin. A day whose statement and margin differ has one
 * open break; it closes by itself once they agree, or when a second person accepts the member's figures.
 */
@Service
@Transactional
class ClearingStatementService(
    private val accounts: ClearingRepository,
    private val repository: ClearingStatementRepository,
    private val calls: MarginCallService,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : ClearingStatementCommand, ClearingStatementQuery {
    override fun importStatements(accountId: UUID, fileName: String?, csv: String): StatementImportView {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.MIDDLE_OFFICE)
        val account = accounts.account(accountId) ?: throw NotFoundException("ClearingAccount", accountId)
        actor.requireEntity(account.legalEntityId)
        if (!account.active) throw ValidationException("Clearing account ${account.code} is closed")
        val days = StatementFile.parse(csv, account.code, account.currency)
        repository.lockAccount(account.id)
        var unchanged = 0
        val imported = days.mapNotNull { day ->
            store(account, day, fileName?.take(MAX_FILE_NAME), actor.userId).also { if (it == null) unchanged++ }
        }
        // The latest day decides on a margin call, once per file.
        val latest = repository.latestStatementDate(account.id)
        imported.lastOrNull { it.businessDate == latest }?.let { calls.evaluate(account, it, actor.userId) }
        val breaks = days.mapNotNull { repository.openBreak(account.id, it.businessDate) }
        return StatementImportView(imported, unchanged, breaks)
    }

    /** Stores the day as the current statement unless it is identical to it; returns null when nothing changed. */
    private fun store(account: ClearingAccountView, day: StatementFile.Day, fileName: String?, userId: String): ClearingStatementView? {
        val hash = HashChain.next(
            null,
            Json.canonical(
                mapOf(
                    "account" to account.code,
                    "date" to day.businessDate.toString(),
                    "im" to day.initialMargin.toPlainString(),
                    "fees" to day.fees.toPlainString(),
                    "cash" to day.cashBalance?.toPlainString(),
                    "lines" to day.lines.map {
                        listOf(
                            it.contractCode,
                            it.position.stripTrailingZeros().toPlainString(),
                            it.settlementPrice?.stripTrailingZeros()?.toPlainString(),
                            it.variationMargin.toPlainString(),
                            it.fees.toPlainString(),
                        )
                    },
                ),
            ),
        )
        val current = repository.currentStatement(account.id, day.businessDate, lock = true)
        if (current != null && current.contentHash == hash) return null
        val latest = repository.latestStatementDate(account.id)
        val now = clock.instant()
        current?.let { repository.supersedeStatement(it.id, now) }
        val statement = ClearingStatementView(
            id = Ids.newId(),
            accountId = account.id,
            legalEntityId = account.legalEntityId,
            businessDate = day.businessDate,
            version = repository.nextStatementVersion(account.id, day.businessDate),
            currency = account.currency,
            variationMargin = day.variationMargin,
            initialMargin = day.initialMargin,
            fees = day.fees,
            cashBalance = day.cashBalance,
            lines = day.lines,
            contentHash = hash,
            fileName = fileName,
            importedBy = userId,
            importedAt = now,
            supersededAt = null,
        )
        repository.insertStatement(statement)
        // The level of initial margin is the latest day's; an earlier day re-sent does not move it.
        val reported = accounts.account(account.id)!!.initialMargin
        val imChange = if (latest == null || !day.businessDate.isBefore(latest)) {
            repository.setInitialMargin(account.id, day.initialMargin)
            day.initialMargin - reported
        } else {
            BigDecimal.ZERO.setScale(2)
        }
        events.publishEvent(ClearingStatementImported(statement, imChange, day.fees - (current?.fees ?: BigDecimal.ZERO)))
        reconcile(account, day.businessDate, userId)
        return statement
    }

    /** A new margin run re-checks the day's statement, if there is one. */
    @EventListener
    fun on(event: VariationMarginComputed) {
        val account = accounts.account(event.run.accountId) ?: return
        reconcile(account, event.run.businessDate, event.run.computedBy)
    }

    private fun reconcile(account: ClearingAccountView, date: LocalDate, userId: String) {
        val statement = repository.currentStatement(account.id, date) ?: return
        val run = accounts.currentRun(account.id, date)
        val differences = MarginReconciliation.differences(statement, run)
        val open = repository.openBreak(account.id, date)
        val now = clock.instant()
        when {
            open == null && differences.isEmpty() -> Unit
            open == null -> {
                val opened = ClearingBreakView(
                    Ids.newId(),
                    account.id,
                    account.legalEntityId,
                    date,
                    statement.id,
                    run?.id,
                    ClearingBreakStatus.OPEN,
                    differences,
                    userId,
                    now,
                    null,
                    null,
                    null,
                )
                repository.insertBreak(opened)
                events.publishEvent(ClearingBreakOpened(opened))
            }
            differences.isEmpty() -> {
                val matched = open.copy(
                    statementId = statement.id,
                    runId = run?.id,
                    status = ClearingBreakStatus.MATCHED,
                    closedBy = userId,
                    closedAt = now,
                    resolution = "Statement version ${statement.version} and margin run version ${run?.version} agree",
                )
                repository.updateBreak(matched)
                events.publishEvent(ClearingBreakClosed(matched))
            }
            !MarginReconciliation.same(open.differences, differences) || open.statementId != statement.id || open.runId != run?.id ->
                repository.updateBreak(open.copy(statementId = statement.id, runId = run?.id, differences = differences))
            else -> Unit
        }
    }

    /**
     * A second person accepts the member's figures for the day, with the reason (the inbox decision). The margin
     * already booked stays as computed; a real difference is corrected at its source (the trade or the price).
     */
    fun accept(breakId: UUID, decidedBy: String, reason: String?): ClearingBreakView {
        val open = repository.clearingBreak(breakId, lock = true) ?: throw NotFoundException("ClearingBreak", breakId)
        if (open.status != ClearingBreakStatus.OPEN) throw ValidationException("The break of ${open.businessDate} is ${open.status}")
        if (reason.isNullOrBlank()) throw ValidationException("Say why the member's figures are accepted")
        if (decidedBy == open.openedBy) throw SegregationOfDutiesException("The break is accepted by someone other than who imported the statement")
        val accepted = open.copy(status = ClearingBreakStatus.ACCEPTED, closedBy = decidedBy, closedAt = clock.instant(), resolution = reason.trim())
        repository.updateBreak(accepted)
        events.publishEvent(ClearingBreakClosed(accepted))
        return accepted
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    private fun readable(legalEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK, Role.AUDITOR, Role.SYSTEM)
        actor.requireEntity(legalEntityId)
    }

    @Transactional(readOnly = true)
    override fun statements(accountId: UUID, from: LocalDate, to: LocalDate): List<ClearingStatementView> {
        val account = accounts.account(accountId) ?: throw NotFoundException("ClearingAccount", accountId)
        readable(account.legalEntityId)
        if (to.isBefore(from)) throw ValidationException("The period ends before it starts")
        return repository.statements(accountId, from, to)
    }

    @Transactional(readOnly = true)
    override fun statement(id: UUID): ClearingStatementView? = repository.statement(id)?.also { readable(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun breaks(legalEntityId: UUID?, status: ClearingBreakStatus?): List<ClearingBreakView> {
        val actor = actorProvider.current()
        legalEntityId?.let(::readable) ?: actor.requireRole(Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK, Role.AUDITOR, Role.SYSTEM)
        return repository.breaks(legalEntityId, status).filter { actor.canAccess(it.legalEntityId) }
    }

    @Transactional(readOnly = true)
    override fun clearingBreak(id: UUID): ClearingBreakView? = repository.clearingBreak(id)?.also { readable(it.legalEntityId) }

    private companion object {
        const val MAX_FILE_NAME = 200
    }
}
