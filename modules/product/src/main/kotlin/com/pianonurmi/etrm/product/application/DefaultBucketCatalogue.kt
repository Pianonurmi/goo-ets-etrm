package com.pianonurmi.etrm.product.application

import com.pianonurmi.etrm.product.api.CreateProductBucket
import com.pianonurmi.etrm.product.api.ProductBucketCommand
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.all
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.label
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.region
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.unsupported
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/**
 * Standard electricity GoO buckets. Seeded on first start; idempotent on code. Vintage is deliberately not part of a
 * bucket: positions key on (bucket, production year), so one catalogue serves every vintage.
 */
object DefaultBucketCatalogue {
    val buckets: List<CreateProductBucket> = listOf(
        CreateProductBucket("ANY_GOO", "Any guarantee of origin", criterion = Criterion.MatchAll, sortOrder = 999),
        CreateProductBucket("EU_RENEWABLE", "European renewable (EEA)", criterion = all(region("EEA"), energySource("RENEWABLE")), sortOrder = 900),
        CreateProductBucket("EU_HYDRO", "European hydro", criterion = all(region("EEA"), energySource("HYDRO")), sortOrder = 500),
        CreateProductBucket("EU_WIND", "European wind", criterion = all(region("EEA"), energySource("WIND")), sortOrder = 500),
        CreateProductBucket("EU_SOLAR", "European solar", criterion = all(region("EEA"), energySource("SOLAR")), sortOrder = 500),
        CreateProductBucket("EU_BIOMASS", "European biomass", criterion = all(region("EEA"), energySource("BIOMASS")), sortOrder = 500),
        CreateProductBucket("EU_NUCLEAR", "European nuclear", criterion = all(region("EEA"), energySource("NUCLEAR")), sortOrder = 500),
        CreateProductBucket(
            "EU_WIND_UNSUPPORTED",
            "European wind, no support",
            criterion = all(region("EEA"), energySource("WIND"), unsupported()),
            sortOrder = 400,
        ),
        CreateProductBucket("NORDIC_RENEWABLE", "Nordic renewable", criterion = all(region("NORDIC"), energySource("RENEWABLE")), sortOrder = 800),
        CreateProductBucket("NORDIC_HYDRO", "Nordic hydro", criterion = all(region("NORDIC"), energySource("HYDRO")), sortOrder = 300),
        CreateProductBucket("NORDIC_WIND", "Nordic wind", criterion = all(region("NORDIC"), energySource("WIND")), sortOrder = 300),
        CreateProductBucket("NORDIC_SOLAR", "Nordic solar", criterion = all(region("NORDIC"), energySource("SOLAR")), sortOrder = 300),
        CreateProductBucket("FI_RENEWABLE", "Finnish renewable", criterion = all(Criterion.country("FI"), energySource("RENEWABLE")), sortOrder = 700),
        CreateProductBucket("FI_WIND", "Finnish wind", criterion = all(Criterion.country("FI"), energySource("WIND")), sortOrder = 200),
        CreateProductBucket("FI_HYDRO", "Finnish hydro", criterion = all(Criterion.country("FI"), energySource("HYDRO")), sortOrder = 200),
        CreateProductBucket("EKOENERGY", "EKOenergy labelled", criterion = all(energySource("RENEWABLE"), label("EKOENERGY")), sortOrder = 100),
        CreateProductBucket("BRA_MILJOVAL", "Bra Miljöval labelled", criterion = all(energySource("RENEWABLE"), label("BRA_MILJOVAL")), sortOrder = 100),
    )
}

@Component
class ProductBucketSeeder(private val query: ProductBucketQuery, private val command: ProductBucketCommand) : InitializingBean {
    private val log = LoggerFactory.getLogger(ProductBucketSeeder::class.java)

    override fun afterPropertiesSet() = seed()

    fun seed() {
        val existing = query.buckets(activeOnly = false).map { it.code }.toSet()
        val created = DefaultBucketCatalogue.buckets.filter { it.code !in existing }.onEach { command.create(it) }
        if (created.isNotEmpty()) log.info("Seeded {} default product buckets", created.size)
    }
}
