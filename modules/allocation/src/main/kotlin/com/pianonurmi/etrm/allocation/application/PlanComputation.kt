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
 * Solves an allocation against the current snapshot of lots, obligations and soft reservations, and analyses coverage;
 * persists nothing (shared by propose, what-if and the coverage report).
 */
@Component
@EnableConfigurationProperties(AllocationSolverSettings::class, AllocationPricingSettings::class)
class PlanComputation(
    private val plans: AllocationPlanRepository,
    private val obligations: ObligationRepository,
    private val problemBuilder: ProblemBuilder,
    private val criteria: CriteriaService,
    private val lotQuery: LotQuery,
    /** Purchases and expected issuance (ADR 0038). */
    private val futureSupply: FutureSupplyProvider,
    private val clock: Clock,
    solverSettings: AllocationSolverSettings = AllocationSolverSettings(),
    pricingSettings: AllocationPricingSettings = AllocationPricingSettings(),
) {
    private val greedy: Solver = GreedySolver(criteria::evaluate)
    private val exact: Solver = CpSatSolver(criteria::evaluate, solverSettings.toDomain(), greedy)
    private val pricer = GlopPricer(criteria::evaluate, pricingSettings.toDomain())

    /** Solves the allocation against the current snapshot; persists nothing (shared by propose and what-if). */
    fun compute(command: ProposeAllocation, now: Instant, actor: String): AllocationPlanView {
        val open = obligations.open(command.holderEntityId).filter { command.obligationIds == null || it.id in command.obligationIds }
        if (open.isEmpty()) throw ValidationException("No open obligations to allocate for entity ${command.holderEntityId}")
        val snapshot = lotQuery.snapshot(command.holderEntityId)
        val softReserved = plans.activeSoftReservations(command.holderEntityId, now)
        // Plans count on supply still to come at its cautious figure (P90), for obligations it arrives in time for.
        val future = if (command.futureSupply) futureSupply.supply(command.holderEntityId, Quantile.P90) else emptyList()
        val built = problemBuilder.build(snapshot, open, softReserved, command.asOf ?: LocalDate.now(clock), future = future)
        val problem = built.problem
        val solved = solverFor(command.solver).solveDetailed(problem)
        val assignments = solved.assignments
        val coverage = CoverageAnalyser(built.scoring::bestBucketCode).analyse(problem, command.holderEntityId, snapshot.seq, now)
        val lines = disaggregate(problem, assignments.filter { problem.classes[it.classIndex].future == null })
        val virtualLines = virtualLines(problem, assignments.filter { problem.classes[it.classIndex].future != null })
        return AllocationPlanView(
            id = Ids.newId(),
            idempotencyKey = command.idempotencyKey,
            holderEntityId = command.holderEntityId,
            snapshotSeq = snapshot.seq,
            solver = command.solver,
            status = PlanStatus.PROPOSED,
            proposedBy = actor,
            proposedAt = now,
            approvedBy = null,
            approvedAt = null,
            decidedBy = null,
            decisionReason = null,
            reservationExpiresAt = now.plus(Duration.ofHours(command.softReservationHours.toLong())),
            objective = mapOf(
                "solver" to solved.solver,
                "requestedSolver" to command.solver.name,
                "obligations" to problem.obligations.size,
                "lotClasses" to problem.classes.size,
                "allocatedMwh" to lines.sumOf { it.quantityMwh },
                "futureSupplyMwh" to virtualLines.sumOf { it.quantityMwh },
                "totalScore" to lines.sumOf { it.score * java.math.BigDecimal(it.quantityMwh) },
                "scoreUnit" to com.pianonurmi.etrm.allocation.api.ScoreBreakdown.EUR_PER_MWH,
            ) + solved.details,
            coverage = coverage,
            lines = lines,
            version = 1,
            value = PlanValuation.of(lines, open, virtualLines),
            pricing = pricer.price(problem, assignments, built.scoring::bestBucketCode),
            virtualLines = virtualLines,
        )
    }

    /** Assignments to supply still to come: one virtual line each, never reserved or instructed (ADR 0038). */
    private fun virtualLines(
        problem: com.pianonurmi.etrm.allocation.domain.AllocationProblem,
        assignments: List<Assignment>,
    ): List<VirtualLineView> = assignments.sortedWith(compareBy({ it.obligationIndex }, { it.score.total }, { it.classIndex }))
        .mapIndexed { i, a ->
            val future = requireNotNull(problem.classes[a.classIndex].future)
            VirtualLineView(
                id = Ids.newId(),
                lineNo = i + 1,
                obligationId = problem.obligations[a.obligationIndex].id,
                kind = future.kind,
                ref = future.ref,
                description = future.description,
                quantityMwh = a.quantityMwh,
                availableFrom = future.availableFrom,
                score = a.score.total,
                breakdown = a.score,
                status = VirtualLineStatus.PLANNED,
            )
        }

    private fun solverFor(kind: SolverKind): Solver = when (kind) {
        SolverKind.GREEDY -> greedy
        SolverKind.EXACT -> exact
    }

    /** Turns class-level assignments into lot-level lines: earliest expiring lots first, splitting the last one. */
    private fun disaggregate(
        problem: com.pianonurmi.etrm.allocation.domain.AllocationProblem,
        assignments: List<Assignment>,
    ): List<AllocationLineView> {
        val remainingPerLot = HashMap<UUID, Long>()
        problem.classes.forEach { cls -> cls.lots.forEach { remainingPerLot[it.lot.id] = it.availableMwh } }
        val lines = mutableListOf<AllocationLineView>()
        var lineNo = 0
        assignments.sortedWith(compareBy({ it.obligationIndex }, { it.score.total }, { it.classIndex })).forEach { a ->
            var need = a.quantityMwh
            val cls = problem.classes[a.classIndex]
            val obligationId = problem.obligations[a.obligationIndex].id
            for (available in cls.lots) {
                if (need == 0L) break
                val left = remainingPerLot.getValue(available.lot.id)
                if (left <= 0) continue
                val qty = minOf(left, need)
                remainingPerLot[available.lot.id] = left - qty
                need -= qty
                lines += AllocationLineView(
                    id = Ids.newId(),
                    lineNo = ++lineNo,
                    obligationId = obligationId,
                    lotId = available.lot.id,
                    lotClassKey = cls.key,
                    quantityMwh = qty,
                    score = a.score.total,
                    breakdown = a.score,
                    status = LineStatus.PROPOSED,
                    reservedLotId = null,
                    instructionId = null,
                    externalRef = null,
                    failureReason = null,
                )
            }
            check(need == 0L) { "Assignment to class ${cls.key} exceeded available lots by $need MWh" }
        }
        return lines
    }

    fun coverage(holderEntityId: UUID, obligationIds: Set<UUID>?): CoverageReport {
        val now = clock.instant()
        val open = obligations.open(holderEntityId).filter { obligationIds == null || it.id in obligationIds }
        val snapshot = lotQuery.snapshot(holderEntityId)
        val built = problemBuilder.build(snapshot, open, plans.activeSoftReservations(holderEntityId, now), LocalDate.now(clock))
        return CoverageAnalyser(built.scoring::bestBucketCode).analyse(built.problem, holderEntityId, snapshot.seq, now)
    }
}
