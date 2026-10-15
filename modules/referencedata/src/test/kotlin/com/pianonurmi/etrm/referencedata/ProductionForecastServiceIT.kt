package com.pianonurmi.etrm.referencedata

import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqProductionForecastRepository
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateProductionDevice
import com.pianonurmi.etrm.referencedata.api.ForecastSource
import com.pianonurmi.etrm.referencedata.api.OwnerType
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceView
import com.pianonurmi.etrm.referencedata.api.ProductionForecastChanged
import com.pianonurmi.etrm.referencedata.api.RecordForecast
import com.pianonurmi.etrm.referencedata.api.UpdateLoadFactor
import com.pianonurmi.etrm.referencedata.api.UpdateProductionDevice
import com.pianonurmi.etrm.referencedata.application.ProductionForecastService
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataPolicies
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionForecastServiceIT {
    private val published = mutableListOf<Any>()
    private var actor = Actor("mo-1", "Middle Office", setOf(Role.MIDDLE_OFFICE))
    private lateinit var devices: ReferenceDataService
    private lateinit var forecasts: ProductionForecastService
    private var gsrnCounter = 0

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata")
        val publisher = ApplicationEventPublisher { published += it }
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(TestDatabase.dsl), publisher)
        val repository = JooqReferenceDataRepository(TestDatabase.dsl)
        devices = ReferenceDataService(repository, taxonomy, publisher)
        forecasts = ProductionForecastService(
            JooqProductionForecastRepository(TestDatabase.dsl),
            repository,
            taxonomy,
            publisher,
            ActorProvider {
                actor
            },
            Clock.systemUTC(),
        )
    }

    @BeforeEach
    fun reset() {
        published.clear()
        actor = Actor("mo-1", "Middle Office", setOf(Role.MIDDLE_OFFICE))
    }

    private fun gsrn(): String {
        val body = "6430000000" + (++gsrnCounter).toString().padStart(7, '0')
        return (0..9).map { "$body$it" }.first(ReferenceDataPolicies::gs1CheckDigitValid)
    }

    private fun device(
        capacityKw: String = "10000",
        technology: String = "WIND_TURBINE_ONSHORE",
        commissioned: LocalDate = LocalDate.of(2026, 6, 10),
        decommissioned: LocalDate? = null,
    ): ProductionDeviceView = devices.createProductionDevice(
        CreateProductionDevice(
            gsrn = gsrn(),
            name = "Device $gsrnCounter",
            country = "FI",
            capacityKw = BigDecimal(capacityKw),
            commissioningDate = commissioned,
            decommissioningDate = decommissioned,
            energySourceCode = "WIND_ONSHORE",
            technologyCode = technology,
            ownerType = OwnerType.THIRD_PARTY,
        ),
    )

    @Test
    fun `recording replaces the forecast, keeps the old one in the history and announces the change`() {
        val device = device()
        val month = YearMonth.of(2026, 11)
        val first = forecasts.record(RecordForecast(device.id, month, 3000, 2200))
        first.version shouldBe 1
        first.source shouldBe ForecastSource.ENTERED
        first.recordedBy shouldBe "mo-1"
        published.filterIsInstance<ProductionForecastChanged>().single().let {
            it.deviceId shouldBe device.id
            it.months shouldBe listOf(month)
        }

        actor = Actor("mo-2", "Other Middle Office", setOf(Role.MIDDLE_OFFICE))
        val second = forecasts.record(RecordForecast(device.id, month, 3300, 2500))
        second.version shouldBe 2
        second.expectedMwh shouldBe 3300
        second.recordedBy shouldBe "mo-2"
        forecasts.forecasts(device.id) shouldHaveSize 1

        val history = TestDatabase.dsl.fetch(
            "SELECT version, expected_mwh, p90_mwh, recorded_by FROM etrm_referencedata.production_forecast_history WHERE device_id = ?",
            device.id,
        )
        history shouldHaveSize 1
        history[0].get("version") shouldBe 1
        history[0].get("expected_mwh") shouldBe 3000L
        history[0].get("p90_mwh") shouldBe 2200L
        history[0].get("recorded_by") shouldBe "mo-1"
        published.filterIsInstance<ProductionForecastChanged>() shouldHaveSize 2
    }

    @Test
    fun `a P90 above the expected figure, a negative figure, an unknown device and a bad role are refused`() {
        val device = device()
        val month = YearMonth.of(2026, 11)
        shouldThrow<ValidationException> { forecasts.record(RecordForecast(device.id, month, 1000, 1001)) }
        shouldThrow<ValidationException> { forecasts.record(RecordForecast(device.id, month, -1, -2)) }
        shouldThrow<NotFoundException> { forecasts.record(RecordForecast(java.util.UUID.randomUUID(), month, 10, 5)) }
        forecasts.forecasts(device.id) shouldHaveSize 0

        actor = Actor("trader", "Trader", setOf(Role.TRADER, Role.BACK_OFFICE))
        shouldThrow<ForbiddenException> { forecasts.record(RecordForecast(device.id, month, 10, 5)) }
        shouldThrow<ForbiddenException> { forecasts.importCsv("gsrn,month,expected_mwh,p90_mwh\n") }
        shouldThrow<ForbiddenException> { forecasts.updateLoadFactor("WIND_TURBINE_ONSHORE", 1, UpdateLoadFactor(BigDecimal("0.4"), BigDecimal("0.3"))) }
        published.filterIsInstance<ProductionForecastChanged>() shouldHaveSize 0
    }

    @Test
    fun `a CSV import loads the good lines, reports the bad ones and announces one change per device`() {
        val a = device()
        val b = device()
        val csv = """
            gsrn,month,expected_mwh,p90_mwh
            ${a.gsrn},2026-11,3000,2200
            ${a.gsrn},2026-12,3200,2400
            ${a.gsrn},2027-01,100,200
            ${b.gsrn},2026-11,500,400
            999999999999999999,2026-11,1,1
            ${b.gsrn},November,1,1
            ${b.gsrn},2026-12,abc,1
            ${b.gsrn},2026-12
        """.trimIndent()
        val result = forecasts.importCsv(csv)
        result.imported shouldBe 3
        result.rejected shouldBe 5
        result.errors shouldHaveSize 5
        result.errors[0] shouldContain "line 4"
        result.errors[0] shouldContain "P90"
        result.errors[1] shouldContain "line 6"
        result.errors[1] shouldContain "unknown device"
        result.errors[2] shouldContain "line 7"
        result.errors[3] shouldContain "line 8"
        result.errors[4] shouldContain "line 9"

        forecasts.forecasts(a.id).map { it.month to it.expectedMwh } shouldContainExactly
            listOf(YearMonth.of(2026, 11) to 3000L, YearMonth.of(2026, 12) to 3200L)
        forecasts.forecasts(a.id).all { it.source == ForecastSource.IMPORTED } shouldBe true
        forecasts.forecasts(b.id).map { it.expectedMwh } shouldBe listOf(500L)

        val events = published.filterIsInstance<ProductionForecastChanged>()
        events shouldHaveSize 2
        events.single { it.deviceId == a.id }.months shouldBe listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12))
        events.single { it.deviceId == b.id }.months shouldBe listOf(YearMonth.of(2026, 11))
    }

    @Test
    fun `expected production uses the forecast where there is one and the load factor elsewhere`() {
        val device = device(capacityKw = "10000", commissioned = LocalDate.of(2026, 6, 10))
        // July has 31 days = 744 hours: 10 000 kW = 10 MW x 744 h x 0.35 = 2 604 MWh; P90 at 0.2625 = 1 953 MWh.
        forecasts.updateLoadFactor("wind_turbine_onshore", 7, UpdateLoadFactor(BigDecimal("0.35"), BigDecimal("0.2625")))
        forecasts.record(RecordForecast(device.id, YearMonth.of(2026, 8), 2800, 2000))
        forecasts.record(RecordForecast(device.id, YearMonth.of(2026, 5), 9999, 9999)) // before commissioning: not counted

        val expected = forecasts.expected(device.id, YearMonth.of(2026, 5), YearMonth.of(2026, 8))
        expected.map { it.month } shouldBe listOf(YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8))
        expected.all { it.gsrn == device.gsrn && it.deviceId == device.id } shouldBe true

        // June: the seeded factor 0.21 over 720 hours = 1 512 MWh; P90 0.1575 = 1 134 MWh. Not pro-rated for the commissioning day.
        expected[0].source shouldBe ForecastSource.LOAD_FACTOR
        expected[0].expectedMwh shouldBe 1512
        expected[0].p90Mwh shouldBe 1134
        expected[1].source shouldBe ForecastSource.LOAD_FACTOR
        expected[1].expectedMwh shouldBe 2604
        expected[1].p90Mwh shouldBe 1953
        expected[2].source shouldBe ForecastSource.ENTERED
        expected[2].expectedMwh shouldBe 2800
        expected[2].p90Mwh shouldBe 2000

        forecasts.loadFactors().single { it.technologyCode == "WIND_TURBINE_ONSHORE" && it.monthOfYear == 7 }.factor.compareTo(BigDecimal("0.35")) shouldBe 0
    }

    @Test
    fun `figures are rounded half-up to whole MWh, a missing load factor gives zero and a decommissioned device stops`() {
        // 1 500 kW x 744 h x 0.35 / 1000 = 390.6 -> 391
        val small = device(capacityKw = "1500", commissioned = LocalDate.of(2026, 1, 1))
        forecasts.updateLoadFactor("WIND_TURBINE_ONSHORE", 7, UpdateLoadFactor(BigDecimal("0.35"), BigDecimal("0.30")))
        forecasts.expected(small.id, YearMonth.of(2026, 7), YearMonth.of(2026, 7)).single().expectedMwh shouldBe 391

        val tidal = device(technology = "MARINE_TIDAL", commissioned = LocalDate.of(2026, 1, 1))
        forecasts.expected(tidal.id, YearMonth.of(2026, 7), YearMonth.of(2026, 7)).single().let {
            it.expectedMwh shouldBe 0
            it.p90Mwh shouldBe 0
            it.source shouldBe ForecastSource.LOAD_FACTOR
        }

        val retired = device(commissioned = LocalDate.of(2026, 1, 1), decommissioned = LocalDate.of(2026, 9, 1))
        forecasts.expected(retired.id, YearMonth.of(2026, 8), YearMonth.of(2026, 10)).map { it.month } shouldBe listOf(YearMonth.of(2026, 8))

        forecasts.expected(java.util.UUID.randomUUID(), YearMonth.of(2026, 8), YearMonth.of(2026, 10)) shouldHaveSize 0
        shouldThrow<ValidationException> { forecasts.expected(small.id, YearMonth.of(2026, 10), YearMonth.of(2026, 8)) }
    }

    @Test
    fun `load factors are validated`() {
        shouldThrow<ValidationException> { forecasts.updateLoadFactor("WIND_TURBINE_ONSHORE", 13, UpdateLoadFactor(BigDecimal("0.4"), BigDecimal("0.3"))) }
        shouldThrow<ValidationException> { forecasts.updateLoadFactor("WIND_TURBINE_ONSHORE", 1, UpdateLoadFactor(BigDecimal("1.2"), BigDecimal("0.3"))) }
        shouldThrow<ValidationException> { forecasts.updateLoadFactor("WIND_TURBINE_ONSHORE", 1, UpdateLoadFactor(BigDecimal("0.4"), BigDecimal("0.5"))) }
        shouldThrow<NotFoundException> { forecasts.updateLoadFactor("PERPETUUM_MOBILE", 1, UpdateLoadFactor(BigDecimal("0.4"), BigDecimal("0.3"))) }
        forecasts.loadFactors().filter { it.technologyCode == "SOLAR_PV" } shouldHaveSize 12
    }

    @Test
    fun `devices carry the issuance lag`() {
        val device = device()
        device.issuanceLagDays shouldBe 30
        devices.updateProductionDevice(device.id, UpdateProductionDevice(issuanceLagDays = 45, expectedVersion = device.version)).issuanceLagDays shouldBe 45
        devices.requireProductionDevice(device.id).issuanceLagDays shouldBe 45
        shouldThrow<ValidationException> {
            devices.updateProductionDevice(device.id, UpdateProductionDevice(issuanceLagDays = -1, expectedVersion = device.version + 1))
        }
    }

    @Test
    fun `the forecast history cannot be changed or deleted`() {
        val device = device()
        forecasts.record(RecordForecast(device.id, YearMonth.of(2026, 11), 3000, 2200))
        forecasts.record(RecordForecast(device.id, YearMonth.of(2026, 11), 3100, 2300))
        shouldThrow<DataAccessException> {
            TestDatabase.dsl.execute("UPDATE etrm_referencedata.production_forecast_history SET expected_mwh = 1 WHERE device_id = ?", device.id)
        }
        shouldThrow<DataAccessException> {
            TestDatabase.dsl.execute("DELETE FROM etrm_referencedata.production_forecast_history WHERE device_id = ?", device.id)
        }
    }
}
