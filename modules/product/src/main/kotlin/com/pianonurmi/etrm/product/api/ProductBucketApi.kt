package com.pianonurmi.etrm.product.api

import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import java.math.BigDecimal
import java.util.UUID

data class ProductBucketView(
    val id: UUID,
    val code: String,
    val name: String,
    val energyCarrier: EnergyCarrier,
    val criteriaId: UUID,
    val criterion: Criterion,
    val summary: String,
    val specificity: BigDecimal,
    val markCurveRef: String?,
    val sortOrder: Int,
    val active: Boolean,
    val version: Int,
)

/** Bucket criteria bound for a reference date, reusable across many subjects (e.g. an allocation run). */
class BoundBuckets(val buckets: List<Pair<ProductBucketView, BoundCriterion>>, private val evaluate: (BoundCriterion, CriteriaSubject) -> Boolean) {
    fun matching(subject: CriteriaSubject): List<ProductBucketView> = buckets.filter { (_, bound) -> evaluate(bound, subject) }.map { it.first }

    /** Most specific matching bucket (ties broken by sort order then code), or null. */
    fun best(subject: CriteriaSubject): ProductBucketView? = matching(subject)
        .sortedWith(compareByDescending<ProductBucketView> { it.specificity }.thenBy { it.sortOrder }.thenBy { it.code })
        .firstOrNull()
}

interface ProductBucketQuery {
    fun buckets(activeOnly: Boolean = true): List<ProductBucketView>

    fun bucket(code: String): ProductBucketView?

    fun requireBucket(code: String): ProductBucketView

    fun bucketById(id: UUID): ProductBucketView?

    fun bind(context: BindContext, activeOnly: Boolean = true): BoundBuckets
}

data class CreateProductBucket(
    val code: String,
    val name: String,
    val energyCarrier: EnergyCarrier = EnergyCarrier.ELECTRICITY,
    val criterion: Criterion,
    val markCurveRef: String? = null,
    val sortOrder: Int = 100,
)

data class UpdateProductBucket(
    val name: String? = null,
    val markCurveRef: String? = null,
    val sortOrder: Int? = null,
    val active: Boolean? = null,
    val expectedVersion: Int,
)

interface ProductBucketCommand {
    fun create(command: CreateProductBucket): ProductBucketView

    fun update(code: String, command: UpdateProductBucket): ProductBucketView
}
