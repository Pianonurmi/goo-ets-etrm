package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.registry.api.InstructionCancelled
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.InstructionOutcomeUnknown
import com.pianonurmi.etrm.registry.api.InstructionOverdue
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionSubmitted
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import com.pianonurmi.etrm.registry.domain.RegistryInstruction
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Orchestrates instruction -> (approval) -> execution -> confirmation and keeps the lot lifecycle in step:
 * submit marks the lot IN_TRANSFER, confirmation transfers it out (or cancels it), failure returns it.
 *
 * Channels without external I/O (simulator, manual hand-over) execute in the submitting transaction. API and file
 * channels use the instruction row as an outbox: the [RegistryDispatcher] claims it after commit, calls the registry
 * outside any transaction and reports the result back through [applyDispatchResult].
 */
@Service
@Transactional
class RegistryInstructionService(
    private val repository: RegistryInstructionRepository,
    private val gateways: List<RegistryGateway>,
    private val lots: LotCommand,
    private val lotQuery: LotQuery,
    private val referenceData: ReferenceDataQuery,
    private val routes: TransferRouteService,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val settings: RegistryDispatchSettings = RegistryDispatchSettings(),
    private val nudge: DispatchNudge = DispatchNudge.NONE,
) : RegistryInstructionCommand, RegistryInstructionQuery {
    private val log = LoggerFactory.getLogger(RegistryInstructionService::class.java)

    override fun submitTransfer(command: SubmitTransfer): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        requireUsableAccount(lotQuery.requireLot(command.lotId).registryAccountId, export = true)
        val lot = prepareLot(command.lotId, command.quantityMwh)
        val route = routes.route(lot.registryCode, command.toRegistryCode)
        if (!route.feasible) throw ValidationException("No transfer route from ${lot.registryCode} to ${command.toRegistryCode}")
        val instruction = newInstruction(InstructionType.TRANSFER, lot, command.idempotencyKey, command.planRef, command.lineRef, command.obligationRef)
            .copy(toRegistryCode = command.toRegistryCode.uppercase(), toAccountRef = command.toAccountRef)
        return submit(instruction, lot, mapOf("route" to route))
    }

    override fun submitCancellation(command: SubmitCancellation): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        requireUsableAccount(lotQuery.requireLot(command.lotId).registryAccountId, export = false)
        val lot = prepareLot(command.lotId, command.quantityMwh)
        val instruction = newInstruction(InstructionType.CANCELLATION, lot, command.idempotencyKey, command.planRef, command.lineRef, command.obligationRef)
            .copy(cancellation = command.details)
        return submit(instruction, lot, mapOf("cancellation" to command.details))
    }

    /** Second-person approval of a manual instruction (from the inbox). */
    fun approve(instructionId: UUID, by: String): RegistryInstructionView {
        val current = locked(instructionId)
        if (current.submittedBy == by) throw com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException("Instruction submitter may not approve it")
        val approved = RegistryInstruction(current).approved(by, now())
        save(approved, null)
        return proceed(approved)
    }

    override fun confirm(instructionId: UUID, externalRef: String?): RegistryInstructionView =
        applyConfirmation(locked(instructionId), externalRef, actorProvider.current().userId, null)

    override fun fail(instructionId: UUID, reason: String): RegistryInstructionView = applyFailure(locked(instructionId), reason, null)

    override fun cancel(instructionId: UUID, reason: String): RegistryInstructionView {
        val next = RegistryInstruction(locked(instructionId)).cancelled(reason)
        save(next, null)
        val lot = lotQuery.requireLot(next.lotId)
        if (lot.status == LotStatus.IN_TRANSFER && lot.instructionRef == next.id) lots.failTransfer(next.lotId, next.id, "instruction cancelled: $reason")
        events.publishEvent(InstructionCancelled(next, reason))
        return next
    }

    override fun retry(instructionId: UUID): RegistryInstructionView {
        val current = locked(instructionId)
        val next = RegistryInstruction(current).resubmitted(settings.unplannedNeedsApproval, now())
        save(next, null)
        val lot = lotQuery.requireLot(next.lotId)
        if (lot.status != LotStatus.IN_TRANSFER) lots.startTransfer(lot.id, next.id)
        events.publishEvent(InstructionSubmitted(next))
        return if (next.status == InstructionStatus.PENDING_APPROVAL) next else proceed(next)
    }

    // ----------------------------------------------------------------------------------------------------------
    // Called by the dispatcher, each in its own transaction
    // ----------------------------------------------------------------------------------------------------------

    /** Applies the result of a registry call made by the dispatcher. */
    fun applyDispatchResult(instructionId: UUID, result: GatewayResult): RegistryInstructionView {
        val current = locked(instructionId)
        if (current.status != InstructionStatus.DISPATCHING && current.status != InstructionStatus.UNKNOWN) {
            log.warn("Ignoring late dispatch result {} for instruction {} in status {}", result::class.simpleName, current.instructionKey, current.status)
            return current
        }
        return apply(current, result, "gateway:${current.channel.lowercase()}")
    }

    /** The dispatcher holding the lease disappeared mid-call: the registry may or may not have the instruction. */
    fun leaseExpired(instructionId: UUID): RegistryInstructionView {
        val current = locked(instructionId)
        if (current.status != InstructionStatus.DISPATCHING) return current
        return markUnknown(current, "dispatch lease expired; outcome unknown")
    }

    /** Applies the registry's answer to a status query for an UNKNOWN instruction; null leaves it for an operator. */
    fun resolveUnknown(instructionId: UUID, result: GatewayResult?): RegistryInstructionView {
        val current = locked(instructionId)
        if (current.status != InstructionStatus.UNKNOWN || result == null) return current
        return when (result) {
            is GatewayResult.Confirmed, is GatewayResult.Sent, is GatewayResult.Failed -> apply(current, result, "gateway:${current.channel.lowercase()}")
            // The registry never saw it: safe to send again.
            is GatewayResult.Retry -> save(current.copy(status = InstructionStatus.PENDING, nextAttemptAt = now(), version = current.version + 1), null)
            is GatewayResult.Unknown -> current
        }
    }

    /** Reports instructions that have been with the registry longer than their channel's confirmation SLA. */
    fun flagOverdue(now: Instant): Int {
        val shortest = settings.confirmationSla.values.minOrNull() ?: return 0
        var flagged = 0
        repository.overdueCandidates(now.minus(shortest), OVERDUE_BATCH).forEach { instruction ->
            val since = instruction.sentAt ?: instruction.createdAt
            if (since.plus(settings.sla(instruction.channel)).isAfter(now)) return@forEach
            repository.markOverdueNotified(instruction.id, now)
            events.publishEvent(InstructionOverdue(instruction))
            flagged++
        }
        return flagged
    }

    /** Certificates of an in-flight instruction no longer appear in the source account's statement: the registry executed it. */
    @EventListener
    fun on(event: InTransferLotLeftAccount) {
        if (!settings.confirmFromStatements) return
        val instruction = repository.lock(event.instructionRef) ?: return
        if (!instruction.status.awaitingRegistry && instruction.status != InstructionStatus.PENDING) return
        applyConfirmation(instruction, instruction.externalRef, "statement:${event.statementDate}", mapOf("statementId" to event.statementId.toString()))
    }

    /** Confirmation or failure reported by a registry transaction export. */
    fun applyRegistryOutcome(instructionId: UUID, completed: Boolean, externalRef: String?, source: String): RegistryInstructionView {
        val current = locked(instructionId)
        if (current.status.terminal) return current
        return if (completed) {
            applyConfirmation(current, externalRef, source, mapOf("source" to source))
        } else {
            applyFailure(current, "registry reported failure ($source)", mapOf("source" to source))
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun instruction(id: UUID): RegistryInstructionView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun requireInstruction(id: UUID): RegistryInstructionView = repository.find(id) ?: throw NotFoundException("RegistryInstruction", id)

    @Transactional(readOnly = true)
    override fun byKey(idempotencyKey: String): RegistryInstructionView? = repository.byKey(idempotencyKey)

    @Transactional(readOnly = true)
    override fun open(registryCode: String?): List<RegistryInstructionView> = repository.open(registryCode?.uppercase())

    @Transactional(readOnly = true)
    override fun forPlan(planRef: UUID): List<RegistryInstructionView> = repository.forPlan(planRef)

    // ----------------------------------------------------------------------------------------------------------

    private fun submit(instruction: RegistryInstructionView, lot: LotView, request: Map<String, Any?>): RegistryInstructionView {
        val registry = referenceData.requireRegistry(lot.registryCode)
        if (!registry.active) throw ValidationException("Registry ${registry.code} is not active")
        val channel = registry.integration.name
        gatewayFor(channel, registry.code)
        val needsApproval = instruction.planRef == null && settings.unplannedNeedsApproval
        val created = instruction.copy(
            channel = channel,
            status = if (needsApproval) InstructionStatus.PENDING_APPROVAL else InstructionStatus.PENDING,
            nextAttemptAt = if (needsApproval) null else now(),
        )
        repository.insert(created, request)
        lots.startTransfer(lot.id, created.id)
        events.publishEvent(InstructionSubmitted(created))
        return if (needsApproval) created else proceed(created)
    }

    /** Executes inline channels now; leaves outbox channels to the dispatcher after commit. */
    private fun proceed(instruction: RegistryInstructionView): RegistryInstructionView {
        val gateway = gatewayFor(instruction.channel, instruction.registryCode)
        if (!gateway.inline) {
            nudge.afterCommit(instruction.id)
            return instruction
        }
        return apply(instruction, gateway.execute(instruction), "gateway:${instruction.channel.lowercase()}")
    }

    private fun apply(current: RegistryInstructionView, result: GatewayResult, by: String): RegistryInstructionView = when (result) {
        is GatewayResult.Confirmed -> applyConfirmation(current, result.externalRef, by, result.response)
        is GatewayResult.Sent -> save(RegistryInstruction(current).sent(result.externalRef, now()), result.response)
        is GatewayResult.Failed -> applyFailure(current, result.reason, result.response)
        is GatewayResult.Retry -> if (current.status == InstructionStatus.DISPATCHING && current.attempts + 1 < settings.maxAttempts) {
            save(RegistryInstruction(current).retryLater(result.reason, now().plus(settings.backoff(current.attempts + 1))), null)
        } else {
            applyFailure(current, "gave up after ${current.attempts + 1} attempts: ${result.reason}", null)
        }
        is GatewayResult.Unknown -> markUnknown(current, result.reason)
    }

    private fun markUnknown(current: RegistryInstructionView, reason: String): RegistryInstructionView {
        val next = save(RegistryInstruction(current).unknown(reason), null)
        events.publishEvent(InstructionOutcomeUnknown(next, reason))
        return next
    }

    /** The source account must be active and, for transfers, allowed to export. */
    private fun requireUsableAccount(accountId: UUID, export: Boolean) {
        val account = referenceData.requireRegistryAccount(accountId)
        if (!account.active) throw ValidationException("Registry account ${account.accountNumber} is inactive")
        if (export && !account.canExport) throw ValidationException("Registry account ${account.accountNumber} may not export")
    }

    private fun prepareLot(lotId: UUID, quantity: Long?): LotView {
        val lot = lotQuery.requireLot(lotId)
        if (lot.status != LotStatus.HELD && lot.status != LotStatus.RESERVED) throw ConflictException("Lot $lotId is ${lot.status}; cannot instruct")
        if (quantity == null || quantity == lot.quantityMwh) return lot
        if (quantity > lot.quantityMwh) throw ValidationException("Quantity $quantity exceeds lot of ${lot.quantityMwh} MWh")
        val carved = lots.split(lotId, quantity).carved
        return if (lot.status == LotStatus.RESERVED) lotQuery.requireLot(carved.id) else carved
    }

    private fun newInstruction(type: InstructionType, lot: LotView, key: String, planRef: UUID?, lineRef: UUID?, obligationRef: UUID?) =
        RegistryInstructionView(
            id = Ids.newId(),
            instructionKey = key,
            type = type,
            registryCode = lot.registryCode,
            holderEntityId = lot.holderEntityId,
            fromAccountId = lot.registryAccountId,
            toRegistryCode = null,
            toAccountRef = null,
            lotId = lot.id,
            range = lot.range,
            quantityMwh = lot.quantityMwh,
            cancellation = null,
            planRef = planRef,
            lineRef = lineRef,
            obligationRef = obligationRef,
            status = InstructionStatus.PENDING,
            externalRef = null,
            attempts = 0,
            lastError = null,
            submittedBy = actorProvider.current().userId,
            confirmedBy = null,
            createdAt = now(),
            sentAt = null,
            confirmedAt = null,
            version = 1,
        )

    private fun gatewayFor(channel: String, registryCode: String): RegistryGateway =
        gateways.firstOrNull { it.channel == channel && it.supports(registryCode) }
            ?: throw ConflictException("No registry gateway for $registryCode on channel $channel")

    private fun applyConfirmation(current: RegistryInstructionView, externalRef: String?, by: String, response: Map<String, Any?>?): RegistryInstructionView {
        val next = RegistryInstruction(current).confirmed(externalRef, by, now())
        save(next, response)
        val lot = when (next.type) {
            InstructionType.TRANSFER -> lots.confirmTransferOut(next.lotId, next.id, next.externalRef)
            InstructionType.CANCELLATION -> lots.cancel(next.lotId, null, next.cancellation!!.copy(registryCancellationRef = next.externalRef))
        }
        events.publishEvent(InstructionConfirmed(next, lot))
        return next
    }

    private fun applyFailure(current: RegistryInstructionView, reason: String, response: Map<String, Any?>?): RegistryInstructionView {
        val next = RegistryInstruction(current).failed(reason)
        save(next, response)
        lots.failTransfer(next.lotId, next.id, reason)
        events.publishEvent(InstructionFailed(next, reason))
        return next
    }

    private fun locked(id: UUID): RegistryInstructionView = repository.lock(id) ?: throw NotFoundException("RegistryInstruction", id)

    private fun save(instruction: RegistryInstructionView, response: Map<String, Any?>?): RegistryInstructionView {
        if (!repository.update(instruction, response)) throw ConflictException("Instruction ${instruction.instructionKey} changed concurrently")
        return instruction
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val OVERDUE_BATCH = 200
    }
}
