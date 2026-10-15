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
 * Registry outcomes of a plan's instructions: a confirmed line fulfils its obligation and records the trade's
 * delivery; a failed line is marked; a plan whose lines are all decided completes.
 */
@Component
@Transactional
class PlanOutcomes(
    private val plans: AllocationPlanRepository,
    private val obligationService: ObligationService,
    private val tradeQuery: TradeQuery,
    private val tradeCommand: TradeCommand,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @EventListener
    fun on(event: InstructionConfirmed) {
        val lineRef = event.instruction.lineRef ?: return
        val plan = event.instruction.planRef?.let { plans.lock(it) } ?: return
        val line = AllocationPlan.lineBy(plan, lineRef) ?: return
        if (line.status != LineStatus.INSTRUCTED) return
        applyConfirmation(AllocationPlan(plan), lineRef, event.instruction)
    }

    @EventListener
    fun on(event: InstructionFailed) {
        val lineRef = event.instruction.lineRef ?: return
        val plan = event.instruction.planRef?.let { plans.lock(it) } ?: return
        val line = AllocationPlan.lineBy(plan, lineRef) ?: return
        if (line.status != LineStatus.INSTRUCTED) return
        val aggregate = AllocationPlan(plan)
        plans.save(aggregate.lineUpdated(line.copy(status = LineStatus.FAILED, failureReason = event.reason)))
        finishIfDone(aggregate)
    }

    fun applyConfirmation(aggregate: AllocationPlan, lineId: UUID, instruction: RegistryInstructionView): AllocationPlanView {
        val line = AllocationPlan.lineBy(aggregate.state, lineId) ?: return aggregate.state
        val confirmedLine = line.copy(status = LineStatus.CONFIRMED, externalRef = instruction.externalRef, instructionId = instruction.id)
        val plan = aggregate.lineUpdated(confirmedLine)
        plans.save(plan)
        val obligation = obligationService.fulfil(line.obligationId, line.quantityMwh)
        recordTradeDelivery(obligation, line, instruction)
        events.publishEvent(AllocationLineConfirmed(plan, confirmedLine, obligation))
        finishIfDone(aggregate)
        return aggregate.state
    }

    private fun recordTradeDelivery(obligation: ObligationView, line: AllocationLineView, instruction: RegistryInstructionView) {
        val scheduleId = obligation.sourceRef ?: return
        if (obligation.kind != ObligationKind.SALE && obligation.kind != ObligationKind.INTERCOMPANY) return
        val tradeObligation = tradeQuery.obligation(scheduleId) ?: return
        tradeCommand.recordDelivery(
            tradeObligation.tradeId,
            RecordDelivery(
                scheduleId,
                java.math.BigDecimal(line.quantityMwh),
                LocalDate.ofInstant(instruction.confirmedAt ?: clock.instant(), clock.zone),
                instruction.externalRef,
                idempotencyKey = "instruction:${instruction.id}",
            ),
        )
    }

    private fun finishIfDone(aggregate: AllocationPlan) {
        if (aggregate.state.status == PlanStatus.CONFIRMED || aggregate.state.status == PlanStatus.PARTIALLY_FAILED) {
            events.publishEvent(AllocationCompleted(aggregate.state))
        }
    }
}
