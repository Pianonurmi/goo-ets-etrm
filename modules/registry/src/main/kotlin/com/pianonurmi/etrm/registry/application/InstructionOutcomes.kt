package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.BeneficiaryCommand
import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryType
import com.pianonurmi.etrm.registry.api.AssetKind
import com.pianonurmi.etrm.registry.api.InstructionCancelled
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.InstructionOutcomeUnknown
import com.pianonurmi.etrm.registry.api.InstructionOverdue
import com.pianonurmi.etrm.registry.api.InstructionPage
import com.pianonurmi.etrm.registry.api.InstructionSearch
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionSubmitted
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.SubmitAllowanceTransfer
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitSurrender
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import com.pianonurmi.etrm.registry.domain.RegistryInstruction
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What becomes of an instruction: confirmed (the lot leaves or is cancelled, allowances move), failed (the asset is
 * given back), sent, retried or of unknown outcome; from an operator, the dispatcher, a statement or a transaction
 * export.
 */
@Service
@Transactional
class InstructionOutcomes(
    private val repository: RegistryInstructionRepository,
    private val store: InstructionStore,
    private val lots: LotCommand,
    private val allowances: AllowanceHoldingCommand,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val settings: RegistryDispatchSettings = RegistryDispatchSettings(),
) {
    private val log = LoggerFactory.getLogger(InstructionOutcomes::class.java)

    fun confirm(instructionId: UUID, externalRef: String?): RegistryInstructionView =
        applyConfirmation(store.locked(instructionId), externalRef, actorProvider.current().userId, null)

    fun fail(instructionId: UUID, reason: String): RegistryInstructionView = applyFailure(store.locked(instructionId), reason, null)

    /** Applies the result of a registry call made by the dispatcher. */
    fun applyDispatchResult(instructionId: UUID, result: GatewayResult): RegistryInstructionView {
        val current = store.locked(instructionId)
        if (current.status != InstructionStatus.DISPATCHING && current.status != InstructionStatus.UNKNOWN) {
            log.warn("Ignoring late dispatch result {} for instruction {} in status {}", result::class.simpleName, current.instructionKey, current.status)
            return current
        }
        return apply(current, result, "gateway:${current.channel.lowercase()}")
    }

    /** The dispatcher holding the lease disappeared mid-call: the registry may or may not have the instruction. */
    fun leaseExpired(instructionId: UUID): RegistryInstructionView {
        val current = store.locked(instructionId)
        if (current.status != InstructionStatus.DISPATCHING) return current
        return markUnknown(current, "dispatch lease expired; outcome unknown")
    }

    /** Applies the registry's answer to a status query for an UNKNOWN instruction; null leaves it for an operator. */
    fun resolveUnknown(instructionId: UUID, result: GatewayResult?): RegistryInstructionView {
        val current = store.locked(instructionId)
        if (current.status != InstructionStatus.UNKNOWN || result == null) return current
        return when (result) {
            is GatewayResult.Confirmed, is GatewayResult.Sent, is GatewayResult.Failed -> apply(current, result, "gateway:${current.channel.lowercase()}")
            // The registry never saw it: safe to send again.
            is GatewayResult.Retry -> store.save(
                current.copy(status = InstructionStatus.PENDING, nextAttemptAt = store.now(), version = current.version + 1),
                null,
            )
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
        val current = store.locked(instructionId)
        if (current.status.terminal) return current
        return if (completed) {
            applyConfirmation(current, externalRef, source, mapOf("source" to source))
        } else {
            applyFailure(current, "registry reported failure ($source)", mapOf("source" to source))
        }
    }

    fun apply(current: RegistryInstructionView, result: GatewayResult, by: String): RegistryInstructionView = when (result) {
        is GatewayResult.Confirmed -> applyConfirmation(current, result.externalRef, by, result.response)
        is GatewayResult.Sent -> store.save(RegistryInstruction(current).sent(result.externalRef, store.now()), result.response)
        is GatewayResult.Failed -> applyFailure(current, result.reason, result.response)
        is GatewayResult.Retry -> if (current.status == InstructionStatus.DISPATCHING && current.attempts + 1 < settings.maxAttempts) {
            store.save(RegistryInstruction(current).retryLater(result.reason, store.now().plus(settings.backoff(current.attempts + 1))), null)
        } else {
            applyFailure(current, "gave up after ${current.attempts + 1} attempts: ${result.reason}", null)
        }
        is GatewayResult.Unknown -> markUnknown(current, result.reason)
    }

    private fun markUnknown(current: RegistryInstructionView, reason: String): RegistryInstructionView {
        val next = store.save(RegistryInstruction(current).unknown(reason), null)
        events.publishEvent(InstructionOutcomeUnknown(next, reason))
        return next
    }

    fun applyConfirmation(current: RegistryInstructionView, externalRef: String?, by: String, response: Map<String, Any?>?): RegistryInstructionView {
        val next = RegistryInstruction(current).confirmed(externalRef, by, store.now())
        store.save(next, response)
        when (next.type) {
            InstructionType.TRANSFER -> events.publishEvent(InstructionConfirmed(next, lots.confirmTransferOut(next.requireLot(), next.id, next.externalRef)))
            InstructionType.CANCELLATION -> events.publishEvent(
                InstructionConfirmed(
                    next,
                    lots.cancel(
                        next.requireLot(),
                        null,
                        checkNotNull(next.cancellation) {
                            "Instruction ${next.instructionKey} cancels nothing"
                        }.copy(registryCancellationRef = next.externalRef),
                    ),
                ),
            )
            InstructionType.ALLOWANCE_TRANSFER, InstructionType.SURRENDER -> {
                allowances.release(next.requireHolding(), next.quantity)
                val holding = allowances.record(
                    RecordAllowanceMovement(
                        holderEntityId = next.holderEntityId,
                        registryAccountId = next.fromAccountId,
                        productCode = next.requireProduct(),
                        type = next.movementType ?: AllowanceMovementType.TRANSFER_OUT,
                        quantity = next.quantity,
                        effectiveDate = store.dateOf(next.confirmedAt ?: store.now()),
                        reference = next.externalRef ?: next.instructionKey,
                        costPerUnit = next.unitPrice,
                        costCurrency = next.priceCurrency,
                        complianceYear = next.complianceYear,
                    ),
                )
                if (next.type == InstructionType.ALLOWANCE_TRANSFER) receiveInternally(next)
                events.publishEvent(InstructionConfirmed(next, null, holding))
            }
        }
        return next
    }

    /** A transfer to another of our accounts lands there too: a purchase at the sale price, or a plain transfer in. */
    private fun receiveInternally(instruction: RegistryInstructionView) {
        val registryCode = checkNotNull(instruction.toRegistryCode) { "Instruction ${instruction.instructionKey} has no destination registry" }
        val accountRef = checkNotNull(instruction.toAccountRef) { "Instruction ${instruction.instructionKey} has no destination account" }
        val target = referenceData.registryAccountByNumber(registryCode, accountRef) ?: return
        if (target.holderType != HolderType.LEGAL_ENTITY) return
        val sale = instruction.movementType == AllowanceMovementType.SALE
        allowances.record(
            RecordAllowanceMovement(
                holderEntityId = target.holderId,
                registryAccountId = target.id,
                productCode = instruction.requireProduct(),
                type = if (sale) AllowanceMovementType.PURCHASE else AllowanceMovementType.TRANSFER_IN,
                quantity = instruction.quantity,
                effectiveDate = store.dateOf(instruction.confirmedAt ?: store.now()),
                reference = instruction.externalRef ?: instruction.instructionKey,
                costPerUnit = instruction.unitPrice,
                costCurrency = instruction.priceCurrency,
            ),
        )
    }

    fun applyFailure(current: RegistryInstructionView, reason: String, response: Map<String, Any?>?): RegistryInstructionView {
        val next = RegistryInstruction(current).failed(reason)
        store.save(next, response)
        when (next.assetKind) {
            AssetKind.GOO_LOT -> lots.failTransfer(next.requireLot(), next.id, reason)
            AssetKind.ALLOWANCE -> allowances.release(next.requireHolding(), next.quantity)
        }
        events.publishEvent(InstructionFailed(next, reason))
        return next
    }

    private companion object {
        const val OVERDUE_BATCH = 200
    }
}
