package com.pianonurmi.etrm.taxonomy

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.api.CreateTaxonomyNode
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import com.pianonurmi.etrm.taxonomy.api.UpdateTaxonomyNode
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaxonomyServiceIT {
    private val published = mutableListOf<Any>()
    private lateinit var service: TaxonomyService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy")
        service = TaxonomyService(JooqTaxonomyRepository(TestDatabase.dsl), ApplicationEventPublisher { published += it })
    }

    @Test
    fun `seeded hierarchy resolves descendant relationships through ltree`() {
        service.isDescendantOrSelf(TaxonomyScheme.ENERGY_SOURCE, "WIND_ONSHORE", "RENEWABLE") shouldBe true
        service.isDescendantOrSelf(TaxonomyScheme.ENERGY_SOURCE, "WIND_ONSHORE", "WIND") shouldBe true
        service.isDescendantOrSelf(TaxonomyScheme.ENERGY_SOURCE, "WIND", "WIND_ONSHORE") shouldBe false
        service.isDescendantOrSelf(TaxonomyScheme.ENERGY_SOURCE, "FOSSIL_GAS", "RENEWABLE") shouldBe false
        service.subtree(TaxonomyScheme.ENERGY_SOURCE, "HYDRO").map { it.code } shouldContainAll
            listOf("HYDRO", "HYDRO_RUN_OF_RIVER", "HYDRO_RESERVOIR", "HYDRO_PUMPED")
    }

    @Test
    fun `region sets enumerate countries`() {
        service.regionCountries("NORDIC") shouldBe setOf("FI", "SE", "NO", "DK", "IS")
        service.regionCountries("EU27").size shouldBe 27
        service.regionCountries("EEA") shouldContain "NO"
        service.regionCountries("EU27") shouldNotContain "NO"
    }

    @Test
    fun `snapshot answers hierarchy questions in memory`() {
        val snapshot = service.snapshot(TaxonomyScheme.ENERGY_SOURCE)
        snapshot.isDescendantOrSelf("BIOGAS_LANDFILL", "BIOMASS") shouldBe true
        snapshot.descendantsOrSelf("WIND") shouldBe setOf("WIND", "WIND_ONSHORE", "WIND_OFFSHORE")
        service.snapshot(TaxonomyScheme.REGION).regionCountries("BALTIC") shouldBe setOf("EE", "LV", "LT")
    }

    @Test
    fun `create child node, update with optimistic locking and external code lookup`() {
        val created = service.create(
            CreateTaxonomyNode(
                scheme = TaxonomyScheme.ENERGY_SOURCE,
                code = "WIND_FLOATING",
                parentCode = "WIND_OFFSHORE",
                name = "Floating offshore wind",
                externalCodes = mapOf("AIB" to "F010502XX"),
                validFrom = LocalDate.of(2026, 1, 1),
            ),
        )
        created.path shouldBe "RENEWABLE.WIND.WIND_OFFSHORE.WIND_FLOATING"
        created.depth shouldBe 3
        service.byExternalCode(TaxonomyScheme.ENERGY_SOURCE, "AIB", "F010502XX")?.code shouldBe "WIND_FLOATING"
        published.size shouldBe 1

        shouldThrow<ConflictException> {
            service.create(CreateTaxonomyNode(TaxonomyScheme.ENERGY_SOURCE, "WIND_FLOATING", "WIND", "dup", validFrom = LocalDate.of(2026, 1, 1)))
        }

        val updated = service.update(TaxonomyScheme.ENERGY_SOURCE, "WIND_FLOATING", UpdateTaxonomyNode(name = "Floating wind", expectedVersion = 1))
        updated.version shouldBe 2
        updated.name shouldBe "Floating wind"
        shouldThrow<StaleVersionException> {
            service.update(TaxonomyScheme.ENERGY_SOURCE, "WIND_FLOATING", UpdateTaxonomyNode(name = "x", expectedVersion = 1))
        }
    }

    @Test
    fun `region membership can be replaced`() {
        service.create(CreateTaxonomyNode(TaxonomyScheme.REGION, "TEST_REGION", "EUROPE", "Test", validFrom = LocalDate.of(2026, 1, 1)))
        service.setRegionMembers("TEST_REGION", setOf("fi", "SE")) shouldBe setOf("FI", "SE")
        service.regionCountries("TEST_REGION") shouldBe setOf("FI", "SE")
    }
}
