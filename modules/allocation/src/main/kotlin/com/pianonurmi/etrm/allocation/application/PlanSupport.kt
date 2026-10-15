package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.AllocationApproved
import com.pianonurmi.etrm.allocation.api.AllocationCommand
import com.pianonurmi.etrm.allocation.api.AllocationCompleted
import com.pianonurmi.etrm.allocation.api.AllocationLineConfirmed
import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.AllocationPlanView
import com.pianonurmi.etrm.allocation.api.AllocationProposed
import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.allocation.api.AllocationRejected
import com.pianonurmi.etrm.allocation.api.AllocationStale
import com.pianonurmi.etrm.allocation.api.AllocationWhatIf
import com.pianonurmi.etrm.allocation.api.CoverageReport
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.PlanStatus
import com.pianonurmi.etrm.allocation.api.ProposeAllocation
import com.pianonurmi.etrm.allocation.api.SolverKind
import com.pianonurmi.etrm.allocation.api.VirtualLineStatus
import com.pianonurmi.etrm.allocation.api.VirtualLineView
import com.pianonurmi.etrm.allocation.domain.AllocationPlan
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.Assignment
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.CpSatSolver
import com.pianonurmi.etrm.allocation.domain.GlopPricer
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.allocation.domain.PlanValuation
import com.pianonurmi.etrm.allocation.domain.Solver
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotReceived
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.ProductionForecastChanged
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.trade.api.DeliveryObligationChanged
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeQuery
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** The user System acts as when supply changes under a plan. */
internal const val SYSTEM_USER = "system"

/** The current user needs one of [roles] and access to the holder entity. */
internal fun ActorProvider.authorise(holderEntityId: UUID, vararg roles: Role) {
    val actor = current()
    actor.requireRole(*roles)
    actor.requireEntity(holderEntityId)
}

internal fun AllocationPlanRepository.locked(id: UUID): AllocationPlanView = lock(id) ?: throw NotFoundException("AllocationPlan", id)

/** Saves the plan's next version; another writer in between is a stale version. */
internal fun AllocationPlanRepository.save(plan: AllocationPlanView) {
    if (!update(plan)) throw StaleVersionException("AllocationPlan", plan.id, plan.version - 1, -1)
}

/** Hard reservations of a plan's lots, each in a savepoint. */
@Component
class LotReservations(private val lotQuery: LotQuery, private val lotCommand: LotCommand, private val savepoints: Savepoints) {
    private val log = LoggerFactory.getLogger(LotReservations::class.java)

    /** Reserves the line's lot (a split of it when the line takes part); a lot that cannot be reserved throws. */
    fun reserve(line: AllocationLineView, planId: UUID): AllocationLineView {
        val lot = lotQuery.requireLot(line.lotId)
        val quantity = if (lot.quantityMwh == line.quantityMwh) null else line.quantityMwh
        val reserved = savepoints.attempt { lotCommand.reserve(line.lotId, quantity, planId) }
        return line.copy(status = LineStatus.RESERVED, reservedLotId = reserved.id)
    }

    fun releaseQuietly(line: AllocationLineView, planId: UUID) {
        val lotId = line.reservedLotId ?: return
        runCatching {
            savepoints.attempt {
                val lot = lotQuery.requireLot(lotId)
                if (lot.status == LotStatus.RESERVED && lot.reservationRef == planId) lotCommand.release(lotId, planId)
            }
        }.onFailure { log.warn("Could not release lot {} of plan {}", lotId, planId, it) }
    }
}

/** The registry instructions that carry out a plan's lines. */
@Component
class PlanInstructions(
    private val obligations: ObligationRepository,
    private val lotQuery: LotQuery,
    private val instructions: RegistryInstructionCommand,
    private val savepoints: Savepoints,
) {
    private val log = LoggerFactory.getLogger(PlanInstructions::class.java)

    fun instruct(plan: AllocationPlanView, line: AllocationLineView): AllocationLineView {
        val obligation = obligations.find(line.obligationId) ?: throw NotFoundException("Obligation", line.obligationId)
        val key = "plan:${plan.id}:line:${line.id}"
        return try {
            val instruction = savepoints.attempt { submit(plan, line, obligation, key) }
            line.copy(status = LineStatus.INSTRUCTED, instructionId = instruction.id, externalRef = instruction.externalRef)
        } catch (ex: DomainException) {
            log.warn("Plan {} line {} could not be instructed: {}", plan.id, line.lineNo, ex.message)
            line.copy(status = LineStatus.FAILED, failureReason = ex.message)
        }
    }

    private fun submit(
        plan: AllocationPlanView,
        line: AllocationLineView,
        obligation: ObligationView,
        key: String,
    ): RegistryInstructionView = when (obligation.kind) {
        ObligationKind.SALE, ObligationKind.INTERCOMPANY -> instructions.submitTransfer(
            SubmitTransfer(
                lotId = line.reservedLotId!!,
                quantityMwh = null,
                toRegistryCode = obligation.targetRegistryCode ?: lotQuery.requireLot(line.reservedLotId).registryCode,
                toAccountRef = obligation.targetAccountRef ?: throw ValidationException("Obligation ${obligation.id} has no target account"),
                idempotencyKey = key,
                planRef = plan.id,
                lineRef = line.id,
                obligationRef = obligation.id,
            ),
        )
        ObligationKind.DISCLOSURE, ObligationKind.OWN_CONSUMPTION -> instructions.submitCancellation(
            SubmitCancellation(
                lotId = line.reservedLotId!!,
                quantityMwh = null,
                details = (obligation.cancellation ?: throw ValidationException("Obligation ${obligation.id} has no cancellation details"))
                    .copy(obligationRef = obligation.id),
                idempotencyKey = key,
                planRef = plan.id,
                lineRef = line.id,
                obligationRef = obligation.id,
            ),
        )
    }

    fun cancelQuietly(instructionId: UUID, planId: UUID, reason: String) {
        runCatching { savepoints.attempt { instructions.cancel(instructionId, "plan cancelled: $reason") } }
            .onFailure { log.warn("Could not cancel instruction {} of plan {}", instructionId, planId, it) }
    }
}
