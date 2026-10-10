package com.pianonurmi.etrm.inventory.application

import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakDetected
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakResolved
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.ReconciliationCommand
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.inventory.api.StatementStatus
import com.pianonurmi.etrm.inventory.api.StatementView
import com.pianonurmi.etrm.inventory.domain.LotRepository
import com.pianonurmi.etrm.inventory.domain.RangeArithmetic
import com.pianonurmi.etrm.inventory.domain.ReconciliationRepository
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Reconciles registry account statements against internal live lots. Lots in transfer are excluded from both
 * directions because the registry may legitimately show them on either side during settlement.
 */
@Service
@Transactional
class ReconciliationService(
    private val repository: ReconciliationRepository,
    private val lots: LotRepository,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) : ReconciliationCommand, ReconciliationQuery {
    override fun importStatement(command: ImportStatement): StatementView {
        val account = referenceData.requireRegistryAccount(command.registryAccountId)
        if (!account.registryCode.equals(command.registryCode, ignoreCase = true)) {
            throw ValidationException("Account ${account.accountNumber} belongs to ${account.registryCode}, not ${command.registryCode}")
        }
        if (command.lines.map { it.lineNo }.toSet().size != command.lines.size) throw ValidationException("Duplicate line numbers in statement")
        val view = StatementView(
            id = Ids.newId(),
            registryCode = account.registryCode,
            registryAccountId = account.id,
            statementDate = command.statementDate,
            source = command.source,
            lineCount = command.lines.size,
            status = StatementStatus.IMPORTED,
            breaks = emptyList(),
        )
        repository.insertStatement(view, actorProvider.current().userId, command.lines)
        return view
    }

    override fun reconcile(statementId: UUID): StatementView {
        val statement = repository.statement(statementId) ?: throw NotFoundException("RegistryStatement", statementId)
        // A statement is reconciled once; running it again returns the recorded result instead of duplicating breaks.
        if (statement.status != StatementStatus.IMPORTED) return statement
        val lines = repository.lines(statementId)
        val accountLots = lots.liveInAccount(statement.registryAccountId)
        val (inTransfer, liveLots) = accountLots.partition { it.status == LotStatus.IN_TRANSFER }
        val breaks = mutableListOf<ReconciliationBreakView>()

        // In-flight lots still count as ours for statement lines (they leave only when the registry executes).
        val lotsByIssuance = accountLots.groupBy { it.issuanceRef }
        lines.forEach { line ->
            val candidates = lotsByIssuance[line.issuanceRef].orEmpty().filter { it.range.overlaps(line.range) }
            RangeArithmetic.subtract(line.range, candidates.map { it.range }).forEach { gap ->
                breaks += newBreak(statementId, BreakType.MISSING_INTERNALLY, line.issuanceRef, gap, null, mapOf("line" to line.lineNo))
            }
            candidates.forEach { lot ->
                attributeMismatch(line, lot)?.let { breaks += newBreak(statementId, BreakType.ATTRIBUTE_MISMATCH, lot.issuanceRef, lot.range, lot.id, it) }
            }
        }
        val linesByIssuance = lines.groupBy { it.issuanceRef }
        liveLots.forEach { lot ->
            val covering = linesByIssuance[lot.issuanceRef].orEmpty().filter { it.range.overlaps(lot.range) }.map { it.range }
            RangeArithmetic.subtract(lot.range, covering).forEach { gap ->
                breaks += newBreak(statementId, BreakType.MISSING_IN_REGISTRY, lot.issuanceRef, gap, lot.id, mapOf("status" to lot.status.name))
            }
        }

        // An in-flight lot that is gone from the account has been executed by the registry; partly gone is a break.
        val leftAccount = mutableListOf<LotView>()
        inTransfer.forEach { lot ->
            val covering = linesByIssuance[lot.issuanceRef].orEmpty().filter { it.range.overlaps(lot.range) }.map { it.range }
            val gaps = RangeArithmetic.subtract(lot.range, covering)
            when {
                covering.isEmpty() -> leftAccount += lot
                gaps.isNotEmpty() -> gaps.forEach { gap ->
                    breaks += newBreak(
                        statementId,
                        BreakType.MISSING_IN_REGISTRY,
                        lot.issuanceRef,
                        gap,
                        lot.id,
                        mapOf("status" to lot.status.name, "partial" to true),
                    )
                }
            }
        }

        breaks.forEach {
            repository.insertBreak(it)
            events.publishEvent(ReconciliationBreakDetected(it))
        }
        val status = if (breaks.isEmpty()) StatementStatus.RECONCILED else StatementStatus.BREAKS
        repository.updateStatementStatus(statementId, status)
        leftAccount.forEach { lot ->
            lot.instructionRef?.let {
                events.publishEvent(InTransferLotLeftAccount(lot.id, it, statementId, statement.statementDate, statement.registryCode))
            }
        }
        return statement.copy(status = status, breaks = breaks)
    }

    override fun resolveBreak(breakId: UUID, status: BreakStatus, resolution: String): ReconciliationBreakView {
        if (status == BreakStatus.OPEN) throw ValidationException("Resolution status must be RESOLVED or ACCEPTED")
        if (resolution.isBlank()) throw ValidationException("A resolution note is required")
        val current = repository.findBreak(breakId) ?: throw NotFoundException("ReconciliationBreak", breakId)
        if (current.status != BreakStatus.OPEN) throw IllegalStateTransitionException("ReconciliationBreak", current.status.name, status.name)
        val updated = current.copy(status = status, resolution = resolution)
        repository.updateBreak(updated, actorProvider.current().userId)
        events.publishEvent(ReconciliationBreakResolved(updated, actorProvider.current().userId))
        return updated
    }

    @Transactional(readOnly = true)
    override fun statement(id: UUID): StatementView? = repository.statement(id)

    @Transactional(readOnly = true)
    override fun openBreaks(registryAccountId: UUID?): List<ReconciliationBreakView> = repository.openBreaks(registryAccountId)

    private fun attributeMismatch(line: StatementLine, lot: LotView): Map<String, Any?>? {
        val mismatches = mutableMapOf<String, Any?>()
        if (line.deviceGsrn != null && line.deviceGsrn != lot.deviceGsrn) {
            mismatches["deviceGsrn"] = mapOf(
                "registry" to line.deviceGsrn,
                "internal" to lot.deviceGsrn,
            )
        }
        if (line.productionPeriod != null && line.productionPeriod != lot.productionPeriod) {
            mismatches["productionPeriod"] = mapOf("registry" to line.productionPeriod.toString(), "internal" to lot.productionPeriod.toString())
        }
        return mismatches.takeIf { it.isNotEmpty() }?.plus("line" to line.lineNo)
    }

    private fun newBreak(statementId: UUID, type: BreakType, issuanceRef: String, range: CertificateRange, lotId: UUID?, details: Map<String, Any?>) =
        ReconciliationBreakView(Ids.newId(), statementId, type, issuanceRef, range, lotId, details, BreakStatus.OPEN, null)
}
