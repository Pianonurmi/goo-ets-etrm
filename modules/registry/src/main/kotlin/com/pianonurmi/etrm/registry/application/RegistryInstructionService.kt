package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
    private val allowances: AllowanceHoldingCommand,
    private val allowanceQuery: AllowanceHoldingQuery,
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

    override fun submitAllowanceTransfer(command: SubmitAllowanceTransfer): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        if (command.quantity <= 0) throw ValidationException("Quantity must be positive")
        if (command.movementType != AllowanceMovementType.SALE && command.movementType != AllowanceMovementType.TRANSFER_OUT) {
            throw ValidationException("Allowance transfers record SALE or TRANSFER_OUT, not ${command.movementType}")
        }
        if (command.movementType == AllowanceMovementType.SALE && command.unitPrice == null) throw ValidationException("A sale needs the unit price")
        val from = allowanceAccount(command.fromAccountId)
        val to = referenceData.requireRegistryAccount(command.toAccountId)
        if (!to.active) throw ValidationException("Receiving account ${to.accountNumber} is inactive")
        if (to.registryCode != from.registryCode) {
            throw ValidationException(
                "Allowances move within ${from.registryCode}; ${to.accountNumber} is in ${to.registryCode}",
            )
        }
        if (to.id == from.id) throw ValidationException("Source and receiving account are the same")
        val holding = reserveAllowances(from.id, command.productCode, command.quantity)
        val now = now()
        // Own accounts and trusted counterparty accounts settle at once; others wait the registry's 26-hour delay.
        val trusted = to.holderType == HolderType.LEGAL_ENTITY || to.trustedOn(LocalDate.ofInstant(now, clock.zone))
        val instruction = allowanceInstruction(InstructionType.ALLOWANCE_TRANSFER, holding, command.idempotencyKey, command.quantity).copy(
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

    override fun submitSurrender(command: SubmitSurrender): RegistryInstructionView {
        repository.byKey(command.idempotencyKey)?.let { return it }
        if (command.quantity <= 0) throw ValidationException("Quantity must be positive")
        val from = allowanceAccount(command.fromAccountId)
        val holding = reserveAllowances(from.id, command.productCode, command.quantity)
        val instruction = allowanceInstruction(InstructionType.SURRENDER, holding, command.idempotencyKey, command.quantity).copy(
            movementType = AllowanceMovementType.SURRENDER,
            complianceYear = command.complianceYear,
            expectedEffectiveAt = now(),
        )
        return submitInstruction(instruction, mapOf("complianceYear" to command.complianceYear))
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
        val current = locked(instructionId)
        val next = RegistryInstruction(current).cancelled(reason)
        save(next, null)
        when (next.assetKind) {
            AssetKind.GOO_LOT -> {
                val lot = lotQuery.requireLot(next.requireLot())
                if (lot.status == LotStatus.IN_TRANSFER && lot.instructionRef == next.id) lots.failTransfer(lot.id, next.id, "instruction cancelled: $reason")
            }
            // A FAILED allowance instruction has already given its reservation back.
            AssetKind.ALLOWANCE -> if (current.status != InstructionStatus.FAILED) allowances.release(next.holdingRef!!, next.quantity)
        }
        events.publishEvent(InstructionCancelled(next, reason))
        return next
    }

    override fun retry(instructionId: UUID): RegistryInstructionView {
        val current = locked(instructionId)
        val next = RegistryInstruction(current).resubmitted(settings.unplannedNeedsApproval, now())
        save(next, null)
        when (next.assetKind) {
            AssetKind.GOO_LOT -> {
                val lot = lotQuery.requireLot(next.requireLot())
                if (lot.status != LotStatus.IN_TRANSFER) lots.startTransfer(lot.id, next.id)
            }
            AssetKind.ALLOWANCE -> if (current.status == InstructionStatus.FAILED) allowances.reserve(next.holdingRef!!, next.quantity)
        }
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
    override fun search(search: InstructionSearch): InstructionPage {
        val actor = actorProvider.current()
        search.holderEntityId?.let(actor::requireEntity)
        return repository.search(search.copy(holderEntityIds = actor.entityFilter))
    }

    @Transactional(readOnly = true)
    override fun forPlan(planRef: UUID): List<RegistryInstructionView> = repository.forPlan(planRef)

    // ----------------------------------------------------------------------------------------------------------

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
        gatewayFor(channel, registry.code)
        val needsApproval = instruction.planRef == null && settings.unplannedNeedsApproval
        val created = instruction.copy(
            channel = channel,
            status = if (needsApproval) InstructionStatus.PENDING_APPROVAL else InstructionStatus.PENDING,
            nextAttemptAt = if (needsApproval) null else now(),
        )
        repository.insert(created, request)
        beforeExecution(created)
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
    private fun requireUsableAccount(accountId: UUID, export: Boolean): RegistryAccountView {
        val account = referenceData.requireRegistryAccount(accountId)
        if (!account.active) throw ValidationException("Registry account ${account.accountNumber} is inactive")
        if (export && !account.canExport) throw ValidationException("Registry account ${account.accountNumber} may not export")
        return account
    }

    /** One of our accounts in an emission allowance registry. */
    private fun allowanceAccount(accountId: UUID): RegistryAccountView {
        val account = requireUsableAccount(accountId, export = true)
        if (account.holderType != HolderType.LEGAL_ENTITY) throw ValidationException("Account ${account.accountNumber} is not one of ours")
        if (referenceData.requireRegistry(account.registryCode).registryType != RegistryType.EMISSION_ALLOWANCE) {
            throw ValidationException("Account ${account.accountNumber} is not an emission allowance account")
        }
        return account
    }

    private fun reserveAllowances(accountId: UUID, productCode: String, quantity: Long): AllowanceHoldingView {
        val holding = allowanceQuery.holding(accountId, productCode.uppercase())
            ?: throw ValidationException("No ${productCode.uppercase()} holding on account $accountId")
        return allowances.reserve(holding.id, quantity)
    }

    private fun allowanceInstruction(type: InstructionType, holding: AllowanceHoldingView, key: String, quantity: Long): RegistryInstructionView {
        val account = referenceData.requireRegistryAccount(holding.registryAccountId)
        return RegistryInstructionView(
            id = Ids.newId(),
            instructionKey = key,
            type = type,
            registryCode = account.registryCode,
            holderEntityId = holding.holderEntityId,
            fromAccountId = holding.registryAccountId,
            toRegistryCode = null,
            toAccountRef = null,
            lotId = null,
            range = null,
            quantity = quantity,
            cancellation = null,
            planRef = null,
            lineRef = null,
            obligationRef = null,
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
            assetKind = AssetKind.ALLOWANCE,
            productCode = holding.productCode,
            holdingRef = holding.id,
        )
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
            quantity = lot.quantityMwh,
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
        when (next.type) {
            InstructionType.TRANSFER -> events.publishEvent(InstructionConfirmed(next, lots.confirmTransferOut(next.requireLot(), next.id, next.externalRef)))
            InstructionType.CANCELLATION -> events.publishEvent(
                InstructionConfirmed(next, lots.cancel(next.requireLot(), null, next.cancellation!!.copy(registryCancellationRef = next.externalRef))),
            )
            InstructionType.ALLOWANCE_TRANSFER, InstructionType.SURRENDER -> {
                allowances.release(next.holdingRef!!, next.quantity)
                val holding = allowances.record(
                    RecordAllowanceMovement(
                        holderEntityId = next.holderEntityId,
                        registryAccountId = next.fromAccountId,
                        productCode = next.productCode!!,
                        type = next.movementType ?: AllowanceMovementType.TRANSFER_OUT,
                        quantity = next.quantity,
                        effectiveDate = LocalDate.ofInstant(next.confirmedAt ?: now(), clock.zone),
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
        val target = referenceData.registryAccountByNumber(instruction.toRegistryCode!!, instruction.toAccountRef!!) ?: return
        if (target.holderType != HolderType.LEGAL_ENTITY) return
        val sale = instruction.movementType == AllowanceMovementType.SALE
        allowances.record(
            RecordAllowanceMovement(
                holderEntityId = target.holderId,
                registryAccountId = target.id,
                productCode = instruction.productCode!!,
                type = if (sale) AllowanceMovementType.PURCHASE else AllowanceMovementType.TRANSFER_IN,
                quantity = instruction.quantity,
                effectiveDate = LocalDate.ofInstant(instruction.confirmedAt ?: now(), clock.zone),
                reference = instruction.externalRef ?: instruction.instructionKey,
                costPerUnit = instruction.unitPrice,
                costCurrency = instruction.priceCurrency,
            ),
        )
    }

    private fun applyFailure(current: RegistryInstructionView, reason: String, response: Map<String, Any?>?): RegistryInstructionView {
        val next = RegistryInstruction(current).failed(reason)
        save(next, response)
        when (next.assetKind) {
            AssetKind.GOO_LOT -> lots.failTransfer(next.requireLot(), next.id, reason)
            AssetKind.ALLOWANCE -> allowances.release(next.holdingRef!!, next.quantity)
        }
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

        /** Union Registry: transfers to accounts not on the trusted account list execute 26 hours later. */
        val UNTRUSTED_DELAY: java.time.Duration = java.time.Duration.ofHours(26)
    }
}
