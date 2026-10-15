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
 * Submits instructions: a transfer or cancellation of certificates, a transfer or surrender of allowances. The asset
 * is taken hold of first; an unplanned instruction waits for a second person when the settings say so.
 */
@Service
@Transactional
class InstructionSubmission(
    private val repository: RegistryInstructionRepository,
    private val factory: InstructionFactory,
    private val execution: InstructionExecution,
    private val routes: TransferRouteService,
    private val beneficiaries: BeneficiaryCommand,
    private val referenceData: ReferenceDataQuery,
    private val lots: LotCommand,
    private val events: ApplicationEventPublisher,
    private val store: InstructionStore,
    private val settings: RegistryDispatchSettings = RegistryDispatchSettings(),
) {
    fun submitTransfer(command: SubmitTransfer): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        factory.requireUsableLotAccount(command.lotId, export = true)
        val lot = factory.prepareLot(command.lotId, command.quantityMwh)
        val route = routes.route(lot.registryCode, command.toRegistryCode)
        if (!route.feasible) throw ValidationException("No transfer route from ${lot.registryCode} to ${command.toRegistryCode}")
        val instruction = factory.newInstruction(InstructionType.TRANSFER, lot, command.idempotencyKey, command.planRef, command.lineRef, command.obligationRef)
            .copy(toRegistryCode = command.toRegistryCode.uppercase(), toAccountRef = command.toAccountRef)
        return submit(instruction, lot, mapOf("route" to route))
    }

    fun submitCancellation(command: SubmitCancellation): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        factory.requireUsableLotAccount(command.lotId, export = false)
        val lot = factory.prepareLot(command.lotId, command.quantityMwh)
        val details = beneficiaries.record(command.details)
        val instruction = factory.newInstruction(
            InstructionType.CANCELLATION,
            lot,
            command.idempotencyKey,
            command.planRef,
            command.lineRef,
            command.obligationRef,
        )
            .copy(cancellation = details)
        return submit(instruction, lot, mapOf("cancellation" to details))
    }

    fun submitAllowanceTransfer(command: SubmitAllowanceTransfer): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        if (command.quantity <= 0) throw ValidationException("Quantity must be positive")
        if (command.movementType != AllowanceMovementType.SALE && command.movementType != AllowanceMovementType.TRANSFER_OUT) {
            throw ValidationException("Allowance transfers record SALE or TRANSFER_OUT, not ${command.movementType}")
        }
        if (command.movementType == AllowanceMovementType.SALE && command.unitPrice == null) throw ValidationException("A sale needs the unit price")
        val from = factory.allowanceAccount(command.fromAccountId)
        val to = referenceData.requireRegistryAccount(command.toAccountId)
        if (!to.active) throw ValidationException("Receiving account ${to.accountNumber} is inactive")
        if (to.registryCode != from.registryCode) {
            throw ValidationException(
                "Allowances move within ${from.registryCode}; ${to.accountNumber} is in ${to.registryCode}",
            )
        }
        if (to.id == from.id) throw ValidationException("Source and receiving account are the same")
        val holding = factory.reserveAllowances(from.id, command.productCode, command.quantity)
        val now = store.now()
        // Own accounts and trusted counterparty accounts settle at once; others wait the registry's 26-hour delay.
        val trusted = to.holderType == HolderType.LEGAL_ENTITY || to.trustedOn(store.dateOf(now))
        val instruction = factory.allowanceInstruction(InstructionType.ALLOWANCE_TRANSFER, holding, command.idempotencyKey, command.quantity).copy(
            toRegistryCode = to.registryCode,
            toAccountRef = to.accountNumber,
            movementType = command.movementType,
            unitPrice = command.unitPrice,
            priceCurrency = command.priceCurrency?.uppercase(),
            obligationRef = command.obligationRef,
            expectedEffectiveAt = if (trusted) now else now.plus(UNTRUSTED_DELAY),
        )
        return submitInstruction(instruction, mapOf("toAccountId" to to.id.toString(), "trusted" to trusted))
    }

    fun submitSurrender(command: SubmitSurrender): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        if (command.quantity <= 0) throw ValidationException("Quantity must be positive")
        val from = factory.allowanceAccount(command.fromAccountId)
        val holding = factory.reserveAllowances(from.id, command.productCode, command.quantity)
        val instruction = factory.allowanceInstruction(InstructionType.SURRENDER, holding, command.idempotencyKey, command.quantity).copy(
            movementType = AllowanceMovementType.SURRENDER,
            complianceYear = command.complianceYear,
            expectedEffectiveAt = store.now(),
        )
        return submitInstruction(instruction, mapOf("complianceYear" to command.complianceYear))
    }

    private fun submit(instruction: RegistryInstructionView, lot: LotView, request: Map<String, Any?>): RegistryInstructionView {
        val created = submitInstruction(instruction, request) { lots.startTransfer(lot.id, it.id) }
        return created
    }

    /** Persists a new instruction (after [beforeExecution] took hold of the asset) and approves or executes it. */
    private fun submitInstruction(
        instruction: RegistryInstructionView,
        request: Map<String, Any?>,
        beforeExecution: (RegistryInstructionView) -> Unit = {},
    ): RegistryInstructionView {
        val registry = referenceData.requireRegistry(instruction.registryCode)
        if (!registry.active) throw ValidationException("Registry ${registry.code} is not active")
        val channel = registry.integration.name
        execution.gatewayFor(channel, registry.code)
        val needsApproval = instruction.planRef == null && settings.unplannedNeedsApproval
        val created = instruction.copy(
            channel = channel,
            status = if (needsApproval) InstructionStatus.PENDING_APPROVAL else InstructionStatus.PENDING,
            nextAttemptAt = if (needsApproval) null else store.now(),
        )
        repository.insert(created, request)
        beforeExecution(created)
        events.publishEvent(InstructionSubmitted(created))
        return if (needsApproval) created else execution.proceed(created)
    }

    companion object {
        /** Union Registry: transfers to accounts not on the trusted account list execute 26 hours later. */
        val UNTRUSTED_DELAY: java.time.Duration = java.time.Duration.ofHours(26)
    }
}
