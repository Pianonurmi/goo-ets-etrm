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

/**
 * Plan lifecycle: propose (solve, soft-reserve) -> approve (four-eyes, hard-reserve, instruct) -> confirm per line
 * as registry instructions complete ([PlanOutcomes]) -> close. Proposals and the approve commit run under a per-entity
 * advisory lock, so two proposals for one entity soft-reserve one after the other; approve marks the plan STALE if any
 * lot can no longer be reserved.
 */
@Service
@Transactional
class PlanLifecycle(
    private val plans: AllocationPlanRepository,
    private val computation: PlanComputation,
    private val reservations: LotReservations,
    private val planInstructions: PlanInstructions,
    private val instructionQuery: RegistryInstructionQuery,
    private val outcomes: PlanOutcomes,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(PlanLifecycle::class.java)

    fun propose(command: ProposeAllocation): AllocationPlanView {
        actorProvider.authorise(command.holderEntityId, Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        // Serialises proposals for the entity: the second one computes against the first one's soft reservations.
        plans.lockEntity(command.holderEntityId)
        plans.byKey(command.idempotencyKey)?.let { return it }
        val plan = computation.compute(command, clock.instant(), actorProvider.current().userId)
        plans.insert(plan)
        plans.replaceSoftReservations(plan.id, plan.lines.groupBy { it.lotId }.mapValues { (_, ls) -> ls.sumOf { it.quantityMwh } }, plan.reservationExpiresAt)
        events.publishEvent(AllocationProposed(plan))
        return plan
    }

    /** Hard reservations, then registry instructions; a lot that can no longer be reserved makes the plan stale. */
    fun approve(planId: UUID): AllocationPlanView {
        actorProvider.authorise(requirePlan(planId).holderEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        val actor = actorProvider.current().userId
        val current = plans.locked(planId)
        plans.lockEntity(current.holderEntityId)
        val aggregate = AllocationPlan(current)
        val approved = aggregate.approve(actor, clock.instant())
        plans.save(approved)
        plans.deleteSoftReservations(planId)

        val reservedLines = mutableListOf<AllocationLineView>()
        for (line in approved.lines) {
            try {
                reservedLines += reservations.reserve(line, planId)
            } catch (ex: DomainException) {
                log.warn("Plan {} is stale: lot {} could not be reserved ({})", planId, line.lotId, ex.message)
                reservedLines.forEach { reservations.releaseQuietly(it, planId) }
                val stale = aggregate.stale("lot ${line.lotId}: ${ex.message}", actor)
                plans.save(stale)
                events.publishEvent(AllocationStale(stale, ex.message ?: "stale"))
                return stale
            }
        }
        var plan = aggregate.reserved(reservedLines)
        plans.save(plan)

        val instructed = plan.lines.map { line -> planInstructions.instruct(plan, line) }
        plan = aggregate.instructed(instructed)
        plans.save(plan)
        events.publishEvent(AllocationApproved(plan))
        // Instructions confirmed synchronously (simulator, API) are settled right away.
        plan.lines.filter { it.status == LineStatus.INSTRUCTED && it.instructionId != null }.forEach { line ->
            instructionQuery.instruction(line.instructionId!!)
                ?.takeIf { it.status == InstructionStatus.CONFIRMED }
                ?.let { confirmed -> plan = outcomes.applyConfirmation(AllocationPlan(plan), line.id, confirmed) }
        }
        return plan
    }

    fun reject(planId: UUID, reason: String): AllocationPlanView {
        actorProvider.authorise(requirePlan(planId).holderEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        val rejected = AllocationPlan(plans.locked(planId)).reject(actorProvider.current().userId, reason)
        plans.save(rejected)
        plans.deleteSoftReservations(planId)
        events.publishEvent(AllocationRejected(rejected, reason))
        return rejected
    }

    fun cancel(planId: UUID, reason: String): AllocationPlanView {
        actorProvider.authorise(requirePlan(planId).holderEntityId, Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        val current = plans.locked(planId)
        val cancelled = AllocationPlan(current).cancel(actorProvider.current().userId, reason)
        plans.save(cancelled)
        plans.deleteSoftReservations(planId)
        current.lines.filter { it.status == LineStatus.RESERVED || it.status == LineStatus.INSTRUCTED || it.status == LineStatus.FAILED }.forEach { line ->
            line.instructionId?.let { planInstructions.cancelQuietly(it, planId, reason) }
            reservations.releaseQuietly(line, planId)
        }
        return cancelled
    }

    fun expireStalePlans(now: Instant): Int {
        var count = 0
        plans.expiredProposals(now).forEach { id ->
            val plan = plans.lock(id) ?: return@forEach
            if (plan.status != PlanStatus.PROPOSED) return@forEach
            plans.save(AllocationPlan(plan).expire(now))
            plans.deleteSoftReservations(id)
            count++
        }
        return count
    }

    private fun requirePlan(id: UUID): AllocationPlanView = plans.find(id) ?: throw NotFoundException("AllocationPlan", id)
}
