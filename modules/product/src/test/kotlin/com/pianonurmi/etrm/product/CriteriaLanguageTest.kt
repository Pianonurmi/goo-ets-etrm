package com.pianonurmi.etrm.product

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.all
import com.pianonurmi.etrm.product.api.criteria.Criterion.Companion.energySource
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.api.criteria.VintageRule
import com.pianonurmi.etrm.product.domain.CriteriaBinder
import com.pianonurmi.etrm.product.domain.CriteriaCodec
import com.pianonurmi.etrm.product.domain.CriteriaDescriber
import com.pianonurmi.etrm.product.domain.CriteriaEvaluator
import com.pianonurmi.etrm.product.domain.Specificity
import com.pianonurmi.etrm.product.domain.SpecificityWeights
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class CriteriaLanguageTest {
    private val paths = mapOf(
        "RENEWABLE" to "RENEWABLE",
        "WIND" to "RENEWABLE.WIND",
        "WIND_ONSHORE" to "RENEWABLE.WIND.WIND_ONSHORE",
        "HYDRO" to "RENEWABLE.HYDRO",
        "NUCLEAR" to "NON_RENEWABLE.NUCLEAR",
    )
    private val ctx = BindContext(
        deliveryDate = LocalDate.of(2026, 6, 30),
        regionCountries = { if (it == "NORDIC") setOf("FI", "SE", "NO", "DK", "IS") else emptySet() },
        taxonomyPath = { _, code -> paths[code] },
    )

    private val finnishWind =
        subject(country = "FI", source = "RENEWABLE.WIND.WIND_ONSHORE", labels = setOf("EKOENERGY"), commissioned = LocalDate.of(2022, 3, 1))

    @Test
    fun `json envelope round trips and is canonical`() {
        val criterion = all(
            Criterion.CountryInRegion("NORDIC"),
            energySource("WIND"),
            Criterion.Vintage(VintageRule.ProductionYear(2026)),
            Criterion.Not(Criterion.LabelsAny(setOf("BRA_MILJOVAL"))),
            Criterion.DeviceAgeAtDelivery(maxYears = 10),
        )
        val json = CriteriaCodec.toJson(criterion)
        json shouldContain "\"v\":1"
        CriteriaCodec.parse(json) shouldBe criterion
        CriteriaCodec.canonical(criterion) shouldBe CriteriaCodec.canonical(CriteriaCodec.parse(json))
        shouldThrow<ValidationException> { CriteriaCodec.parse("""{"v":99,"criterion":{"type":"matchAll"}}""") }
        shouldThrow<ValidationException> { CriteriaCodec.parse("""{"type":"matchAll"}""") }
        shouldThrow<ValidationException> { CriteriaCodec.parse("""{"v":1,"criterion":{"type":"unicorn"}}""") }
    }

    @Test
    fun `binding resolves regions, taxonomy paths, device age and vintage`() {
        val bound = CriteriaBinder.bind(
            all(
                Criterion.CountryInRegion("NORDIC"),
                energySource("WIND"),
                Criterion.DeviceAgeAtDelivery(5),
                Criterion.Vintage(VintageRule.SameCalendarYearAsDelivery),
            ),
            ctx,
        ) as BoundCriterion.All
        bound.of[0] shouldBe BoundCriterion.InSet(LotAttribute.DEVICE_COUNTRY, setOf("FI", "SE", "NO", "DK", "IS"))
        bound.of[1] shouldBe BoundCriterion.PathDescendantOf(LotAttribute.ENERGY_SOURCE, "RENEWABLE.WIND")
        bound.of[2] shouldBe BoundCriterion.DateRange(LotAttribute.COMMISSIONING_DATE, LocalDate.of(2021, 6, 30), LocalDate.of(2026, 7, 1))
        bound.of[3] shouldBe BoundCriterion.ProductionPeriodWithin(LocalDateRange.ofYear(2026))
        CriteriaBinder.bind(energySource("UNICORN"), ctx) shouldBe BoundCriterion.MatchNone("Unknown ENERGY_SOURCE code UNICORN")
        CriteriaBinder.bind(Criterion.CountryInRegion("ATLANTIS"), ctx) shouldBe BoundCriterion.MatchNone("Region ATLANTIS has no countries")
    }

    @Test
    fun `evaluation follows null semantics`() {
        val matches = { c: Criterion, s: CriteriaSubject -> CriteriaEvaluator.matches(CriteriaBinder.bind(c, ctx), s) }
        matches(all(Criterion.CountryInRegion("NORDIC"), energySource("WIND")), finnishWind) shouldBe true
        matches(energySource("HYDRO"), finnishWind) shouldBe false
        matches(energySource("RENEWABLE"), finnishWind) shouldBe true
        matches(Criterion.LabelsAll(setOf("EKOENERGY")), finnishWind) shouldBe true
        matches(Criterion.LabelsAll(setOf("EKOENERGY", "OK_POWER")), finnishWind) shouldBe false
        matches(Criterion.DeviceAgeAtDelivery(3), finnishWind) shouldBe false
        matches(Criterion.DeviceAgeAtDelivery(5), finnishWind) shouldBe true
        matches(Criterion.Vintage(VintageRule.ProductionYear(2026)), finnishWind) shouldBe true
        matches(Criterion.Vintage(VintageRule.MonthsBeforeDelivery(2)), finnishWind) shouldBe false

        val unknownCommissioning = subject(country = "SE", source = "RENEWABLE.HYDRO", commissioned = null)
        matches(Criterion.DeviceAgeAtDelivery(10), unknownCommissioning) shouldBe false
        matches(Criterion.Not(Criterion.DeviceAgeAtDelivery(10)), unknownCommissioning) shouldBe true
        matches(Criterion.Equals(LotAttribute.SUPPORT_PRODUCTION, "false"), unknownCommissioning) shouldBe false
    }

    @Test
    fun `specificity orders constrained criteria above generic ones`() {
        val specificity = Specificity(SpecificityWeights.DEFAULT, { _, code -> paths[code]?.count { it == '.' } }, { 5 })
        val generic = specificity.score(energySource("RENEWABLE"))
        val nordicWind = specificity.score(all(Criterion.CountryInRegion("NORDIC"), energySource("WIND")))
        val device = specificity.score(Criterion.device("643000000000000016"))
        nordicWind shouldBeGreaterThan generic
        device shouldBeGreaterThan nordicWind
        specificity.score(Criterion.MatchAll) shouldBe BigDecimal("0.0000")
        specificity.score(Criterion.anyOf(Criterion.device("1"), energySource("RENEWABLE"))) shouldBe generic
    }

    @Test
    fun `describer renders trade-confirmation style text`() {
        CriteriaDescriber.describe(all(Criterion.CountryInRegion("NORDIC"), energySource("HYDRO"), Criterion.productionYear(2026))) shouldBe
            "device in region NORDIC AND energy source within HYDRO AND vintage 2026"
    }

    private fun subject(
        country: String?,
        source: String?,
        labels: Set<String> = emptySet(),
        commissioned: LocalDate?,
    ): CriteriaSubject = object : CriteriaSubject {
        override fun string(attribute: LotAttribute): String? = when (attribute) {
            LotAttribute.DEVICE_COUNTRY -> country
            LotAttribute.ENERGY_CARRIER -> "ELECTRICITY"
            else -> null
        }

        override fun bool(attribute: LotAttribute): Boolean? = null

        override fun number(attribute: LotAttribute): BigDecimal? = null

        override fun date(attribute: LotAttribute): LocalDate? = if (attribute == LotAttribute.COMMISSIONING_DATE) commissioned else null

        override fun taxonomyPath(attribute: LotAttribute): String? = if (attribute == LotAttribute.ENERGY_SOURCE) source else null

        override val labels: Set<String> = labels

        override val productionPeriod: LocalDateRange = LocalDateRange.ofMonth(YearMonth.of(2026, 3))
    }
}
