package com.pianonurmi.etrm.ledger.adapter.jooq

import com.pianonurmi.etrm.ledger.adapter.jooq.generated.Sequences.HEDGE_NUMBER_SEQ
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.HedgeInstrument.HEDGE_INSTRUMENT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.HedgeMeasurement.HEDGE_MEASUREMENT
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.HedgeRelationship.HEDGE_RELATIONSHIP
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.HedgeMeasurementRecord
import com.pianonurmi.etrm.ledger.adapter.jooq.generated.tables.records.HedgeRelationshipRecord
import com.pianonurmi.etrm.ledger.api.HedgeCheck
import com.pianonurmi.etrm.ledger.api.HedgeDirection
import com.pianonurmi.etrm.ledger.api.HedgeDiscontinuation
import com.pianonurmi.etrm.ledger.api.HedgeInstrumentView
import com.pianonurmi.etrm.ledger.api.HedgeMeasurementView
import com.pianonurmi.etrm.ledger.api.HedgeRelationshipView
import com.pianonurmi.etrm.ledger.api.HedgeStatus
import com.pianonurmi.etrm.ledger.api.HedgedItem
import com.pianonurmi.etrm.ledger.domain.HedgeRepository
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.product.api.ProductFamily
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.util.UUID

@Repository
class JooqHedgeRepository(private val dsl: DSLContext) : HedgeRepository {
    private val checksType = object : TypeReference<List<HedgeCheck>>() {}

    override fun nextNumber(): String = "H-%05d".format(dsl.nextval(HEDGE_NUMBER_SEQ))

    override fun insert(hedge: HedgeRelationshipView) {
        dsl.insertInto(HEDGE_RELATIONSHIP)
            .set(HEDGE_RELATIONSHIP.ID, hedge.id)
            .set(HEDGE_RELATIONSHIP.NUMBER, hedge.number)
            .set(HEDGE_RELATIONSHIP.LEGAL_ENTITY_ID, hedge.legalEntityId)
            .set(HEDGE_RELATIONSHIP.DIRECTION, hedge.item.direction.name)
            .set(HEDGE_RELATIONSHIP.PRODUCT_FAMILY, hedge.item.productFamily.name)
            .set(HEDGE_RELATIONSHIP.ALLOWANCE_PRODUCT_CODE, hedge.item.allowanceProductCode)
            .set(HEDGE_RELATIONSHIP.BUCKET_CODE, hedge.item.bucketCode)
            .set(HEDGE_RELATIONSHIP.PRODUCTION_YEAR, hedge.item.productionYear)
            .set(HEDGE_RELATIONSHIP.QUANTITY, hedge.item.quantity)
            .set(HEDGE_RELATIONSHIP.EXPECTED_DATE, hedge.item.expectedDate)
            .set(HEDGE_RELATIONSHIP.CURRENCY, hedge.item.currency)
            .set(HEDGE_RELATIONSHIP.OBJECTIVE, hedge.objective)
            .set(HEDGE_RELATIONSHIP.HEDGE_RATIO, hedge.hedgeRatio)
            .set(HEDGE_RELATIONSHIP.CHECKS, JSONB.jsonb(Json.mapper.writeValueAsString(hedge.checks)))
            .set(HEDGE_RELATIONSHIP.STATUS, hedge.status.name)
            .set(HEDGE_RELATIONSHIP.REQUESTED_BY, hedge.requestedBy)
            .set(HEDGE_RELATIONSHIP.REQUESTED_AT, hedge.requestedAt)
            .execute()
        hedge.instruments.forEach { i ->
            dsl.insertInto(HEDGE_INSTRUMENT)
                .set(HEDGE_INSTRUMENT.RELATIONSHIP_ID, hedge.id)
                .set(HEDGE_INSTRUMENT.TRADE_ID, i.tradeId)
                .set(HEDGE_INSTRUMENT.TRADE_NUMBER, i.tradeNumber)
                .set(HEDGE_INSTRUMENT.PROPORTION, i.proportion)
                .set(HEDGE_INSTRUMENT.QUANTITY, i.quantity)
                .execute()
        }
    }

    /** Status, decisions and what designation and discontinuation lock; the hedged item and instruments never change. */
    override fun update(hedge: HedgeRelationshipView) {
        val d = hedge.discontinuation
        dsl.update(HEDGE_RELATIONSHIP)
            .set(HEDGE_RELATIONSHIP.STATUS, hedge.status.name)
            .set(HEDGE_RELATIONSHIP.DECIDED_BY, hedge.decidedBy)
            .set(HEDGE_RELATIONSHIP.DECIDED_AT, hedge.decidedAt)
            .set(HEDGE_RELATIONSHIP.DESIGNATION_DATE, hedge.designationDate)
            .set(HEDGE_RELATIONSHIP.DESIGNATION_PRICE, hedge.designationPrice)
            .set(HEDGE_RELATIONSHIP.INSTRUMENT_BASELINE, hedge.instrumentBaseline)
            .set(HEDGE_RELATIONSHIP.DOCUMENT_ID, hedge.documentId)
            .set(HEDGE_RELATIONSHIP.DISCONTINUATION_REASON, d?.reason)
            .set(HEDGE_RELATIONSHIP.STILL_EXPECTED, d?.stillExpected)
            .set(HEDGE_RELATIONSHIP.DISCONTINUATION_BY, d?.requestedBy)
            .set(HEDGE_RELATIONSHIP.DISCONTINUATION_AT, d?.requestedAt)
            .set(HEDGE_RELATIONSHIP.DISCONTINUED_BY, d?.decidedBy)
            .set(HEDGE_RELATIONSHIP.DISCONTINUED_AT, d?.decidedAt)
            .set(HEDGE_RELATIONSHIP.FROZEN_RESERVE, d?.frozenReserve)
            .where(HEDGE_RELATIONSHIP.ID.eq(hedge.id))
            .execute()
    }

    override fun hedge(id: UUID, lock: Boolean): HedgeRelationshipView? = dsl.selectFrom(HEDGE_RELATIONSHIP)
        .where(HEDGE_RELATIONSHIP.ID.eq(id))
        .let { if (lock) it.forUpdate() else it }
        .fetchOne()?.let(::toView)

    override fun hedges(legalEntityId: UUID, statuses: Set<HedgeStatus>?): List<HedgeRelationshipView> = dsl.selectFrom(HEDGE_RELATIONSHIP)
        .where(HEDGE_RELATIONSHIP.LEGAL_ENTITY_ID.eq(legalEntityId))
        .and(statuses?.let { s -> HEDGE_RELATIONSHIP.STATUS.`in`(s.map { it.name }) } ?: DSL.noCondition())
        .orderBy(HEDGE_RELATIONSHIP.NUMBER)
        .fetch(::toView)

    override fun designatedProportions(tradeIds: Collection<UUID>, statuses: Set<HedgeStatus>): Map<UUID, BigDecimal> =
        dsl.select(HEDGE_INSTRUMENT.TRADE_ID, DSL.sum(HEDGE_INSTRUMENT.PROPORTION))
            .from(HEDGE_INSTRUMENT)
            .join(HEDGE_RELATIONSHIP).on(HEDGE_RELATIONSHIP.ID.eq(HEDGE_INSTRUMENT.RELATIONSHIP_ID))
            .where(HEDGE_INSTRUMENT.TRADE_ID.`in`(tradeIds), HEDGE_RELATIONSHIP.STATUS.`in`(statuses.map { it.name }))
            .groupBy(HEDGE_INSTRUMENT.TRADE_ID)
            .fetchMap({ it.value1() }, { it.value2() })

    override fun insertMeasurement(measurement: HedgeMeasurementView): HedgeMeasurementView {
        dsl.insertInto(HEDGE_MEASUREMENT)
            .set(HEDGE_MEASUREMENT.RELATIONSHIP_ID, measurement.relationshipId)
            .set(HEDGE_MEASUREMENT.VALUATION_RUN_ID, measurement.valuationRunId)
            .set(HEDGE_MEASUREMENT.VALUATION_DATE, measurement.valuationDate)
            .set(HEDGE_MEASUREMENT.INSTRUMENT_VALUE, measurement.instrumentValue)
            .set(HEDGE_MEASUREMENT.INSTRUMENT_CHANGE, measurement.instrumentChange)
            .set(HEDGE_MEASUREMENT.HYPOTHETICAL_VALUE, measurement.hypotheticalValue)
            .set(HEDGE_MEASUREMENT.EFFECTIVE, measurement.effective)
            .set(HEDGE_MEASUREMENT.INEFFECTIVE, measurement.ineffective)
            .set(HEDGE_MEASUREMENT.OFFSET_RATIO, measurement.offsetRatio)
            .set(HEDGE_MEASUREMENT.MEASURED_AT, measurement.measuredAt)
            .onConflictDoNothing()
            .execute()
        return measurement(measurement.relationshipId, measurement.valuationRunId)!!
    }

    override fun measurement(relationshipId: UUID, valuationRunId: UUID): HedgeMeasurementView? = dsl.selectFrom(HEDGE_MEASUREMENT)
        .where(HEDGE_MEASUREMENT.RELATIONSHIP_ID.eq(relationshipId), HEDGE_MEASUREMENT.VALUATION_RUN_ID.eq(valuationRunId))
        .fetchOne()?.let(::toMeasurement)

    override fun measurements(relationshipId: UUID): List<HedgeMeasurementView> = dsl.selectFrom(HEDGE_MEASUREMENT)
        .where(HEDGE_MEASUREMENT.RELATIONSHIP_ID.eq(relationshipId))
        .orderBy(HEDGE_MEASUREMENT.VALUATION_DATE, HEDGE_MEASUREMENT.MEASURED_AT)
        .fetch(::toMeasurement)

    private fun instruments(relationshipId: UUID): List<HedgeInstrumentView> = dsl.selectFrom(HEDGE_INSTRUMENT)
        .where(HEDGE_INSTRUMENT.RELATIONSHIP_ID.eq(relationshipId))
        .orderBy(HEDGE_INSTRUMENT.TRADE_NUMBER)
        .fetch { HedgeInstrumentView(it.tradeId, it.tradeNumber, it.proportion, it.quantity) }

    private fun toView(r: HedgeRelationshipRecord) = HedgeRelationshipView(
        id = r.id,
        number = r.number,
        legalEntityId = r.legalEntityId,
        item = HedgedItem(
            HedgeDirection.valueOf(r.direction),
            ProductFamily.valueOf(r.productFamily),
            r.allowanceProductCode,
            r.bucketCode,
            r.productionYear,
            r.quantity,
            r.expectedDate,
            r.currency,
        ),
        instruments = instruments(r.id),
        objective = r.objective,
        hedgeRatio = r.hedgeRatio,
        checks = Json.mapper.readValue(r.checks.data(), checksType),
        status = HedgeStatus.valueOf(r.status),
        requestedBy = r.requestedBy,
        requestedAt = r.requestedAt,
        decidedBy = r.decidedBy,
        decidedAt = r.decidedAt,
        designationDate = r.designationDate,
        designationPrice = r.designationPrice,
        instrumentBaseline = r.instrumentBaseline,
        documentId = r.documentId,
        discontinuation = r.discontinuationReason?.let {
            HedgeDiscontinuation(
                it,
                r.stillExpected ?: false,
                r.discontinuationBy!!,
                r.discontinuationAt!!,
                r.discontinuedBy,
                r.discontinuedAt,
                r.frozenReserve,
            )
        },
    )

    private fun toMeasurement(r: HedgeMeasurementRecord) = HedgeMeasurementView(
        r.relationshipId,
        r.valuationRunId,
        r.valuationDate,
        r.instrumentValue,
        r.instrumentChange,
        r.hypotheticalValue,
        r.effective,
        r.ineffective,
        r.offsetRatio,
        r.measuredAt,
    )
}
