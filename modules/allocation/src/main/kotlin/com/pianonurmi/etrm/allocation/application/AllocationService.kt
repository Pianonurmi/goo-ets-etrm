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
import com.pianonurmi.etrm.allocation.domain.AllocationPlan
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.Assignment
import com.pianonurmi.etrm.allocation.domain.CoverageAnalyser
import com.pianonurmi.etrm.allocation.domain.CpSatSolver
import com.pianonurmi.etrm.allocation.domain.GreedySolver
import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.allocation.domain.PlanValuation
import com.pianonurmi.etrm.allocation.domain.Solver
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
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
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeQuery
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Plan lifecycle: propose (solve, soft-reserve) -> approve (four-eyes, hard-reserve, instruct) -> confirm per line
 * as registry instructions complete -> close. Proposals read without locks; the approve commit runs under a
 * per-entity advisory lock and marks the plan STALE if any lot can no longer be reserved.
 */
@Service
@Transactional
@EnableConfigurationProperties(AllocationSolverSettings::class)
class AllocationService(
    private val plans: AllocationPlanRepository,
    private val obligations: ObligationRepository,
    private val obligationService: ObligationService,
    private val problemBuilder: ProblemBuilder,
    private val criteria: CriteriaService,
    private val lotQuery: LotQuery,
    private val lotCommand: LotCommand,
    private val instructions: RegistryInstructionCommand,
    private val instructionQuery: RegistryInstructionQuery,
    private val tradeCommand: TradeCommand,
    private val tradeQuery: TradeQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val savepoints: Savepoints,
    solverSettings: AllocationSolverSettings = AllocationSolverSettings(),
) : AllocationCommand, AllocationQuery {
    private val log = LoggerFactory.getLogger(AllocationService::class.java)
    private val greedy: Solver = GreedySolver(criteria::evaluate)
    private val exact: Solver = CpSatSolver(criteria::evaluate, solverSettings.toDomain(), greedy)

    // ----------------------------------------------------------------------------------------------------------
    // Propose
    // ----------------------------------------------------------------------------------------------------------

    override fun propose(command: ProposeAllocation): AllocationPlanView {
        authorise(command.holderEntityId, Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        plans.byKey(command.idempotencyKey)?.let { return it }
        val plan = compute(command, clock.instant())
        plans.insert(plan)
        plans.replaceSoftReservations(plan.id, plan.lines.groupBy { it.lotId }.mapValues { (_, ls) -> ls.sumOf { it.quantityMwh } }, plan.reservationExpiresAt)
        events.publishEvent(AllocationProposed(plan))
        return plan
    }

    @Transactional(readOnly = true)
    override fun whatIf(command: ProposeAllocation): AllocationWhatIf {
        authorise(command.holderEntityId, Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        val plan = compute(command, clock.instant())
        return AllocationWhatIf(
            plan.holderEntityId,
            plan.snapshotSeq,
            plan.solver,
            plan.proposedAt,
            plan.objective,
            requireNotNull(plan.coverage) { "computed plans carry coverage" },
            plan.lines,
            plan.value,
        )
    }

    private fun authorise(holderEntityId: UUID, vararg roles: Role) {
        val actor = actorProvider.current()
        actor.requireRole(*roles)
        actor.requireEntity(holderEntityId)
    }

    /** Solves the allocation against the current snapshot; persists nothing (shared by propose and what-if). */
    private fun compute(command: ProposeAllocation, now: Instant): AllocationPlanView {
        val actor = actorProvider.current().userId
        val open = obligations.open(command.holderEntityId).filter { command.obligationIds == null || it.id in command.obligationIds }
        if (open.isEmpty()) throw ValidationException("No open obligations to allocate for entity ${command.holderEntityId}")
        val snapshot = lotQuery.snapshot(command.holderEntityId)
        val softReserved = plans.activeSoftReservations(command.holderEntityId, now)
        val built = problemBuilder.build(snapshot, open, softReserved, command.asOf ?: LocalDate.now(clock))
        val problem = built.problem
        val solved = solverFor(command.solver).solveDetailed(problem)
        val assignments = solved.assignments
        val coverage = CoverageAnalyser(built.scoring::bestBucketCode).analyse(problem, command.holderEntityId, snapshot.seq, now)
        val lines = disaggregate(problem, assignments)
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
                "totalScore" to lines.sumOf { it.score * java.math.BigDecimal(it.quantityMwh) },
                "scoreUnit" to com.pianonurmi.etrm.allocation.api.ScoreBreakdown.EUR_PER_MWH,
            ) + solved.details,
            coverage = coverage,
            lines = lines,
            version = 1,
            value = PlanValuation.of(lines, open),
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

    // ----------------------------------------------------------------------------------------------------------
    // Approve: hard reservations and registry instructions
    // ----------------------------------------------------------------------------------------------------------

    override fun approve(planId: UUID): AllocationPlanView {
        authorise(requirePlan(planId).holderEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        val actor = actorProvider.current().userId
        val current = locked(planId)
        plans.lockEntity(current.holderEntityId)
        val aggregate = AllocationPlan(current)
        val approved = aggregate.approve(actor, clock.instant())
        save(approved)
        plans.deleteSoftReservations(planId)

        val reservedLines = mutableListOf<AllocationLineView>()
        for (line in approved.lines) {
            try {
                val lot = lotQuery.requireLot(line.lotId)
                val quantity = if (lot.quantityMwh == line.quantityMwh) null else line.quantityMwh
                val reserved = savepoints.attempt { lotCommand.reserve(line.lotId, quantity, planId) }
                reservedLines += line.copy(status = LineStatus.RESERVED, reservedLotId = reserved.id)
            } catch (ex: DomainException) {
                log.warn("Plan {} is stale: lot {} could not be reserved ({})", planId, line.lotId, ex.message)
                reservedLines.forEach { releaseQuietly(it, planId) }
                val stale = aggregate.stale("lot ${line.lotId}: ${ex.message}", actor)
                save(stale)
                events.publishEvent(AllocationStale(stale, ex.message ?: "stale"))
                return stale
            }
        }
        var plan = aggregate.reserved(reservedLines)
        save(plan)

        val instructed = plan.lines.map { line -> instruct(plan, line) }
        plan = aggregate.instructed(instructed)
        save(plan)
        events.publishEvent(AllocationApproved(plan))
        // Instructions confirmed synchronously (simulator, API) are settled right away.
        plan.lines.filter { it.status == LineStatus.INSTRUCTED && it.instructionId != null }.forEach { line ->
            instructionQuery.instruction(line.instructionId!!)
                ?.takeIf { it.status == InstructionStatus.CONFIRMED }
                ?.let { confirmed -> plan = applyConfirmation(AllocationPlan(plan), line.id, confirmed) }
        }
        return plan
    }

    private fun instruct(plan: AllocationPlanView, line: AllocationLineView): AllocationLineView {
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

    // ----------------------------------------------------------------------------------------------------------
    // Registry confirmations and failures
    // ----------------------------------------------------------------------------------------------------------

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
        save(aggregate.lineUpdated(line.copy(status = LineStatus.FAILED, failureReason = event.reason)))
        finishIfDone(aggregate)
    }

    private fun applyConfirmation(aggregate: AllocationPlan, lineId: UUID, instruction: RegistryInstructionView): AllocationPlanView {
        val line = AllocationPlan.lineBy(aggregate.state, lineId) ?: return aggregate.state
        val confirmedLine = line.copy(status = LineStatus.CONFIRMED, externalRef = instruction.externalRef, instructionId = instruction.id)
        val plan = aggregate.lineUpdated(confirmedLine)
        save(plan)
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

    // ----------------------------------------------------------------------------------------------------------
    // Reject, cancel, expire
    // ----------------------------------------------------------------------------------------------------------

    override fun reject(planId: UUID, reason: String): AllocationPlanView {
        authorise(requirePlan(planId).holderEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        val rejected = AllocationPlan(locked(planId)).reject(actorProvider.current().userId, reason)
        save(rejected)
        plans.deleteSoftReservations(planId)
        events.publishEvent(AllocationRejected(rejected, reason))
        return rejected
    }

    override fun cancel(planId: UUID, reason: String): AllocationPlanView {
        authorise(requirePlan(planId).holderEntityId, Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        val current = locked(planId)
        val cancelled = AllocationPlan(current).cancel(actorProvider.current().userId, reason)
        save(cancelled)
        plans.deleteSoftReservations(planId)
        current.lines.filter { it.status == LineStatus.RESERVED || it.status == LineStatus.INSTRUCTED || it.status == LineStatus.FAILED }.forEach { line ->
            line.instructionId?.let { id -> runCatching { instructions.cancel(id, "plan cancelled: $reason") } }
            releaseQuietly(line, planId)
        }
        return cancelled
    }

    override fun expireStalePlans(now: Instant): Int {
        var count = 0
        plans.expiredProposals(now).forEach { id ->
            val plan = plans.lock(id) ?: return@forEach
            if (plan.status != PlanStatus.PROPOSED) return@forEach
            save(AllocationPlan(plan).expire(now))
            plans.deleteSoftReservations(id)
            count++
        }
        return count
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun plan(id: UUID): AllocationPlanView? = plans.find(id)

    @Transactional(readOnly = true)
    override fun requirePlan(id: UUID): AllocationPlanView = plans.find(id) ?: throw NotFoundException("AllocationPlan", id)

    @Transactional(readOnly = true)
    override fun plans(holderEntityId: UUID, activeOnly: Boolean): List<AllocationPlanView> = plans.byEntity(holderEntityId, activeOnly)

    @Transactional(readOnly = true)
    override fun coverage(holderEntityId: UUID, obligationIds: Set<UUID>?): CoverageReport {
        val now = clock.instant()
        val open = obligations.open(holderEntityId).filter { obligationIds == null || it.id in obligationIds }
        val snapshot = lotQuery.snapshot(holderEntityId)
        val built = problemBuilder.build(snapshot, open, plans.activeSoftReservations(holderEntityId, now), LocalDate.now(clock))
        return CoverageAnalyser(built.scoring::bestBucketCode).analyse(built.problem, holderEntityId, snapshot.seq, now)
    }

    // ----------------------------------------------------------------------------------------------------------

    private fun releaseQuietly(line: AllocationLineView, planId: UUID) {
        val lotId = line.reservedLotId ?: return
        runCatching {
            val lot = lotQuery.requireLot(lotId)
            if (lot.status == LotStatus.RESERVED && lot.reservationRef == planId) lotCommand.release(lotId, planId)
        }.onFailure { log.warn("Could not release lot {} of plan {}: {}", lotId, planId, it.message) }
    }

    private fun locked(id: UUID): AllocationPlanView = plans.lock(id) ?: throw NotFoundException("AllocationPlan", id)

    private fun save(plan: AllocationPlanView) {
        if (!plans.update(plan)) throw StaleVersionException("AllocationPlan", plan.id, plan.version - 1, -1)
    }
}
