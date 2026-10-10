package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.GlBalanceLine
import com.pianonurmi.etrm.ledger.api.GlExportFormatView
import com.pianonurmi.etrm.ledger.api.GlExportStatus
import com.pianonurmi.etrm.ledger.api.GlExportStatusChanged
import com.pianonurmi.etrm.ledger.api.GlExportView
import com.pianonurmi.etrm.ledger.api.GlInterfaceCommand
import com.pianonurmi.etrm.ledger.api.GlInterfaceQuery
import com.pianonurmi.etrm.ledger.api.HardCloseGate
import com.pianonurmi.etrm.ledger.api.PostingRuleChangeRequested
import com.pianonurmi.etrm.ledger.api.PostingRuleChangeView
import com.pianonurmi.etrm.ledger.api.PostingRuleVersionView
import com.pianonurmi.etrm.ledger.api.ReconciliationRow
import com.pianonurmi.etrm.ledger.api.RuleChangeStatus
import com.pianonurmi.etrm.ledger.api.UpsertPostingRule
import com.pianonurmi.etrm.ledger.domain.GlInterfaceRepository
import com.pianonurmi.etrm.ledger.domain.JournalRepository
import com.pianonurmi.etrm.ledger.domain.PeriodEndRepository
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.YearMonth
import java.util.UUID

/** The configured conditions for a hard close (`etrm.ledger.hard-close-gates`). */
@Component
class HardCloseGates(
    private val journal: JournalRepository,
    private val glInterface: GlInterfaceRepository,
    private val periodEnd: PeriodEndRepository,
    @Value("\${etrm.ledger.hard-close-gates:ALL_EXPORTED,LAST_EXPORT_ACKNOWLEDGED,PERIOD_END_RUN}") private val configured: List<HardCloseGate>,
) {
    /** What stands in the way of a hard close of the period, one sentence per failed gate. */
    fun blockers(legalEntityId: UUID, period: YearMonth): List<String> = configured.distinct().mapNotNull { gate ->
        when (gate) {
            HardCloseGate.ALL_EXPORTED -> {
                val exported = glInterface.exportedEntryIds(legalEntityId, period.toString())
                val pending = journal.entries(legalEntityId, period.toString(), null, Int.MAX_VALUE).count { it.id !in exported }
                if (pending > 0) "$pending journal entries are not exported" else null
            }
            HardCloseGate.LAST_EXPORT_ACKNOWLEDGED -> {
                val last = glInterface.exports(legalEntityId, period.toString()).filter { it.status != GlExportStatus.REJECTED }.maxByOrNull { it.exportSeq }
                when {
                    last == null -> "nothing was exported"
                    last.status != GlExportStatus.ACKNOWLEDGED -> "export ${last.exportSeq} is ${last.status}, not acknowledged"
                    else -> null
                }
            }
            HardCloseGate.PERIOD_END_RUN -> if (periodEnd.runs(legalEntityId, period.toString()).isEmpty()) "the period end has not been run" else null
        }
    }
}

/**
 * The general ledger side of the sub-ledger (ADR 0024): export delivery, acknowledgement and rejection, GL balances
 * and reconciliation, and posting rule changes under four eyes.
 */
@Service
@Transactional
class GlInterfaceService(
    private val repository: GlInterfaceRepository,
    private val gates: HardCloseGates,
    @Lazy private val ledger: LedgerService,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : GlInterfaceQuery, GlInterfaceCommand {
    private fun accountant(legalEntityId: UUID?) {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        legalEntityId?.let(actor::requireEntity)
    }

    private fun requireExport(id: UUID): GlExportView = repository.find(id, lock = true) ?: throw NotFoundException("GlExport", id)

    private fun transition(export: GlExportView, to: GlExportStatus, reason: String?, reference: String?): GlExportView {
        val allowed = when (to) {
            GlExportStatus.DELIVERED -> export.status == GlExportStatus.GENERATED
            GlExportStatus.ACKNOWLEDGED, GlExportStatus.REJECTED -> export.status == GlExportStatus.GENERATED || export.status == GlExportStatus.DELIVERED
            GlExportStatus.GENERATED -> false
        }
        if (!allowed) throw ValidationException("Export ${export.exportSeq} is ${export.status} and cannot become $to")
        val by = actorProvider.current().userId
        val at = clock.instant()
        repository.setStatus(export.id, to, reason, reference ?: export.acknowledgementRef, by, at)
        val next = export.copy(
            status = to,
            statusReason = reason,
            acknowledgementRef = reference ?: export.acknowledgementRef,
            statusChangedBy = by,
            statusChangedAt = at,
        )
        events.publishEvent(GlExportStatusChanged(next.copy(content = "")))
        return next
    }

    override fun markDelivered(exportId: UUID): GlExportView {
        val export = requireExport(exportId)
        accountant(export.legalEntityId)
        return transition(export, GlExportStatus.DELIVERED, null, null)
    }

    override fun acknowledge(exportId: UUID, reference: String): GlExportView {
        val export = requireExport(exportId)
        accountant(export.legalEntityId)
        if (reference.isBlank()) throw ValidationException("An acknowledgement carries the general ledger's reference")
        return transition(export, GlExportStatus.ACKNOWLEDGED, null, reference.trim())
    }

    override fun reject(exportId: UUID, reason: String): GlExportView {
        val export = requireExport(exportId)
        accountant(export.legalEntityId)
        if (reason.isBlank()) throw ValidationException("A rejection needs a reason")
        val rejected = transition(export, GlExportStatus.REJECTED, reason.trim(), null)
        repository.releaseEntries(exportId)
        return rejected
    }

    override fun importBalances(legalEntityId: UUID, period: YearMonth, balances: List<GlBalanceLine>): List<ReconciliationRow> {
        accountant(legalEntityId)
        if (balances.map { it.glAccount }.toSet().size != balances.size) throw ValidationException("Each GL account appears once")
        repository.replaceGlBalances(legalEntityId, period.toString(), balances, actorProvider.current().userId)
        return reconciliationOf(legalEntityId, period)
    }

    @Transactional(readOnly = true)
    override fun reconciliation(legalEntityId: UUID, period: YearMonth): List<ReconciliationRow> {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT, Role.AUDITOR)
        actor.requireEntity(legalEntityId)
        return reconciliationOf(legalEntityId, period)
    }

    private fun reconciliationOf(legalEntityId: UUID, period: YearMonth): List<ReconciliationRow> {
        val end = period.atEndOfMonth()
        val sub = repository.subLedgerBalances(legalEntityId, end, exportedOnly = false)
        val exported = repository.subLedgerBalances(legalEntityId, end, exportedOnly = true)
        val gl = repository.glBalances(legalEntityId, period.toString())
        return (sub.keys + exported.keys + gl.keys).sorted().map { account ->
            ReconciliationRow(
                account,
                sub[account] ?: BigDecimal.ZERO,
                exported[account] ?: BigDecimal.ZERO,
                gl[account] ?: gl.takeIf {
                    it.isNotEmpty()
                }?.let { BigDecimal.ZERO },
            )
        }
    }

    override fun requestRuleChange(command: UpsertPostingRule): PostingRuleChangeView {
        accountant(command.legalEntityId)
        if (command.lines.isEmpty()) throw ValidationException("A posting rule needs lines")
        val change = PostingRuleChangeView(
            Ids.newId(),
            command.legalEntityId,
            command.ruleCode.uppercase(),
            command.copy(ruleCode = command.ruleCode.uppercase()),
            RuleChangeStatus.PENDING,
            actorProvider.current().userId,
            clock.instant(),
            null,
            null,
        )
        repository.insertRuleChange(change)
        events.publishEvent(PostingRuleChangeRequested(change))
        return change
    }

    /** From the approval task: the rule takes effect and its new version is kept in the history. */
    fun applyRuleChange(changeId: UUID, by: String) {
        val change = pendingRuleChange(changeId)
        if (change.requestedBy == by) throw SegregationOfDutiesException("A posting rule change is approved by someone other than who requested it")
        val applied = ledger.applyRule(change.change)
        repository.insertRuleVersion(
            PostingRuleVersionView(applied.ruleCode, applied.legalEntityId, applied.version, change.change, change.id, clock.instant()),
        )
        repository.decideRuleChange(changeId, RuleChangeStatus.APPLIED, by, clock.instant())
    }

    fun rejectRuleChange(changeId: UUID, by: String) {
        pendingRuleChange(changeId)
        repository.decideRuleChange(changeId, RuleChangeStatus.REJECTED, by, clock.instant())
    }

    private fun pendingRuleChange(id: UUID): PostingRuleChangeView {
        val change = repository.ruleChange(id, lock = true) ?: throw NotFoundException("PostingRuleChange", id)
        if (change.status != RuleChangeStatus.PENDING) throw ValidationException("Posting rule change $id is ${change.status}")
        return change
    }

    @Transactional(readOnly = true)
    override fun exports(legalEntityId: UUID, period: YearMonth?): List<GlExportView> {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT, Role.AUDITOR)
        actor.requireEntity(legalEntityId)
        return repository.exports(legalEntityId, period?.toString())
    }

    @Transactional(readOnly = true)
    override fun formats(): List<GlExportFormatView> = repository.formats()

    @Transactional(readOnly = true)
    override fun ruleChanges(status: RuleChangeStatus?): List<PostingRuleChangeView> =
        repository.ruleChanges(status).filter { actorProvider.current().canAccess(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun ruleHistory(ruleCode: String): List<PostingRuleVersionView> = repository.ruleHistory(ruleCode.uppercase())

    @Transactional(readOnly = true)
    override fun hardCloseBlockers(legalEntityId: UUID, period: YearMonth): List<String> {
        actorProvider.current().requireEntity(legalEntityId)
        return gates.blockers(legalEntityId, period)
    }
}
