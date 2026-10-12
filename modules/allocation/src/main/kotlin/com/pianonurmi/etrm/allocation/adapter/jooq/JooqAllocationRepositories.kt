package com.pianonurmi.etrm.allocation.adapter.jooq

import com.pianonurmi.etrm.allocation.adapter.jooq.generated.tables.AllocationLine.ALLOCATION_LINE
import com.pianonurmi.etrm.allocation.adapter.jooq.generated.tables.AllocationPlan.ALLOCATION_PLAN
import com.pianonurmi.etrm.allocation.adapter.jooq.generated.tables.Obligation.OBLIGATION
import com.pianonurmi.etrm.allocation.adapter.jooq.generated.tables.SoftReservation.SOFT_RESERVATION
import com.pianonurmi.etrm.allocation.adapter.jooq.generated.tables.records.AllocationLineRecord
import com.pianonurmi.etrm.allocation.adapter.jooq.generated.tables.records.AllocationPlanRecord
import com.pianonurmi.etrm.allocation.adapter.jooq.generated.tables.records.ObligationRecord
import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.AllocationPlanView
import com.pianonurmi.etrm.allocation.api.CoverageReport
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationStatus
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.PlanPricing
import com.pianonurmi.etrm.allocation.api.PlanStatus
import com.pianonurmi.etrm.allocation.api.PlanValue
import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.allocation.api.SolverKind
import com.pianonurmi.etrm.allocation.domain.AllocationPlanRepository
import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.BucketMatchKind
import com.pianonurmi.etrm.product.api.criteria.Criterion
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.jooq.postgres.extensions.types.LocalDateRange as PgLocalDateRange

@Repository
class JooqObligationRepository(private val dsl: DSLContext) : ObligationRepository {
    private val constraintsType = object : TypeReference<List<AllocationConstraint>>() {}

    override fun find(id: UUID): ObligationView? = dsl.selectFrom(OBLIGATION).where(OBLIGATION.ID.eq(id)).fetchOne()?.let(::toView)

    override fun lock(id: UUID): ObligationView? = dsl.selectFrom(OBLIGATION).where(OBLIGATION.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun bySource(sourceRef: UUID): ObligationView? = dsl.selectFrom(OBLIGATION).where(OBLIGATION.SOURCE_REF.eq(sourceRef)).fetchOne()?.let(::toView)

    override fun open(holderEntityId: UUID): List<ObligationView> = dsl.selectFrom(OBLIGATION)
        .where(OBLIGATION.HOLDER_ENTITY_ID.eq(holderEntityId), OBLIGATION.STATUS.eq(ObligationStatus.OPEN.name))
        .orderBy(OBLIGATION.DELIVERY_DATE, OBLIGATION.ID)
        .fetch(::toView)

    override fun openStamp(holderEntityId: UUID): String = dsl.select(
        DSL.count(),
        DSL.coalesce(DSL.sum(OBLIGATION.VERSION), DSL.inline(0)),
        DSL.coalesce(DSL.sum(OBLIGATION.FULFILLED_MWH), DSL.inline(0)),
        // PostgreSQL has no max(uuid); UUIDv7 text sorts in creation order.
        DSL.max(OBLIGATION.ID.cast(String::class.java)),
    )
        .from(OBLIGATION)
        .where(OBLIGATION.HOLDER_ENTITY_ID.eq(holderEntityId), OBLIGATION.STATUS.eq(ObligationStatus.OPEN.name))
        .fetchOne { "${it.value1()}:${it.value2()}:${it.value3()}:${it.value4()}" }!!

    override fun insert(obligation: ObligationView) {
        val o = obligation
        dsl.insertInto(OBLIGATION)
            .set(OBLIGATION.ID, o.id)
            .set(OBLIGATION.KIND, o.kind.name)
            .set(OBLIGATION.HOLDER_ENTITY_ID, o.holderEntityId)
            .set(OBLIGATION.BOOK_ID, o.bookId)
            .set(OBLIGATION.COUNTERPARTY_ID, o.counterpartyId)
            .set(OBLIGATION.TRADE_REF, o.tradeRef)
            .set(OBLIGATION.SOURCE_REF, o.sourceRef)
            .set(values(o))
            .set(OBLIGATION.VERSION, o.version)
            .execute()
    }

    override fun update(obligation: ObligationView): Boolean = update0(obligation)

    private fun update0(o: ObligationView): Boolean = dsl.update(OBLIGATION)
        .set(values(o))
        .set(OBLIGATION.VERSION, o.version)
        .set(OBLIGATION.UPDATED_AT, Instant.now())
        .where(OBLIGATION.ID.eq(o.id), OBLIGATION.VERSION.eq(o.version - 1))
        .execute() == 1

    private fun values(o: ObligationView): ObligationRecord = ObligationRecord().apply {
        criteriaJson = JSONB.jsonb(Json.mapper.writeValueAsString(o.criterion))
        criteriaSummary = o.criteriaSummary
        quantityMwh = o.quantityMwh
        fulfilledMwh = o.fulfilledMwh
        deliveryWindow = PgLocalDateRange.localDateRange(o.deliveryWindow.start, o.deliveryWindow.endExclusive)
        deliveryDate = o.deliveryDate
        targetRegistryCode = o.targetRegistryCode
        targetAccountRef = o.targetAccountRef
        cancellation = o.cancellation?.let { JSONB.jsonb(Json.mapper.writeValueAsString(it)) }
        priorityClass = o.priorityClass
        constraints = JSONB.jsonb(Json.mapper.writerFor(constraintsType).writeValueAsString(o.constraints))
        status = o.status.name
        bucketCode = o.bucketCode
        bucketMatch = o.bucketMatch?.name
        vintageYear = o.vintageYear
    }

    private fun toView(r: ObligationRecord): ObligationView {
        val window = r.deliveryWindow
        return ObligationView(
            id = r.id,
            kind = ObligationKind.valueOf(r.kind),
            holderEntityId = r.holderEntityId,
            bookId = r.bookId,
            counterpartyId = r.counterpartyId,
            tradeRef = r.tradeRef,
            sourceRef = r.sourceRef,
            criterion = Json.mapper.readValue(r.criteriaJson.data(), Criterion::class.java),
            criteriaSummary = r.criteriaSummary,
            quantityMwh = r.quantityMwh,
            fulfilledMwh = r.fulfilledMwh,
            deliveryWindow = LocalDateRange(window.lower()!!, window.upper()!!),
            deliveryDate = r.deliveryDate,
            targetRegistryCode = r.targetRegistryCode,
            targetAccountRef = r.targetAccountRef,
            cancellation = r.cancellation?.let { Json.mapper.readValue(it.data(), CancellationDetails::class.java) },
            priorityClass = r.priorityClass,
            constraints = Json.mapper.readValue(r.constraints.data(), constraintsType),
            status = ObligationStatus.valueOf(r.status),
            version = r.version,
            bucketCode = r.bucketCode,
            bucketMatch = r.bucketMatch?.let { BucketMatchKind.valueOf(it) },
            vintageYear = r.vintageYear,
        )
    }
}

@Repository
class JooqAllocationPlanRepository(private val dsl: DSLContext) : AllocationPlanRepository {
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    override fun find(id: UUID): AllocationPlanView? = dsl.selectFrom(ALLOCATION_PLAN).where(ALLOCATION_PLAN.ID.eq(id)).fetchOne()?.let(::toView)

    override fun lock(id: UUID): AllocationPlanView? = dsl.selectFrom(ALLOCATION_PLAN).where(ALLOCATION_PLAN.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun byKey(idempotencyKey: String): AllocationPlanView? =
        dsl.selectFrom(ALLOCATION_PLAN).where(ALLOCATION_PLAN.IDEMPOTENCY_KEY.eq(idempotencyKey)).fetchOne()?.let(::toView)

    override fun byEntity(holderEntityId: UUID, activeOnly: Boolean): List<AllocationPlanView> = dsl.selectFrom(ALLOCATION_PLAN)
        .where(ALLOCATION_PLAN.HOLDER_ENTITY_ID.eq(holderEntityId))
        .and(if (activeOnly) ALLOCATION_PLAN.STATUS.`in`(PlanStatus.entries.filter { it.active }.map { it.name }) else DSL.noCondition())
        .orderBy(ALLOCATION_PLAN.PROPOSED_AT.desc())
        .fetch(::toView)

    override fun byInstruction(instructionId: UUID): AllocationPlanView? = dsl.select(ALLOCATION_LINE.PLAN_ID)
        .from(ALLOCATION_LINE)
        .where(ALLOCATION_LINE.INSTRUCTION_ID.eq(instructionId))
        .fetchOne(ALLOCATION_LINE.PLAN_ID)
        ?.let { find(it) }

    override fun insert(plan: AllocationPlanView) {
        dsl.insertInto(ALLOCATION_PLAN)
            .set(ALLOCATION_PLAN.ID, plan.id)
            .set(ALLOCATION_PLAN.IDEMPOTENCY_KEY, plan.idempotencyKey)
            .set(ALLOCATION_PLAN.HOLDER_ENTITY_ID, plan.holderEntityId)
            .set(ALLOCATION_PLAN.SNAPSHOT_SEQ, plan.snapshotSeq)
            .set(ALLOCATION_PLAN.SOLVER, plan.solver.name)
            .set(ALLOCATION_PLAN.PROPOSED_BY, plan.proposedBy)
            .set(ALLOCATION_PLAN.PROPOSED_AT, plan.proposedAt)
            .set(ALLOCATION_PLAN.RESERVATION_EXPIRES_AT, plan.reservationExpiresAt)
            .set(ALLOCATION_PLAN.OBJECTIVE, JSONB.jsonb(Json.mapper.writeValueAsString(plan.objective)))
            .set(ALLOCATION_PLAN.COVERAGE_REPORT, JSONB.jsonb(Json.mapper.writeValueAsString(plan.coverage)))
            .set(ALLOCATION_PLAN.VALUE_SUMMARY, plan.value?.let { JSONB.jsonb(Json.mapper.writeValueAsString(it)) })
            .set(ALLOCATION_PLAN.PRICING, plan.pricing?.let { JSONB.jsonb(Json.mapper.writeValueAsString(it)) })
            .set(headerValues(plan))
            .set(ALLOCATION_PLAN.VERSION, plan.version)
            .execute()
        writeLines(plan)
    }

    override fun update(plan: AllocationPlanView): Boolean {
        val updated = dsl.update(ALLOCATION_PLAN)
            .set(headerValues(plan))
            .set(ALLOCATION_PLAN.VERSION, plan.version)
            .set(ALLOCATION_PLAN.UPDATED_AT, Instant.now())
            .where(ALLOCATION_PLAN.ID.eq(plan.id), ALLOCATION_PLAN.VERSION.eq(plan.version - 1))
            .execute() == 1
        if (updated) writeLines(plan)
        return updated
    }

    private fun headerValues(plan: AllocationPlanView): AllocationPlanRecord = AllocationPlanRecord().apply {
        status = plan.status.name
        approvedBy = plan.approvedBy
        approvedAt = plan.approvedAt
        decidedBy = plan.decidedBy
        decisionReason = plan.decisionReason
    }

    private fun writeLines(plan: AllocationPlanView) {
        plan.lines.forEach { l ->
            dsl.insertInto(ALLOCATION_LINE)
                .set(ALLOCATION_LINE.ID, l.id)
                .set(ALLOCATION_LINE.PLAN_ID, plan.id)
                .set(ALLOCATION_LINE.LINE_NO, l.lineNo)
                .set(ALLOCATION_LINE.OBLIGATION_ID, l.obligationId)
                .set(ALLOCATION_LINE.LOT_ID, l.lotId)
                .set(ALLOCATION_LINE.LOT_CLASS_KEY, l.lotClassKey)
                .set(ALLOCATION_LINE.QUANTITY_MWH, l.quantityMwh)
                .set(ALLOCATION_LINE.SCORE, l.score)
                .set(ALLOCATION_LINE.SCORE_BREAKDOWN, JSONB.jsonb(Json.mapper.writeValueAsString(l.breakdown)))
                .set(lineValues(l))
                .onConflict(ALLOCATION_LINE.ID)
                .doUpdate()
                .set(lineValues(l))
                .execute()
        }
    }

    private fun lineValues(l: AllocationLineView): AllocationLineRecord = AllocationLineRecord().apply {
        status = l.status.name
        reservedLotId = l.reservedLotId
        instructionId = l.instructionId
        externalRef = l.externalRef
        failureReason = l.failureReason
    }

    override fun expiredProposals(now: Instant): List<UUID> = dsl.select(ALLOCATION_PLAN.ID)
        .from(ALLOCATION_PLAN)
        .where(ALLOCATION_PLAN.STATUS.eq(PlanStatus.PROPOSED.name), ALLOCATION_PLAN.RESERVATION_EXPIRES_AT.le(now))
        .fetch(ALLOCATION_PLAN.ID)

    override fun activeSoftReservations(holderEntityId: UUID, now: Instant, excludingPlan: UUID?): Map<UUID, Long> = dsl
        .select(SOFT_RESERVATION.LOT_ID, DSL.sum(SOFT_RESERVATION.QUANTITY_MWH))
        .from(SOFT_RESERVATION)
        .join(ALLOCATION_PLAN).on(ALLOCATION_PLAN.ID.eq(SOFT_RESERVATION.PLAN_ID))
        .where(
            ALLOCATION_PLAN.HOLDER_ENTITY_ID.eq(holderEntityId),
            ALLOCATION_PLAN.STATUS.eq(PlanStatus.PROPOSED.name),
            SOFT_RESERVATION.EXPIRES_AT.gt(now),
            excludingPlan?.let { ALLOCATION_PLAN.ID.ne(it) } ?: DSL.noCondition(),
        )
        .groupBy(SOFT_RESERVATION.LOT_ID)
        .fetch()
        .associate { it.value1() to it.value2().toLong() }

    override fun replaceSoftReservations(planId: UUID, reservations: Map<UUID, Long>, expiresAt: Instant) {
        dsl.deleteFrom(SOFT_RESERVATION).where(SOFT_RESERVATION.PLAN_ID.eq(planId)).execute()
        reservations.forEach { (lotId, qty) ->
            dsl.insertInto(SOFT_RESERVATION)
                .set(SOFT_RESERVATION.PLAN_ID, planId)
                .set(SOFT_RESERVATION.LOT_ID, lotId)
                .set(SOFT_RESERVATION.QUANTITY_MWH, qty)
                .set(SOFT_RESERVATION.EXPIRES_AT, expiresAt)
                .execute()
        }
    }

    override fun deleteSoftReservations(planId: UUID) {
        dsl.deleteFrom(SOFT_RESERVATION).where(SOFT_RESERVATION.PLAN_ID.eq(planId)).execute()
    }

    override fun lockEntity(holderEntityId: UUID) {
        dsl.execute("SELECT pg_advisory_xact_lock(hashtext({0}))", DSL.`val`(holderEntityId.toString()))
    }

    private fun toView(r: AllocationPlanRecord): AllocationPlanView {
        val lines = dsl.selectFrom(ALLOCATION_LINE).where(ALLOCATION_LINE.PLAN_ID.eq(r.id)).orderBy(ALLOCATION_LINE.LINE_NO).fetch { l ->
            AllocationLineView(
                id = l.id,
                lineNo = l.lineNo,
                obligationId = l.obligationId,
                lotId = l.lotId,
                lotClassKey = l.lotClassKey,
                quantityMwh = l.quantityMwh,
                score = l.score,
                breakdown = Json.mapper.readValue(l.scoreBreakdown.data(), ScoreBreakdown::class.java),
                status = LineStatus.valueOf(l.status),
                reservedLotId = l.reservedLotId,
                instructionId = l.instructionId,
                externalRef = l.externalRef,
                failureReason = l.failureReason,
            )
        }
        return AllocationPlanView(
            id = r.id,
            idempotencyKey = r.idempotencyKey,
            holderEntityId = r.holderEntityId,
            snapshotSeq = r.snapshotSeq,
            solver = SolverKind.valueOf(r.solver),
            status = PlanStatus.valueOf(r.status),
            proposedBy = r.proposedBy,
            proposedAt = r.proposedAt,
            approvedBy = r.approvedBy,
            approvedAt = r.approvedAt,
            decidedBy = r.decidedBy,
            decisionReason = r.decisionReason,
            reservationExpiresAt = r.reservationExpiresAt,
            objective = Json.mapper.readValue(r.objective.data(), mapType),
            coverage = r.coverageReport?.data()?.takeIf { it != "null" }?.let { Json.mapper.readValue(it, CoverageReport::class.java) },
            lines = lines,
            version = r.version,
            value = r.valueSummary?.data()?.takeIf { it != "null" }?.let { Json.mapper.readValue(it, PlanValue::class.java) },
            pricing = r.pricing?.data()?.takeIf { it != "null" }?.let { Json.mapper.readValue(it, PlanPricing::class.java) },
        )
    }
}
