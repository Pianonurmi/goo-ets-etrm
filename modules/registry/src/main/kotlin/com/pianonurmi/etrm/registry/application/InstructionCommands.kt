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

/** An operator's or the inbox's commands on an instruction: approve, cancel, retry. */
@Service
@Transactional
class InstructionCommands(
    private val store: InstructionStore,
    private val execution: InstructionExecution,
    private val lots: LotCommand,
    private val lotQuery: LotQuery,
    private val allowances: AllowanceHoldingCommand,
    private val events: ApplicationEventPublisher,
    private val settings: RegistryDispatchSettings = RegistryDispatchSettings(),
) {
    /** Second-person approval of a manual instruction (from the inbox). */
    fun approve(instructionId: UUID, by: String): RegistryInstructionView {
        val current = store.locked(instructionId)
        SecondPerson.require(by, current.submittedBy) { "Instruction submitter may not approve it" }
        val approved = RegistryInstruction(current).approved(by, store.now())
        store.save(approved, null)
        return execution.proceed(approved)
    }

    fun cancel(instructionId: UUID, reason: String): RegistryInstructionView {
        val current = store.locked(instructionId)
        val next = RegistryInstruction(current).cancelled(reason)
        store.save(next, null)
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

    fun retry(instructionId: UUID): RegistryInstructionView {
        val current = store.locked(instructionId)
        val next = RegistryInstruction(current).resubmitted(settings.unplannedNeedsApproval, store.now())
        store.save(next, null)
        when (next.assetKind) {
            AssetKind.GOO_LOT -> {
                val lot = lotQuery.requireLot(next.requireLot())
                if (lot.status != LotStatus.IN_TRANSFER) lots.startTransfer(lot.id, next.id)
            }
            AssetKind.ALLOWANCE -> if (current.status == InstructionStatus.FAILED) allowances.reserve(next.holdingRef!!, next.quantity)
        }
        events.publishEvent(InstructionSubmitted(next))
        return if (next.status == InstructionStatus.PENDING_APPROVAL) next else execution.proceed(next)
    }
}
