package com.pianonurmi.etrm.product.fixtures

import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqFuturesContractRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqProductBucketRepository
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.FuturesContractService
import com.pianonurmi.etrm.product.application.ProductBucketSeeder
import com.pianonurmi.etrm.product.application.ProductBucketService
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import com.pianonurmi.etrm.product.application.TaxonomySnapshots
import com.pianonurmi.etrm.taxonomy.api.TaxonomyQuery
import org.jooq.DSLContext

/**
 * The product module as the application wires it, for hand-wired tests of any module: criteria over the taxonomy,
 * the default product buckets (seeded), the allowance catalogue and futures contracts.
 */
class ProductWiring(dsl: DSLContext, taxonomy: TaxonomyQuery, actor: ActorProvider) {
    val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
    val buckets = ProductBucketService(JooqProductBucketRepository(dsl), criteria).also { ProductBucketSeeder(it, it).seed() }
    val catalogue = ProductCatalogueService(JooqAllowanceProductRepository(dsl))
    val futures = FuturesContractService(JooqFuturesContractRepository(dsl), catalogue, actor)
}
