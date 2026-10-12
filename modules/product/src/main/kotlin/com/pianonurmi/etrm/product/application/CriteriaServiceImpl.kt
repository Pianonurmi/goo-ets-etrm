package com.pianonurmi.etrm.product.application

import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaCatalogue
import com.pianonurmi.etrm.product.api.criteria.CriteriaColumns
import com.pianonurmi.etrm.product.api.criteria.CriteriaRef
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.domain.CriteriaBinder
import com.pianonurmi.etrm.product.domain.CriteriaCatalogueBuilder
import com.pianonurmi.etrm.product.domain.CriteriaCodec
import com.pianonurmi.etrm.product.domain.CriteriaDescriber
import com.pianonurmi.etrm.product.domain.CriteriaEvaluator
import com.pianonurmi.etrm.product.domain.CriteriaImplication
import com.pianonurmi.etrm.product.domain.CriteriaRepository
import com.pianonurmi.etrm.product.domain.JooqCriteriaCompiler
import com.pianonurmi.etrm.product.domain.Specificity
import com.pianonurmi.etrm.product.domain.StoredCriteria
import org.jooq.Condition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class CriteriaServiceImpl(
    private val repository: CriteriaRepository,
    private val snapshots: TaxonomySnapshots,
) : CriteriaService {
    private val specificity: Specificity by lazy {
        Specificity(
            weights = repository.specificityWeights(),
            taxonomyDepth = snapshots::depthOf,
            regionSize = { snapshots.regionCountries(it).size.takeIf { size -> size > 0 } },
        )
    }

    override fun parse(json: String): Criterion = CriteriaCodec.parse(json)

    override fun toJson(criterion: Criterion): String = CriteriaCodec.toJson(criterion)

    override fun bind(criterion: Criterion, context: BindContext): BoundCriterion = CriteriaBinder.bind(criterion, context)

    override fun contextFor(deliveryDate: LocalDate): BindContext = BindContext(
        deliveryDate = deliveryDate,
        regionCountries = snapshots::regionCountries,
        taxonomyPath = snapshots::pathOf,
    )

    override fun evaluate(bound: BoundCriterion, subject: CriteriaSubject): Boolean = CriteriaEvaluator.matches(bound, subject)

    override fun compile(bound: BoundCriterion, columns: CriteriaColumns): Condition = JooqCriteriaCompiler.compile(bound, columns)

    override fun implies(narrow: BoundCriterion, broad: BoundCriterion): Boolean = CriteriaImplication.implies(narrow, broad)

    override fun specificity(criterion: Criterion): BigDecimal = specificity.score(criterion)

    override fun describe(criterion: Criterion): String = CriteriaDescriber.describe(criterion)

    override fun catalogue(): CriteriaCatalogue = CriteriaCatalogueBuilder.catalogue

    @Transactional
    override fun store(criterion: Criterion, createdBy: String?): CriteriaRef {
        val hash = HashChain.next(null, CriteriaCodec.canonical(criterion))
        repository.findByHash(hash)?.let { return it.toRef() }
        val stored = StoredCriteria(
            id = Ids.newId(),
            schemaVersion = CriteriaCodec.CURRENT_VERSION,
            json = CriteriaCodec.toJson(criterion),
            hash = hash,
            specificity = specificity(criterion),
            summary = describe(criterion),
            createdBy = createdBy,
        )
        repository.insert(stored)
        return stored.toRef()
    }

    @Transactional(readOnly = true)
    override fun load(id: UUID): Criterion = CriteriaCodec.parse(repository.findById(id)?.json ?: throw NotFoundException("Criteria", id))

    @Transactional(readOnly = true)
    override fun ref(id: UUID): CriteriaRef = repository.findById(id)?.toRef() ?: throw NotFoundException("Criteria", id)

    private fun StoredCriteria.toRef() = CriteriaRef(id, hash, specificity, summary)
}
