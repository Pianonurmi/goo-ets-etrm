package com.pianonurmi.etrm.inventory.adapter.jooq

import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.AllowanceHolding.ALLOWANCE_HOLDING
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.AllowanceMovement.ALLOWANCE_MOVEMENT
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.records.AllowanceHoldingRecord
import com.pianonurmi.etrm.inventory.adapter.jooq.generated.tables.records.AllowanceMovementRecord
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.domain.AllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.domain.NewAllowanceMovement
import com.pianonurmi.etrm.platform.ids.Ids
import org.jooq.DSLContext
import org.jooq.DatePart
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JooqAllowanceHoldingRepository(private val dsl: DSLContext) : AllowanceHoldingRepository {
    override fun find(registryAccountId: UUID, productCode: String): AllowanceHoldingView? = dsl.selectFrom(ALLOWANCE_HOLDING)
        .where(ALLOWANCE_HOLDING.REGISTRY_ACCOUNT_ID.eq(registryAccountId), ALLOWANCE_HOLDING.PRODUCT_CODE.eq(productCode))
        .fetchOne()?.let(::toView)

    override fun findById(id: UUID): AllowanceHoldingView? = dsl.selectFrom(
        ALLOWANCE_HOLDING,
    ).where(ALLOWANCE_HOLDING.ID.eq(id)).forUpdate().fetchOne()?.let(::toView)

    override fun lockOrCreate(holderEntityId: UUID, registryAccountId: UUID, productCode: String): AllowanceHoldingView {
        dsl.insertInto(ALLOWANCE_HOLDING)
            .set(ALLOWANCE_HOLDING.ID, Ids.newId())
            .set(ALLOWANCE_HOLDING.HOLDER_ENTITY_ID, holderEntityId)
            .set(ALLOWANCE_HOLDING.REGISTRY_ACCOUNT_ID, registryAccountId)
            .set(ALLOWANCE_HOLDING.PRODUCT_CODE, productCode)
            .onConflictDoNothing()
            .execute()
        return dsl.selectFrom(ALLOWANCE_HOLDING)
            .where(ALLOWANCE_HOLDING.REGISTRY_ACCOUNT_ID.eq(registryAccountId), ALLOWANCE_HOLDING.PRODUCT_CODE.eq(productCode))
            .forUpdate()
            .fetchOne()!!
            .let(::toView)
    }

    override fun update(holding: AllowanceHoldingView): Boolean = dsl.update(ALLOWANCE_HOLDING)
        .set(ALLOWANCE_HOLDING.QUANTITY, holding.quantity)
        .set(ALLOWANCE_HOLDING.RESERVED_QUANTITY, holding.reservedQuantity)
        .set(ALLOWANCE_HOLDING.VERSION, holding.version)
        .set(ALLOWANCE_HOLDING.UPDATED_AT, Instant.now())
        .where(ALLOWANCE_HOLDING.ID.eq(holding.id), ALLOWANCE_HOLDING.VERSION.eq(holding.version - 1))
        .execute() == 1

    override fun byEntity(holderEntityId: UUID): List<AllowanceHoldingView> = dsl.selectFrom(ALLOWANCE_HOLDING)
        .where(ALLOWANCE_HOLDING.HOLDER_ENTITY_ID.eq(holderEntityId))
        .orderBy(ALLOWANCE_HOLDING.PRODUCT_CODE, ALLOWANCE_HOLDING.REGISTRY_ACCOUNT_ID)
        .fetch(::toView)

    override fun appendMovement(movement: NewAllowanceMovement): AllowanceMovementView = dsl.insertInto(ALLOWANCE_MOVEMENT)
        .set(ALLOWANCE_MOVEMENT.HOLDING_ID, movement.holdingId)
        .set(ALLOWANCE_MOVEMENT.MOVEMENT_TYPE, movement.type)
        .set(ALLOWANCE_MOVEMENT.QUANTITY, movement.signedQuantity)
        .set(ALLOWANCE_MOVEMENT.EFFECTIVE_DATE, movement.effectiveDate)
        .set(ALLOWANCE_MOVEMENT.REFERENCE, movement.reference)
        .set(ALLOWANCE_MOVEMENT.COST_PER_UNIT, movement.costPerUnit)
        .set(ALLOWANCE_MOVEMENT.COST_CURRENCY, movement.costCurrency)
        .set(ALLOWANCE_MOVEMENT.COMPLIANCE_YEAR, movement.complianceYear)
        .set(ALLOWANCE_MOVEMENT.ACTOR, movement.actor)
        .set(ALLOWANCE_MOVEMENT.CORRELATION_ID, movement.correlationId)
        .returning()
        .fetchOne()!!
        .let(::toMovement)

    override fun movements(holdingId: UUID): List<AllowanceMovementView> =
        dsl.selectFrom(ALLOWANCE_MOVEMENT).where(ALLOWANCE_MOVEMENT.HOLDING_ID.eq(holdingId)).orderBy(ALLOWANCE_MOVEMENT.SEQ).fetch(::toMovement)

    override fun movementTotal(holderEntityId: UUID, type: AllowanceMovementType, complianceYear: Int, productCodes: Set<String>?): Long {
        val year = DSL.coalesce(ALLOWANCE_MOVEMENT.COMPLIANCE_YEAR, DSL.extract(ALLOWANCE_MOVEMENT.EFFECTIVE_DATE, DatePart.YEAR))
        return dsl.select(DSL.sum(DSL.abs(ALLOWANCE_MOVEMENT.QUANTITY)))
            .from(ALLOWANCE_MOVEMENT)
            .join(ALLOWANCE_HOLDING).on(ALLOWANCE_HOLDING.ID.eq(ALLOWANCE_MOVEMENT.HOLDING_ID))
            .where(ALLOWANCE_HOLDING.HOLDER_ENTITY_ID.eq(holderEntityId), ALLOWANCE_MOVEMENT.MOVEMENT_TYPE.eq(type.name), year.eq(complianceYear))
            .and(productCodes?.let { ALLOWANCE_HOLDING.PRODUCT_CODE.`in`(it) } ?: DSL.noCondition())
            .fetchOne(0, Long::class.java) ?: 0L
    }

    private fun toView(r: AllowanceHoldingRecord) = AllowanceHoldingView(
        r.id,
        r.holderEntityId,
        r.registryAccountId,
        r.productCode,
        r.quantity,
        r.reservedQuantity,
        r.version,
    )

    private fun toMovement(r: AllowanceMovementRecord) = AllowanceMovementView(
        r.seq,
        r.holdingId,
        AllowanceMovementType.valueOf(r.movementType),
        r.quantity,
        r.effectiveDate,
        r.reference,
        r.costPerUnit,
        r.costCurrency,
        r.complianceYear,
        r.actor,
        r.recordedAt,
    )
}
