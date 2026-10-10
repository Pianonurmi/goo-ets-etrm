package com.pianonurmi.etrm.product.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.product.api.BucketMatch
import com.pianonurmi.etrm.product.api.BucketMatchKind
import com.pianonurmi.etrm.product.api.CreateProductBucket
import com.pianonurmi.etrm.product.api.ProductBucketCommand
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductBucketView
import com.pianonurmi.etrm.product.api.UpdateProductBucket
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.domain.CriteriaImplication
import com.pianonurmi.etrm.product.domain.ProductBucket
import com.pianonurmi.etrm.product.domain.ProductBucketRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class ProductBucketService(
    private val repository: ProductBucketRepository,
    private val criteria: CriteriaService,
) : ProductBucketQuery, ProductBucketCommand {
    override fun buckets(activeOnly: Boolean): List<ProductBucketView> = repository.all(activeOnly).map { it.toView() }

    override fun bucket(code: String): ProductBucketView? = repository.byCode(code.uppercase())?.toView()

    override fun requireBucket(code: String): ProductBucketView = bucket(code) ?: throw NotFoundException("ProductBucket", code)

    override fun bucketById(id: UUID): ProductBucketView? = repository.byId(id)?.toView()

    override fun bind(context: BindContext, activeOnly: Boolean): BoundBuckets = BoundBuckets(
        buckets = repository.all(activeOnly).map { bucket -> bucket.toView() to criteria.bind(bucket.criterion, context) },
        evaluate = criteria::evaluate,
    )

    override fun bucketFor(criteriaId: UUID?, criterion: Criterion, context: BindContext): BucketMatch? {
        val active = repository.all(true).map { it.toView() }
        active.firstOrNull { criteriaId != null && it.criteriaId == criteriaId }?.let { return BucketMatch(it, BucketMatchKind.EXACT) }
        val bound = criteria.bind(criterion, context)
        return active.filter { CriteriaImplication.implies(bound, criteria.bind(it.criterion, context)) }
            .sortedWith(compareByDescending<ProductBucketView> { it.specificity }.thenBy { it.sortOrder }.thenBy { it.code })
            .firstOrNull()
            ?.let { BucketMatch(it, BucketMatchKind.IMPLIED) }
    }

    @Transactional
    override fun create(command: CreateProductBucket): ProductBucketView {
        val code = command.code.trim().uppercase()
        if (repository.byCode(code) != null) throw ConflictException("Product bucket $code already exists")
        val ref = criteria.store(command.criterion, "bucket:$code")
        val bucket = ProductBucket(
            id = Ids.newId(),
            code = code,
            name = command.name.trim(),
            energyCarrier = command.energyCarrier,
            criteriaId = ref.id,
            criterion = command.criterion,
            specificity = ref.specificity,
            markCurveRef = command.markCurveRef,
            sortOrder = command.sortOrder,
            active = true,
            version = 1,
        )
        repository.insert(bucket)
        return bucket.toView()
    }

    @Transactional
    override fun update(code: String, command: UpdateProductBucket): ProductBucketView {
        val current = repository.byCode(code.uppercase()) ?: throw NotFoundException("ProductBucket", code)
        if (current.version != command.expectedVersion) throw StaleVersionException("ProductBucket", code, command.expectedVersion, current.version)
        val updated = current.copy(
            name = command.name ?: current.name,
            markCurveRef = command.markCurveRef ?: current.markCurveRef,
            sortOrder = command.sortOrder ?: current.sortOrder,
            active = command.active ?: current.active,
            version = current.version + 1,
        )
        if (!repository.update(updated, command.expectedVersion)) {
            throw StaleVersionException("ProductBucket", code, command.expectedVersion, current.version)
        }
        return updated.toView()
    }

    private fun ProductBucket.toView() = ProductBucketView(
        id,
        code,
        name,
        energyCarrier,
        criteriaId,
        criterion,
        criteria.describe(criterion),
        specificity,
        markCurveRef,
        sortOrder,
        active,
        version,
    )
}
