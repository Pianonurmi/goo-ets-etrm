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

enum class BucketMatchKind {
    /** The criteria are the bucket's own. */
    EXACT,

    /** Every certificate the criteria accept is in the bucket (proved, not guessed). */
    IMPLIED,
}

data class BucketMatch(val bucket: ProductBucketView, val kind: BucketMatchKind)

interface ProductBucketQuery {
    fun buckets(activeOnly: Boolean = true): List<ProductBucketView>

    fun bucket(code: String): ProductBucketView?

    fun requireBucket(code: String): ProductBucketView

    fun bucketById(id: UUID): ProductBucketView?

    fun bind(context: BindContext, activeOnly: Boolean = true): BoundBuckets

    /**
     * The bucket a delivery criterion belongs to: the bucket with the same stored criteria, else the most specific
     * active bucket whose criteria provably contain it, bound for [context]. Null when no bucket contains it.
     */
    fun bucketFor(criteriaId: UUID?, criterion: Criterion, context: BindContext): BucketMatch?

    /** The active buckets ordered by proven containment, bound for [context] (ADR 0037). */
    fun hierarchy(context: BindContext): BucketHierarchy
}

/**
 * A bucket in the product hierarchy (ADR 0037). [parents] are the smallest buckets that provably contain it (the
 * Hasse diagram of containment), so a bucket can have more than one: Nordic wind is under Nordic renewable and under
 * European wind. [aliases]: buckets proven equivalent to it, shown as one node. [depth]: the longest way up to a root.
 */
data class BucketNode(
    val code: String,
    val name: String,
    val aliases: List<String>,
    val parents: List<String>,
    val depth: Int,
    val sortOrder: Int,
)

/** Every active bucket once, parents before children. Containment is proven, not guessed: unproven means unrelated. */
data class BucketHierarchy(val nodes: List<BucketNode>) {
    val roots: List<String> get() = nodes.filter { it.parents.isEmpty() }.map { it.code }

    fun children(code: String): List<String> = nodes.filter { code in it.parents }.map { it.code }

    /** [code] and everything under it. */
    fun descendants(code: String): Set<String> {
        val seen = linkedSetOf(code)
        var frontier = listOf(code)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { children(it) }.filter { seen.add(it) }
        }
        return seen
    }
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
