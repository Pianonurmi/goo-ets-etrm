package com.pianonurmi.etrm.product

import com.pianonurmi.etrm.platform.test.PropertySeeds
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.api.criteria.VintageRule
import com.pianonurmi.etrm.product.domain.CriteriaBinder
import com.pianonurmi.etrm.product.domain.CriteriaEvaluator
import com.pianonurmi.etrm.product.domain.CriteriaImplication
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Soundness of [CriteriaImplication]: whenever it claims that a criterion implies another, every random certificate
 * accepted by the first is accepted by the second. Random pairs mix unrelated trees with derived ones (a disjunction
 * containing the criterion, a conjunct of it) so that proofs are found often enough to matter.
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class CriteriaImplicationTest {
    private data class Lot(
        val country: String?,
        val source: String?,
        override val labels: Set<String>,
        val supported: Boolean?,
        val capacity: BigDecimal?,
        val commissioned: LocalDate?,
        override val productionPeriod: LocalDateRange,
    ) : CriteriaSubject {
        override fun string(attribute: LotAttribute): String? = when (attribute) {
            LotAttribute.DEVICE_COUNTRY -> country
            LotAttribute.ENERGY_CARRIER -> "ELECTRICITY"
            else -> null
        }

        override fun bool(attribute: LotAttribute): Boolean? = if (attribute == LotAttribute.SUPPORT_PRODUCTION) supported else null

        override fun number(attribute: LotAttribute): BigDecimal? = if (attribute == LotAttribute.DEVICE_CAPACITY_KW) capacity else null

        override fun date(attribute: LotAttribute): LocalDate? = if (attribute == LotAttribute.COMMISSIONING_DATE) commissioned else null

        override fun taxonomyPath(attribute: LotAttribute): String? = if (attribute == LotAttribute.ENERGY_SOURCE) source else null
    }

    private val sourcePaths = listOf(
        "RENEWABLE",
        "RENEWABLE.WIND",
        "RENEWABLE.WIND.WIND_ONSHORE",
        "RENEWABLE.HYDRO",
        "RENEWABLE.HYDRO.HYDRO_RESERVOIR",
        "RENEWABLE.SOLAR",
        "NON_RENEWABLE.NUCLEAR",
    )
    private val sourceCodes = sourcePaths.associateBy { it.substringAfterLast('.') }
    private val countries = listOf("FI", "SE", "NO", "DE", "FR")
    private val labelCodes = listOf("EKOENERGY", "BRA_MILJOVAL", "OK_POWER")
    private val regions = mapOf("NORDIC" to setOf("FI", "SE", "NO", "DK"), "CWE" to setOf("DE", "FR", "NL"))
    private val ctx = BindContext(LocalDate.of(2026, 9, 15), { regions[it] ?: emptySet() }, { _, code -> sourceCodes[code] })

    private val lotArb: Arb<Lot> = Arb.bind(
        Arb.element(countries).orNull(0.1),
        Arb.element(sourcePaths).orNull(0.1),
        Arb.set(Arb.element(labelCodes), 0..2),
        Arb.boolean().orNull(0.2),
        Arb.int(500, 400_000).map { BigDecimal(it) }.orNull(0.1),
        Arb.int(1990, 2026).map { LocalDate.of(it, 6, 1) }.orNull(0.1),
        Arb.int(0, 35).map { LocalDateRange.ofMonth(YearMonth.of(2024, 1).plusMonths(it.toLong())) },
    ) { c, s, l, sp, cap, comm, p -> Lot(c, s, l, sp, cap, comm, p) }

    private val leafArb: Arb<Criterion> = Arb.choice(
        Arb.set(Arb.element(countries), 1..3).map { Criterion.InSet(LotAttribute.DEVICE_COUNTRY, it) },
        Arb.element(countries).map { Criterion.Equals(LotAttribute.DEVICE_COUNTRY, it) },
        Arb.element(regions.keys.toList()).map { Criterion.CountryInRegion(it) },
        Arb.element(sourceCodes.keys.toList()).map { Criterion.energySource(it) },
        Arb.set(Arb.element(labelCodes), 1..2).map { Criterion.LabelsAny(it) },
        Arb.set(Arb.element(labelCodes), 1..2).map { Criterion.LabelsAll(it) },
        Arb.boolean().map { Criterion.Equals(LotAttribute.SUPPORT_PRODUCTION, it.toString()) },
        Arb.int(1, 30).map { Criterion.DeviceAgeAtDelivery(it) },
        Arb.int(2024, 2026).map { Criterion.productionYear(it) },
        Arb.int(1, 24).map { Criterion.Vintage(VintageRule.MonthsBeforeDelivery(it)) },
        Arb.bind(Arb.int(1, 200).orNull(0.3), Arb.int(201, 400).orNull(0.3)) { lo, hi ->
            if (lo == null && hi == null) {
                Criterion.MatchAll
            } else {
                Criterion.NumberRange(
                    LotAttribute.DEVICE_CAPACITY_KW,
                    lo?.let {
                        BigDecimal(it * 1000)
                    },
                    hi?.let { BigDecimal(it * 1000) },
                )
            }
        },
        Arb.int(1995, 2025).map { Criterion.DateRange(LotAttribute.COMMISSIONING_DATE, from = LocalDate.of(it, 1, 1)) },
        Arb.element(listOf(Criterion.MatchAll)),
    )

    private fun treeArb(depth: Int): Arb<Criterion> = if (depth == 0) {
        leafArb
    } else {
        Arb.choice(
            leafArb,
            Arb.bind(treeArb(depth - 1), treeArb(depth - 1)) { a, b -> Criterion.all(a, b) },
            Arb.bind(treeArb(depth - 1), treeArb(depth - 1)) { a, b -> Criterion.anyOf(a, b) },
            treeArb(depth - 1).map { Criterion.Not(it) },
        )
    }

    /** A broad candidate for [narrow]: unrelated, a disjunction containing it, one of its conjuncts, or a weakened leaf. */
    private fun broadArb(narrow: Criterion): Arb<Criterion> = Arb.choice(
        treeArb(2),
        treeArb(1).map { Criterion.anyOf(narrow, it) },
        Arb.element(((narrow as? Criterion.All)?.of ?: listOf(narrow))),
        leafArb,
    )

    private val lots = runBlocking { lotArb.samples().take(500).map { it.value }.toList() }

    private fun bind(c: Criterion): BoundCriterion = CriteriaBinder.bind(c, ctx)

    @Test
    fun `claimed implications hold for every certificate`() {
        var proved = 0
        runBlocking {
            checkAll(PropTestConfig(seed = PropertySeeds.seed(3000L), iterations = 3000), treeArb(2)) { narrow ->
                val n = bind(narrow)
                broadArb(narrow).samples().take(3).map { it.value }.forEach { broad ->
                    val b = bind(broad)
                    if (CriteriaImplication.implies(n, b)) {
                        proved++
                        lots.filter { CriteriaEvaluator.matches(n, it) }.forEach { lot -> CriteriaEvaluator.matches(b, lot) shouldBe true }
                    }
                }
            }
        }
        proved shouldBeGreaterThan 1000
    }

    @Test
    fun `typical delivery criteria imply their buckets`() {
        val trade = Criterion.all(Criterion.country("FI"), Criterion.energySource("HYDRO_RESERVOIR"), Criterion.productionYear(2026))
        CriteriaImplication.implies(bind(trade), bind(Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")))) shouldBe true
        CriteriaImplication.implies(bind(trade), bind(Criterion.all(Criterion.region("CWE"), Criterion.energySource("HYDRO")))) shouldBe false
        CriteriaImplication.implies(bind(Criterion.label("EKOENERGY")), bind(Criterion.LabelsAny(setOf("EKOENERGY", "OK_POWER")))) shouldBe true
        CriteriaImplication.implies(bind(Criterion.productionYear(2026)), bind(Criterion.Vintage(VintageRule.MonthsBeforeDelivery(6)))) shouldBe false
        // Six months before a September delivery lie within the delivery year.
        CriteriaImplication.implies(bind(Criterion.Vintage(VintageRule.MonthsBeforeDelivery(6))), bind(Criterion.productionYear(2026))) shouldBe true
        CriteriaImplication.implies(
            bind(Criterion.Vintage(VintageRule.Explicit(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 1)))),
            bind(Criterion.productionYear(2026)),
        ) shouldBe true
    }
}
