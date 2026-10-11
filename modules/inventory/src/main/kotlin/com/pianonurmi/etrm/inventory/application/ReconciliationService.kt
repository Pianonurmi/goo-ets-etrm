package com.pianonurmi.etrm.inventory.application

import com.pianonurmi.etrm.inventory.api.BreakAction
import com.pianonurmi.etrm.inventory.api.BreakAgeing
import com.pianonurmi.etrm.inventory.api.BreakFilter
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ImportAllowanceStatement
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakAcceptanceRejected
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakAcceptanceRequested
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakAssigned
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakDetected
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakResolved
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.ReconciliationCommand
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.inventory.api.StatementKind
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.inventory.api.StatementStatus
import com.pianonurmi.etrm.inventory.api.StatementView
import com.pianonurmi.etrm.inventory.domain.AllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.domain.LotRepository
import com.pianonurmi.etrm.inventory.domain.RangeArithmetic
import com.pianonurmi.etrm.inventory.domain.ReconciliationRepository
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Reconciles registry account statements against internal live lots.

 * A discrepancy is one break across statements, identified by its fingerprint: a later statement that shows it again
 * adds a sighting; one that no longer shows it clears it (AUTO_CLEARED). An accepted break absorbs later sightings of
 * the same discrepancy instead of reopening it. Breaks close by a correcting action or by four-eyes acceptance.
 * Lots in transfer are excluded from both
 * directions because the registry may legitimately show them on either side during settlement.
 *
 * Allowance statements carry balances per product; they are compared with the holdings of the account. Reserved
 * allowances may already have left in the registry, so a registry balance between free and total quantity agrees.
 */
@Service
@Transactional
class ReconciliationService(
    private val repository: ReconciliationRepository,
    private val lots: LotRepository,
    private val holdings: AllowanceHoldingRepository,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) : ReconciliationCommand, ReconciliationQuery {
    override fun importStatement(command: ImportStatement): StatementView {
        val account = statementAccount(command.registryAccountId, command.registryCode, RegistryType.GOO)
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

    override fun importAllowanceStatement(command: ImportAllowanceStatement): StatementView {
        val account = statementAccount(command.registryAccountId, command.registryCode, RegistryType.EMISSION_ALLOWANCE)
        if (command.lines.map { it.lineNo }.toSet().size != command.lines.size) throw ValidationException("Duplicate line numbers in statement")
        val lines = command.lines.map { it.copy(productCode = it.productCode.trim().uppercase()) }
        if (lines.map { it.productCode }.toSet().size != lines.size) throw ValidationException("A product appears twice in the statement")
        if (lines.any { it.quantity < 0 }) throw ValidationException("Balances cannot be negative")
        val view = StatementView(
            id = Ids.newId(),
            registryCode = account.registryCode,
            registryAccountId = account.id,
            statementDate = command.statementDate,
            source = command.source,
            lineCount = lines.size,
            status = StatementStatus.IMPORTED,
            breaks = emptyList(),
            kind = StatementKind.ALLOWANCE,
        )
        repository.insertAllowanceStatement(view, actorProvider.current().userId, lines)
        return view
    }

    override fun reconcile(statementId: UUID): StatementView {
        val statement = repository.statement(statementId) ?: throw NotFoundException("RegistryStatement", statementId)
        // A statement is reconciled once; running it again returns the recorded result instead of duplicating breaks.
        if (statement.status != StatementStatus.IMPORTED) return statement
        if (statement.kind == StatementKind.ALLOWANCE) return reconcileBalances(statement)
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

        val result = finish(statement, breaks)
        leftAccount.forEach { lot ->
            lot.instructionRef?.let {
                events.publishEvent(InTransferLotLeftAccount(lot.id, it, statementId, statement.statementDate, statement.registryCode))
            }
        }
        return result
    }

    override fun assignBreak(breakId: UUID, assignee: String): ReconciliationBreakView {
        if (assignee.isBlank()) throw ValidationException("An assignee is required")
        val current = locked(breakId)
        if (current.status != BreakStatus.OPEN && current.status != BreakStatus.INVESTIGATING) {
            throw IllegalStateTransitionException("ReconciliationBreak", current.status.name, BreakStatus.INVESTIGATING.name)
        }
        if (current.status == BreakStatus.INVESTIGATING && current.assignee == assignee) return current
        val next = current.copy(status = BreakStatus.INVESTIGATING, assignee = assignee)
        repository.updateBreak(next)
        events.publishEvent(ReconciliationBreakAssigned(next))
        return next
    }

    override fun resolveBreak(breakId: UUID, action: BreakAction, resolution: String): ReconciliationBreakView {
        if (resolution.isBlank()) throw ValidationException("A resolution note is required")
        val current = locked(breakId)
        if (!current.status.open) throw IllegalStateTransitionException("ReconciliationBreak", current.status.name, BreakStatus.RESOLVED.name)
        return close(current.copy(status = BreakStatus.RESOLVED, resolutionAction = action, resolution = resolution, acceptanceRequestedBy = null))
    }

    override fun requestAcceptance(breakId: UUID, reason: String): ReconciliationBreakView {
        if (reason.isBlank()) throw ValidationException("A reason is required to accept a break")
        val current = locked(breakId)
        if (current.status != BreakStatus.OPEN && current.status != BreakStatus.INVESTIGATING) {
            throw IllegalStateTransitionException("ReconciliationBreak", current.status.name, BreakStatus.PENDING_ACCEPTANCE.name)
        }
        val next = current.copy(status = BreakStatus.PENDING_ACCEPTANCE, resolution = reason, acceptanceRequestedBy = actorProvider.current().userId)
        repository.updateBreak(next)
        events.publishEvent(ReconciliationBreakAcceptanceRequested(next))
        return next
    }

    override fun decideAcceptance(breakId: UUID, accept: Boolean, by: String, note: String?): ReconciliationBreakView {
        val current = locked(breakId)
        if (current.status != BreakStatus.PENDING_ACCEPTANCE) {
            throw IllegalStateTransitionException("ReconciliationBreak", current.status.name, if (accept) "ACCEPTED" else "INVESTIGATING")
        }
        if (current.acceptanceRequestedBy == by) throw SegregationOfDutiesException("The person asking to accept a break may not approve it")
        if (accept) {
            val resolution = listOfNotNull(current.resolution, note?.takeIf { it.isNotBlank() }?.let { "approved: $it" }).joinToString("; ")
            return close(current.copy(status = BreakStatus.ACCEPTED, resolution = resolution), by)
        }
        val next = current.copy(status = BreakStatus.INVESTIGATING, acceptanceRequestedBy = null, resolution = null)
        repository.updateBreak(next)
        events.publishEvent(ReconciliationBreakAcceptanceRejected(next, by, note))
        return next
    }

    private fun close(next: ReconciliationBreakView, by: String = actorProvider.current().userId): ReconciliationBreakView {
        val closed = next.copy(resolvedBy = by, resolvedAt = clock.instant())
        repository.updateBreak(closed)
        events.publishEvent(ReconciliationBreakResolved(closed, by))
        return closed
    }

    private fun locked(id: UUID): ReconciliationBreakView = repository.findBreak(id, forUpdate = true) ?: throw NotFoundException("ReconciliationBreak", id)

    private fun reconcileBalances(statement: StatementView): StatementView {
        val account = referenceData.requireRegistryAccount(statement.registryAccountId)
        val reported = repository.allowanceLines(statement.id).associate { it.productCode to it.quantity }
        val held = holdings.byEntity(account.holderId).filter { it.registryAccountId == account.id }.associateBy { it.productCode }
        val breaks = (reported.keys + held.keys).sorted().mapNotNull { product ->
            val registry = reported[product] ?: 0
            val holding = held[product]
            val total = holding?.quantity ?: 0
            val free = total - (holding?.reservedQuantity ?: 0)
            if (registry in free..total) return@mapNotNull null
            ReconciliationBreakView(
                id = Ids.newId(),
                statementId = statement.id,
                registryAccountId = account.id,
                type = BreakType.BALANCE_MISMATCH,
                issuanceRef = null,
                range = null,
                lotId = null,
                details = mapOf("reserved" to (holding?.reservedQuantity ?: 0), "difference" to registry - total),
                status = BreakStatus.OPEN,
                resolution = null,
                productCode = product,
                registryQuantity = registry,
                internalQuantity = total,
            )
        }
        return finish(statement, breaks)
    }

    /**
     * Records what [statement] shows: new discrepancies open breaks, known ones get a sighting, and open breaks of the
     * same kind on the account that the statement no longer shows are cleared (unless seen in a later statement).
     */
    private fun finish(statement: StatementView, candidates: List<ReconciliationBreakView>): StatementView {
        val seen = candidates.map { withIdentity(it, statement) }.distinctBy { it.fingerprint }.map { candidate ->
            val known = repository.currentByFingerprint(candidate.fingerprint)
            if (known == null) {
                repository.insertBreak(candidate)
                repository.addSighting(candidate.id, statement.id)
                events.publishEvent(ReconciliationBreakDetected(candidate))
                candidate
            } else {
                val updated = known.copy(
                    lastSeenOn = maxOf(known.lastSeenOn ?: statement.statementDate, statement.statementDate),
                    details = candidate.details,
                    registryQuantity = candidate.registryQuantity,
                    internalQuantity = candidate.internalQuantity,
                )
                repository.updateBreak(updated)
                repository.addSighting(known.id, statement.id)
                updated
            }
        }
        val fingerprints = seen.map { it.fingerprint }.toSet()
        val kindTypes = if (statement.kind == StatementKind.ALLOWANCE) setOf(BreakType.BALANCE_MISMATCH) else CERTIFICATE_BREAKS
        repository.openForAccount(statement.registryAccountId)
            .filter { it.type in kindTypes && it.fingerprint !in fingerprints && (it.lastSeenOn ?: LocalDate.MIN) <= statement.statementDate }
            .forEach { gone ->
                close(
                    gone.copy(status = BreakStatus.AUTO_CLEARED, resolution = "Not in statement ${statement.source} of ${statement.statementDate}"),
                    "system",
                )
            }
        val status = if (seen.isEmpty()) StatementStatus.RECONCILED else StatementStatus.BREAKS
        repository.updateStatementStatus(statement.id, status)
        return statement.copy(status = status, breaks = seen)
    }

    private fun withIdentity(candidate: ReconciliationBreakView, statement: StatementView): ReconciliationBreakView {
        val identity = when (candidate.type) {
            BreakType.BALANCE_MISMATCH -> "${candidate.productCode}|-|${candidate.registryQuantity!! - candidate.internalQuantity!!}"
            else -> "${candidate.issuanceRef}|${candidate.range!!.start}-${candidate.range.end}"
        }
        return candidate.copy(
            registryAccountId = statement.registryAccountId,
            fingerprint = "${statement.registryAccountId}|${candidate.type}|$identity",
            firstSeenOn = statement.statementDate,
            lastSeenOn = statement.statementDate,
        )
    }

    private fun statementAccount(accountId: UUID, registryCode: String, type: RegistryType): RegistryAccountView {
        val account = referenceData.requireRegistryAccount(accountId)
        if (!account.registryCode.equals(registryCode, ignoreCase = true)) {
            throw ValidationException("Account ${account.accountNumber} belongs to ${account.registryCode}, not $registryCode")
        }
        if (referenceData.requireRegistry(account.registryCode).registryType != type) {
            throw ValidationException("Account ${account.accountNumber} is not in a ${type.name.lowercase()} registry")
        }
        return account
    }

    @Transactional(readOnly = true)
    override fun statement(id: UUID): StatementView? = repository.statement(id)

    @Transactional(readOnly = true)
    override fun statements(registryAccountId: UUID, limit: Int): List<StatementView> = repository.statements(registryAccountId, limit)

    @Transactional(readOnly = true)
    override fun openBreaks(registryAccountId: UUID?): List<ReconciliationBreakView> = repository.breaks(BreakFilter(registryAccountId))

    @Transactional(readOnly = true)
    override fun breaks(filter: BreakFilter): List<ReconciliationBreakView> = repository.breaks(filter)

    @Transactional(readOnly = true)
    override fun requireBreak(id: UUID): ReconciliationBreakView = repository.findBreak(id) ?: throw NotFoundException("ReconciliationBreak", id)

    @Transactional(readOnly = true)
    override fun ageing(): BreakAgeing = repository.ageing(LocalDate.now(clock))

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

    private companion object {
        val CERTIFICATE_BREAKS = setOf(BreakType.MISSING_INTERNALLY, BreakType.MISSING_IN_REGISTRY, BreakType.ATTRIBUTE_MISMATCH)
    }
}
