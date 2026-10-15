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

/** Plans counting on a purchase or on expected issuance (ADR 0038) hear when it changes. */
@Component
@Transactional
class FutureSupplyWatch(
    private val plans: AllocationPlanRepository,
    private val referenceData: ReferenceDataQuery,
    private val savepoints: Savepoints,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(FutureSupplyWatch::class.java)

    // ----------------------------------------------------------------------------------------------------------
    // Future supply (ADR 0038): plans counting on a purchase or on expected issuance hear when it changes
    // ----------------------------------------------------------------------------------------------------------

    @EventListener
    fun on(event: DeliveryObligationChanged) {
        if (event.obligation.side == Side.BUY) {
            futureSupplyChanged(
                "purchase:${event.obligation.scheduleId}",
                "purchase ${event.obligation.tradeNumber} changed",
            )
        }
    }

    @EventListener
    fun on(event: DeliveryObligationClosed) {
        if (event.obligation.side == Side.BUY) {
            futureSupplyChanged("purchase:${event.obligation.scheduleId}", "purchase ${event.obligation.tradeNumber} closed: ${event.reason}")
        }
    }

    @EventListener
    fun on(event: ProductionForecastChanged) {
        val gsrn = referenceData.productionDevice(event.deviceId)?.gsrn ?: return
        event.months.forEach { futureSupplyChanged("issuance:$gsrn:$it", "forecast of $gsrn for $it changed") }
    }

    /** Issued certificates replace expected issuance: plans counting on it should be made again with the real lots. */
    @EventListener
    fun on(event: LotReceived) {
        val lot = event.lot
        if (lot.acquisitionSource != AcquisitionSource.ISSUANCE) return
        val month = YearMonth.from(lot.productionPeriod.start)
        futureSupplyChanged("issuance:${lot.deviceGsrn}:$month", "certificates of ${lot.deviceGsrn} for $month issued")
    }

    /**
     * A proposed plan counting on the changed supply goes stale and frees its soft reservations, so it is proposed
     * again rather than approved on figures that no longer hold; in a plan already approved, the virtual lines are
     * marked CHANGED (nothing was reserved for them). Never fails the transaction that published the change.
     */
    private fun futureSupplyChanged(sourceRef: String, reason: String) {
        plans.countingOn(sourceRef).forEach { planId ->
            runCatching {
                savepoints.attempt {
                    val plan = plans.lock(planId) ?: return@attempt
                    if (plan.status == PlanStatus.PROPOSED) {
                        val stale = AllocationPlan(plan).stale(reason, SYSTEM_USER)
                        plans.save(stale)
                        plans.deleteSoftReservations(planId)
                        events.publishEvent(AllocationStale(stale, reason))
                    } else if (plan.virtualLines.any { it.ref == sourceRef && it.status == VirtualLineStatus.PLANNED }) {
                        plans.save(
                            plan.copy(
                                virtualLines = plan.virtualLines.map { if (it.ref == sourceRef) it.copy(status = VirtualLineStatus.CHANGED) else it },
                                version = plan.version + 1,
                            ),
                        )
                    }
                }
            }.onFailure { log.warn("Plan {} could not be updated after a change to {}: {}", planId, sourceRef, it.message) }
        }
    }
}
