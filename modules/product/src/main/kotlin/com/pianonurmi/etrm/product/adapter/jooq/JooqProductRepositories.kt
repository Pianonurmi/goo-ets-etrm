package com.pianonurmi.etrm.product.adapter.jooq

import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.product.adapter.jooq.generated.tables.AllowanceProduct.ALLOWANCE_PRODUCT
import com.pianonurmi.etrm.product.adapter.jooq.generated.tables.Criteria.CRITERIA
import com.pianonurmi.etrm.product.adapter.jooq.generated.tables.FuturesContract.FUTURES_CONTRACT
import com.pianonurmi.etrm.product.adapter.jooq.generated.tables.ProductBucket.PRODUCT_BUCKET
import com.pianonurmi.etrm.product.adapter.jooq.generated.tables.SpecificityWeight.SPECIFICITY_WEIGHT
import com.pianonurmi.etrm.product.adapter.jooq.generated.tables.records.CriteriaRecord
import com.pianonurmi.etrm.product.adapter.jooq.generated.tables.records.FuturesContractRecord
import com.pianonurmi.etrm.product.api.AllowanceProductView
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.FuturesContractView
import com.pianonurmi.etrm.product.domain.AllowanceProductRepository
import com.pianonurmi.etrm.product.domain.CriteriaCodec
import com.pianonurmi.etrm.product.domain.CriteriaRepository
import com.pianonurmi.etrm.product.domain.FuturesContractRepository
import com.pianonurmi.etrm.product.domain.ProductBucket
import com.pianonurmi.etrm.product.domain.ProductBucketRepository
import com.pianonurmi.etrm.product.domain.SpecificityWeights
import com.pianonurmi.etrm.product.domain.StoredCriteria
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

@Repository
class JooqCriteriaRepository(private val dsl: DSLContext) : CriteriaRepository {
    override fun findByHash(hash: String): StoredCriteria? = dsl.selectFrom(CRITERIA).where(CRITERIA.BODY_HASH.eq(hash)).fetchOne()?.let(::toDomain)

    override fun findById(id: UUID): StoredCriteria? = dsl.selectFrom(CRITERIA).where(CRITERIA.ID.eq(id)).fetchOne()?.let(::toDomain)

    override fun insert(stored: StoredCriteria) {
        dsl.insertInto(CRITERIA)
            .set(CRITERIA.ID, stored.id)
            .set(CRITERIA.SCHEMA_VERSION, stored.schemaVersion)
            .set(CRITERIA.BODY, JSONB.jsonb(stored.json))
            .set(CRITERIA.BODY_HASH, stored.hash)
            .set(CRITERIA.STATIC_SPECIFICITY, stored.specificity)
            .set(CRITERIA.SUMMARY, stored.summary)
            .set(CRITERIA.CREATED_BY, stored.createdBy)
            .onConflict(CRITERIA.BODY_HASH).doNothing()
            .execute()
    }

    override fun specificityWeights(): SpecificityWeights = SpecificityWeights(
        dsl.selectFrom(SPECIFICITY_WEIGHT).fetch().associate { it.predicate to it.weight },
    )

    private fun toDomain(r: CriteriaRecord) = StoredCriteria(r.id, r.schemaVersion, r.body.data(), r.bodyHash, r.staticSpecificity, r.summary, r.createdBy)
}

@Repository
class JooqProductBucketRepository(private val dsl: DSLContext) : ProductBucketRepository {
    private fun select() = dsl.select(PRODUCT_BUCKET.asterisk(), CRITERIA.BODY, CRITERIA.STATIC_SPECIFICITY)
        .from(PRODUCT_BUCKET).join(CRITERIA).on(CRITERIA.ID.eq(PRODUCT_BUCKET.CRITERIA_ID))

    override fun all(activeOnly: Boolean): List<ProductBucket> = select()
        .where(if (activeOnly) PRODUCT_BUCKET.ACTIVE.isTrue else DSL.noCondition())
        .orderBy(PRODUCT_BUCKET.SORT_ORDER, PRODUCT_BUCKET.CODE)
        .fetch(::toDomain)

    override fun byCode(code: String): ProductBucket? = select().where(PRODUCT_BUCKET.CODE.eq(code)).fetchOne()?.let(::toDomain)

    override fun byId(id: UUID): ProductBucket? = select().where(PRODUCT_BUCKET.ID.eq(id)).fetchOne()?.let(::toDomain)

    override fun insert(bucket: ProductBucket) {
        dsl.insertInto(PRODUCT_BUCKET)
            .set(PRODUCT_BUCKET.ID, bucket.id)
            .set(PRODUCT_BUCKET.CODE, bucket.code)
            .set(PRODUCT_BUCKET.NAME, bucket.name)
            .set(PRODUCT_BUCKET.ENERGY_CARRIER, bucket.energyCarrier.name)
            .set(PRODUCT_BUCKET.CRITERIA_ID, bucket.criteriaId)
            .set(PRODUCT_BUCKET.MARK_CURVE_REF, bucket.markCurveRef)
            .set(PRODUCT_BUCKET.SORT_ORDER, bucket.sortOrder)
            .set(PRODUCT_BUCKET.ACTIVE, bucket.active)
            .set(PRODUCT_BUCKET.VERSION, bucket.version)
            .execute()
    }

    override fun update(bucket: ProductBucket, expectedVersion: Int): Boolean = dsl.update(PRODUCT_BUCKET)
        .set(PRODUCT_BUCKET.NAME, bucket.name)
        .set(PRODUCT_BUCKET.MARK_CURVE_REF, bucket.markCurveRef)
        .set(PRODUCT_BUCKET.SORT_ORDER, bucket.sortOrder)
        .set(PRODUCT_BUCKET.ACTIVE, bucket.active)
        .set(PRODUCT_BUCKET.VERSION, bucket.version)
        .set(PRODUCT_BUCKET.UPDATED_AT, DSL.currentInstant())
        .where(PRODUCT_BUCKET.ID.eq(bucket.id), PRODUCT_BUCKET.VERSION.eq(expectedVersion))
        .execute() == 1

    private fun toDomain(r: Record): ProductBucket {
        val b = r.into(PRODUCT_BUCKET)
        return ProductBucket(
            id = b.id,
            code = b.code,
            name = b.name,
            energyCarrier = EnergyCarrier.valueOf(b.energyCarrier),
            criteriaId = b.criteriaId,
            criterion = CriteriaCodec.parse(r.get(CRITERIA.BODY).data()),
            specificity = r.get(CRITERIA.STATIC_SPECIFICITY),
            markCurveRef = b.markCurveRef,
            sortOrder = b.sortOrder,
            active = b.active,
            version = b.version,
        )
    }
}

@Repository
class JooqAllowanceProductRepository(private val dsl: DSLContext) : AllowanceProductRepository {
    override fun all(activeOnly: Boolean): List<AllowanceProductView> = dsl.selectFrom(ALLOWANCE_PRODUCT)
        .where(if (activeOnly) ALLOWANCE_PRODUCT.ACTIVE.isTrue else DSL.noCondition())
        .orderBy(ALLOWANCE_PRODUCT.CODE)
        .fetch { AllowanceProductView(it.code, it.name, it.jurisdiction, it.phase, QuantityUnit.valueOf(it.unit), it.registryCode, it.active) }

    override fun byCode(code: String): AllowanceProductView? = dsl.selectFrom(ALLOWANCE_PRODUCT).where(ALLOWANCE_PRODUCT.CODE.eq(code)).fetchOne()
        ?.let { AllowanceProductView(it.code, it.name, it.jurisdiction, it.phase, QuantityUnit.valueOf(it.unit), it.registryCode, it.active) }
}

@Repository
class JooqFuturesContractRepository(private val dsl: DSLContext) : FuturesContractRepository {
    override fun all(allowanceProductCode: String?, activeOnly: Boolean): List<FuturesContractView> = dsl.selectFrom(FUTURES_CONTRACT)
        .where(allowanceProductCode?.let { FUTURES_CONTRACT.ALLOWANCE_PRODUCT_CODE.eq(it) } ?: DSL.noCondition())
        .and(if (activeOnly) FUTURES_CONTRACT.ACTIVE.isTrue else DSL.noCondition())
        .orderBy(FUTURES_CONTRACT.DELIVERY_DATE, FUTURES_CONTRACT.CODE)
        .fetch(::toView)

    override fun byCode(code: String): FuturesContractView? = dsl.selectFrom(FUTURES_CONTRACT).where(FUTURES_CONTRACT.CODE.eq(code)).fetchOne()?.let(::toView)

    override fun insert(contract: FuturesContractView) {
        dsl.insertInto(FUTURES_CONTRACT)
            .set(FUTURES_CONTRACT.CODE, contract.code)
            .set(FUTURES_CONTRACT.ALLOWANCE_PRODUCT_CODE, contract.allowanceProductCode)
            .set(FUTURES_CONTRACT.VENUE_MIC, contract.venueMic)
            .set(FUTURES_CONTRACT.DELIVERY_MONTH, contract.deliveryMonth.toString())
            .set(FUTURES_CONTRACT.DELIVERY_DATE, contract.deliveryDate)
            .set(FUTURES_CONTRACT.LAST_TRADING_DAY, contract.lastTradingDay)
            .set(FUTURES_CONTRACT.CONTRACT_SIZE, contract.contractSize)
            .set(FUTURES_CONTRACT.CURRENCY, contract.currency)
            .set(FUTURES_CONTRACT.ACTIVE, contract.active)
            .execute()
    }

    override fun setActive(code: String, active: Boolean) {
        dsl.update(FUTURES_CONTRACT).set(FUTURES_CONTRACT.ACTIVE, active).where(FUTURES_CONTRACT.CODE.eq(code)).execute()
    }

    private fun toView(r: FuturesContractRecord) = FuturesContractView(
        r.code,
        r.allowanceProductCode,
        r.venueMic,
        YearMonth.parse(r.deliveryMonth),
        r.deliveryDate,
        r.lastTradingDay,
        r.contractSize,
        r.currency,
        r.active,
    )
}
