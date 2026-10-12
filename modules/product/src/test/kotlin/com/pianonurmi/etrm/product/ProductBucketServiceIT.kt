package com.pianonurmi.etrm.product

import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqProductBucketRepository
import com.pianonurmi.etrm.product.api.BucketMatchKind
import com.pianonurmi.etrm.product.api.CreateProductBucket
import com.pianonurmi.etrm.product.api.UpdateProductBucket
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.DefaultBucketCatalogue
import com.pianonurmi.etrm.product.application.ProductBucketSeeder
import com.pianonurmi.etrm.product.application.ProductBucketService
import com.pianonurmi.etrm.product.application.TaxonomySnapshots
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeIn
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductBucketServiceIT {
    private lateinit var criteria: CriteriaServiceImpl
    private lateinit var buckets: ProductBucketService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "product")
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(TestDatabase.dsl), ApplicationEventPublisher { })
        criteria = CriteriaServiceImpl(JooqCriteriaRepository(TestDatabase.dsl), TaxonomySnapshots(taxonomy))
        buckets = ProductBucketService(JooqProductBucketRepository(TestDatabase.dsl), criteria)
        ProductBucketSeeder(buckets, buckets).seed()
        ProductBucketSeeder(buckets, buckets).seed() // idempotent
    }

    @Test
    fun `default catalogue is seeded once and criteria are deduplicated by hash`() {
        buckets.buckets().size shouldBe DefaultBucketCatalogue.buckets.size
        val a = criteria.store(Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")))
        val b = criteria.store(Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")))
        a.id shouldBe b.id
        a.id shouldBe buckets.requireBucket("NORDIC_HYDRO").criteriaId
        criteria.load(a.id) shouldBe Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"))
    }

    @Test
    fun `a lot maps to every matching bucket and the most specific one is best`() {
        val bound = buckets.bind(criteria.contextFor(LocalDate.of(2026, 12, 1)))
        val finnishHydro = subject("FI", "RENEWABLE.HYDRO.HYDRO_RESERVOIR", setOf("EKOENERGY"))
        bound.matching(finnishHydro).map {
            it.code
        } shouldContainAll listOf("ANY_GOO", "EU_RENEWABLE", "EU_HYDRO", "NORDIC_RENEWABLE", "NORDIC_HYDRO", "FI_HYDRO", "EKOENERGY")
        bound.best(finnishHydro)?.code shouldBe "FI_HYDRO"
        bound.best(subject("DE", "RENEWABLE.WIND.WIND_OFFSHORE", emptySet()))?.code shouldBe "EU_WIND_UNSUPPORTED"
        bound.best(subject("DE", "NON_RENEWABLE.FOSSIL.FOSSIL_GAS", emptySet()))?.code shouldBe "ANY_GOO"
    }

    @Test
    fun `ad-hoc criteria map to the most specific bucket that provably contains them`() {
        val ctx = criteria.contextFor(LocalDate.of(2026, 12, 1))
        val finnishReservoir2026 = Criterion.all(Criterion.country("FI"), Criterion.energySource("HYDRO_RESERVOIR"), Criterion.productionYear(2026))
        buckets.bucketFor(null, finnishReservoir2026, ctx)!!.let {
            it.bucket.code shouldBe "FI_HYDRO"
            it.kind shouldBe BucketMatchKind.IMPLIED
        }
        val nordicHydro = buckets.requireBucket("NORDIC_HYDRO")
        buckets.bucketFor(nordicHydro.criteriaId, nordicHydro.criterion, ctx)!!.let {
            it.bucket.code shouldBe "NORDIC_HYDRO"
            it.kind shouldBe BucketMatchKind.EXACT
        }
        // Finland or Germany is not Nordic: only the European bucket provably contains it.
        buckets.bucketFor(null, Criterion.all(Criterion.country("FI", "DE"), Criterion.energySource("WIND")), ctx)!!.bucket.code shouldBe "EU_WIND"
        // "Not nuclear" proves nothing about the source.
        buckets.bucketFor(null, Criterion.Not(Criterion.energySource("NUCLEAR")), ctx)!!.bucket.code shouldBe "ANY_GOO"
    }

    @Test
    fun `implication is exposed through the service`() {
        val nordic = criteria.bind(Criterion.region("NORDIC"), criteria.contextFor(LocalDate.of(2026, 12, 1)))
        val hydro = criteria.bind(Criterion.energySource("HYDRO"), criteria.contextFor(LocalDate.of(2026, 12, 1)))
        criteria.implies(BoundCriterion.All(listOf(nordic, hydro)), hydro) shouldBe true
        criteria.implies(hydro, BoundCriterion.All(listOf(nordic, hydro))) shouldBe false
        criteria.implies(nordic, BoundCriterion.MatchAll) shouldBe true
    }

    @Test
    fun `the hierarchy puts each bucket under its smallest proven containers`() {
        val hierarchy = buckets.hierarchy(criteria.contextFor(LocalDate.of(2026, 12, 1)))
        val parents = hierarchy.nodes.associate { it.code to it.parents.toSet() }
        hierarchy.roots shouldBe listOf("ANY_GOO")
        parents["EU_RENEWABLE"] shouldBe setOf("ANY_GOO")
        parents["NORDIC_RENEWABLE"] shouldBe setOf("EU_RENEWABLE")
        // Two ways up: Nordic hydro is European hydro and Nordic renewable; neither contains the other.
        parents["NORDIC_HYDRO"] shouldBe setOf("EU_HYDRO", "NORDIC_RENEWABLE")
        parents["FI_HYDRO"] shouldBe setOf("NORDIC_HYDRO", "FI_RENEWABLE")
        // Transitive reduction: Finnish hydro is also European renewable, but through its parents only.
        "EU_RENEWABLE" shouldNotBeIn parents.getValue("FI_HYDRO")
        // A label proves nothing about region, so labelled buckets sit right under the root.
        parents["EKOENERGY"] shouldBe setOf("ANY_GOO")
        hierarchy.nodes.single { it.code == "FI_HYDRO" }.depth shouldBe 4
        hierarchy.descendants(
            "NORDIC_RENEWABLE",
        ) shouldBe setOf("NORDIC_RENEWABLE", "NORDIC_HYDRO", "NORDIC_SOLAR", "NORDIC_WIND", "FI_RENEWABLE", "FI_HYDRO", "FI_WIND")
        hierarchy.nodes.indexOfFirst { it.code == "NORDIC_HYDRO" } shouldBeGreaterThan hierarchy.nodes.indexOfFirst { it.code == "EU_HYDRO" }
    }

    @Test
    fun `buckets proven equivalent are one node with aliases`() {
        val twin = buckets.create(
            CreateProductBucket(
                "NORDIC_HYDRO_TWIN",
                "Nordic hydro (twin)",
                criterion = Criterion.all(Criterion.energySource("HYDRO"), Criterion.region("NORDIC")),
                sortOrder = 990,
            ),
        )
        try {
            val hierarchy = buckets.hierarchy(criteria.contextFor(LocalDate.of(2026, 12, 1)))
            hierarchy.nodes.single { it.code == "NORDIC_HYDRO" }.aliases shouldBe listOf("NORDIC_HYDRO_TWIN")
            hierarchy.nodes.none { it.code == "NORDIC_HYDRO_TWIN" } shouldBe true
        } finally {
            buckets.update(twin.code, UpdateProductBucket(active = false, expectedVersion = twin.version))
        }
    }

    private fun subject(country: String, source: String, labels: Set<String>) = object : CriteriaSubject {
        override fun string(attribute: LotAttribute): String? = when (attribute) {
            LotAttribute.DEVICE_COUNTRY -> country
            LotAttribute.ENERGY_CARRIER -> "ELECTRICITY"
            else -> null
        }

        override fun bool(attribute: LotAttribute): Boolean = false

        override fun number(attribute: LotAttribute): BigDecimal? = null

        override fun date(attribute: LotAttribute): LocalDate? = null

        override fun taxonomyPath(attribute: LotAttribute): String? = if (attribute == LotAttribute.ENERGY_SOURCE) source else null

        override val labels: Set<String> = labels

        override val productionPeriod: LocalDateRange = LocalDateRange.ofMonth(YearMonth.of(2026, 5))
    }
}
