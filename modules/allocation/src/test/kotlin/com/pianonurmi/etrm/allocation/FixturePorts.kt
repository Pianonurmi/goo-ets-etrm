package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaCatalogue
import com.pianonurmi.etrm.product.api.criteria.CriteriaColumns
import com.pianonurmi.etrm.product.api.criteria.CriteriaRef
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.domain.CriteriaBinder
import com.pianonurmi.etrm.product.domain.CriteriaEvaluator
import com.pianonurmi.etrm.product.domain.CriteriaImplication
import com.pianonurmi.etrm.registry.api.TransferRoute
import com.pianonurmi.etrm.registry.api.TransferRouteQuery
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The ports [com.pianonurmi.etrm.allocation.application.ProblemBuilder] needs, in memory: fixture taxonomy, three buckets, routes. */
object FixturePorts {
    fun buckets(asOf: LocalDate): ProductBucketQuery {
        val views = listOf(
            EngineFixtures.bucket("NORDIC_HYDRO", Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")), 3),
            EngineFixtures.bucket("EU_WIND", Criterion.all(Criterion.region("EEA"), Criterion.energySource("WIND")), 2),
            EngineFixtures.bucket("EU_RENEWABLE", Criterion.energySource("RENEWABLE"), 1),
        )
        return mockk {
            every { bind(any(), any()) } answers {
                BoundBuckets(views.map { it to CriteriaBinder.bind(it.criterion, EngineFixtures.ctx(asOf)) }, CriteriaEvaluator::matches)
            }
        }
    }

    /** Every registry reaches every other, except that nothing leaves ISLAND. */
    fun routes(): TransferRouteQuery = mockk {
        every { route(any(), any()) } answers {
            val from = firstArg<String>()
            val to = secondArg<String>()
            TransferRoute(from, to, from == to || from != "ISLAND", BigDecimal.ZERO, 0)
        }
    }

    /** The criteria language without a database: the fixture taxonomy and regions. */
    object Criteria : CriteriaService {
        override fun parse(json: String): Criterion = error("not used")

        override fun toJson(criterion: Criterion): String = criterion.toString()

        override fun bind(criterion: Criterion, context: BindContext): BoundCriterion = CriteriaBinder.bind(criterion, context)

        override fun contextFor(deliveryDate: LocalDate): BindContext = EngineFixtures.ctx(deliveryDate)

        override fun evaluate(bound: BoundCriterion, subject: CriteriaSubject): Boolean = CriteriaEvaluator.matches(bound, subject)

        override fun compile(bound: BoundCriterion, columns: CriteriaColumns): org.jooq.Condition = error("not used")

        override fun implies(narrow: BoundCriterion, broad: BoundCriterion): Boolean = CriteriaImplication.implies(narrow, broad)

        override fun specificity(criterion: Criterion): BigDecimal = BigDecimal.ONE

        override fun describe(criterion: Criterion): String = criterion.toString()

        override fun catalogue(): CriteriaCatalogue = error("not used")

        override fun store(criterion: Criterion, createdBy: String?): CriteriaRef = error("not used")

        override fun load(id: UUID): Criterion = error("not used")

        override fun ref(id: UUID): CriteriaRef = error("not used")
    }
}
