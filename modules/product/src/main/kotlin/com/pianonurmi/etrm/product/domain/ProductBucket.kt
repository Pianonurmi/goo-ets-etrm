package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.criteria.Criterion
import java.math.BigDecimal
import java.util.UUID

/** A standardised GoO product used as the aggregation dimension for positions, marks and scoring. */
data class ProductBucket(
    val id: UUID,
    val code: String,
    val name: String,
    val energyCarrier: EnergyCarrier,
    val criteriaId: UUID,
    val criterion: Criterion,
    val specificity: BigDecimal,
    val markCurveRef: String?,
    val sortOrder: Int,
    val active: Boolean,
    val version: Int,
)

interface ProductBucketRepository {
    fun all(activeOnly: Boolean): List<ProductBucket>

    fun byCode(code: String): ProductBucket?

    fun byId(id: UUID): ProductBucket?

    fun insert(bucket: ProductBucket)

    fun update(bucket: ProductBucket, expectedVersion: Int): Boolean
}

interface CriteriaRepository {
    fun findByHash(hash: String): StoredCriteria?

    fun findById(id: UUID): StoredCriteria?

    fun insert(stored: StoredCriteria)

    fun specificityWeights(): SpecificityWeights
}

data class StoredCriteria(
    val id: UUID,
    val schemaVersion: Int,
    val json: String,
    val hash: String,
    val specificity: BigDecimal,
    val summary: String,
    val createdBy: String?,
)
