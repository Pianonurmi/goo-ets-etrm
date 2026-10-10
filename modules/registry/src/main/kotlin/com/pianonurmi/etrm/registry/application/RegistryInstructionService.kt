package com.pianonurmi.etrm.registry.application

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
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
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
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Orchestrates instruction -> execution -> confirmation and keeps the lot lifecycle in step:
 * submit marks the lot IN_TRANSFER, confirmation transfers it out (or cancels it), failure returns it.
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
) : RegistryInstructionCommand, RegistryInstructionQuery {
    override fun submitTransfer(command: SubmitTransfer): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        val lot = prepareLot(command.lotId, command.quantityMwh)
        val route = routes.route(lot.registryCode, command.toRegistryCode)
        if (!route.feasible) throw ValidationException("No transfer route from ${lot.registryCode} to ${command.toRegistryCode}")
        val instruction = newInstruction(
            InstructionType.TRANSFER,
            lot,
            command.idempotencyKey,
            command.planRef,
            command.lineRef,
            command.obligationRef,
        ).copy(toRegistryCode = command.toRegistryCode.uppercase(), toAccountRef = command.toAccountRef)
        repository.insert(instruction, mapOf("route" to route))
        lots.startTransfer(lot.id, instruction.id)
        events.publishEvent(InstructionSubmitted(instruction))
        return execute(instruction)
    }

    override fun submitCancellation(command: SubmitCancellation): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        val lot = prepareLot(command.lotId, command.quantityMwh)
        val instruction = newInstruction(
            InstructionType.CANCELLATION,
            lot,
            command.idempotencyKey,
            command.planRef,
            command.lineRef,
            command.obligationRef,
        ).copy(cancellation = command.details)
        repository.insert(instruction, mapOf("cancellation" to command.details))
        lots.startTransfer(lot.id, instruction.id)
        events.publishEvent(InstructionSubmitted(instruction))
        return execute(instruction)
    }

    override fun confirm(instructionId: UUID, externalRef: String?): RegistryInstructionView {
        val current = locked(instructionId)
        return applyConfirmation(current, externalRef, actorProvider.current().userId, null)
    }

    override fun fail(instructionId: UUID, reason: String): RegistryInstructionView = applyFailure(locked(instructionId), reason, null)

    override fun cancel(instructionId: UUID, reason: String): RegistryInstructionView {
        val next = RegistryInstruction(locked(instructionId)).cancelled(reason)
        save(next, null)
        val lot = lotQuery.requireLot(next.lotId)
        if (lot.status == LotStatus.IN_TRANSFER && lot.instructionRef == next.id) lots.failTransfer(next.lotId, next.id, "instruction cancelled: $reason")
        return next
    }

    override fun retry(instructionId: UUID): RegistryInstructionView {
        val current = locked(instructionId)
        if (current.status != InstructionStatus.PENDING && current.status != InstructionStatus.FAILED) {
            throw ConflictException("Instruction ${current.instructionKey} is ${current.status}; only PENDING or FAILED can be retried")
        }
        val lot = lotQuery.requireLot(current.lotId)
        if (lot.status != LotStatus.IN_TRANSFER) lots.startTransfer(lot.id, current.id)
        return execute(current)
    }

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
            createdAt = clock.instant(),
            sentAt = null,
            confirmedAt = null,
            version = 1,
        )

    private fun execute(instruction: RegistryInstructionView): RegistryInstructionView {
        val registry = referenceData.requireRegistry(instruction.registryCode)
        val gateway = gateways.firstOrNull { it.supports(registry.code, registry.integration.name) }
            ?: throw ConflictException("No registry gateway for ${registry.code} (${registry.integration})")
        return when (val result = gateway.execute(instruction)) {
            is GatewayResult.Confirmed -> applyConfirmation(
                instruction,
                result.externalRef,
                "gateway:${registry.integration.name.lowercase()}",
                result.response,
            )
            is GatewayResult.Sent -> RegistryInstruction(instruction).sent(result.externalRef, clock.instant()).also { save(it, result.response) }
            is GatewayResult.Failed -> applyFailure(instruction, result.reason, result.response)
        }
    }

    private fun applyConfirmation(current: RegistryInstructionView, externalRef: String?, by: String, response: Map<String, Any?>?): RegistryInstructionView {
        val next = RegistryInstruction(current).confirmed(externalRef, by, clock.instant())
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

    private fun save(instruction: RegistryInstructionView, response: Map<String, Any?>?) {
        if (!repository.update(instruction, response)) throw ConflictException("Instruction ${instruction.instructionKey} changed concurrently")
    }
}
