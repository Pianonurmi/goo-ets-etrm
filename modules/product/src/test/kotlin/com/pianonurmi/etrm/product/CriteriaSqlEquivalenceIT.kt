package com.pianonurmi.etrm.product

import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.CriteriaColumns
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.api.criteria.VintageRule
import com.pianonurmi.etrm.product.domain.CriteriaBinder
import com.pianonurmi.etrm.product.domain.CriteriaCodec
import com.pianonurmi.etrm.product.domain.CriteriaEvaluator
import com.pianonurmi.etrm.product.domain.JooqCriteriaCompiler
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
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * Property test: for random criteria trees and random lot populations, the set of rows selected by the compiled
 * SQL condition equals the set accepted by the in-memory evaluator. This is the contract both allocation (memory)
 * and inventory search (SQL) rely on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(io.kotest.common.ExperimentalKotest::class)
class CriteriaSqlEquivalenceIT {
    private val dsl = TestDatabase.dsl
    private val table = DSL.table(DSL.name("etrm_product", "criteria_fixture"))

    private object Columns : CriteriaColumns {
        private fun <T> f(name: String, type: org.jooq.DataType<T>): Field<T> = DSL.field(DSL.name("etrm_product", "criteria_fixture", name), type)

        override fun string(attribute: LotAttribute): Field<String?> = f(attribute.name.lowercase(), SQLDataType.VARCHAR)

        override fun bool(attribute: LotAttribute): Field<Boolean?> = f(attribute.name.lowercase(), SQLDataType.BOOLEAN)

        override fun number(attribute: LotAttribute): Field<BigDecimal?> = f(attribute.name.lowercase(), SQLDataType.NUMERIC)

        override fun date(attribute: LotAttribute): Field<LocalDate?> = f(attribute.name.lowercase(), SQLDataType.LOCALDATE)

        override fun taxonomyPath(attribute: LotAttribute): Field<*> = DSL.field(DSL.name("etrm_product", "criteria_fixture", attribute.name.lowercase()))

        override val labels: Field<Array<String?>?> = f("labels", SQLDataType.VARCHAR.arrayDataType)

        override val productionPeriod: Field<*> = DSL.field(DSL.name("etrm_product", "criteria_fixture", "production_period"))
    }

    private data class Row(
        val id: Int,
        val country: String?,
        val carrier: String,
        val source: String?,
        val technology: String?,
        override val labels: Set<String>,
        val supportProduction: Boolean?,
        val supportInvestment: Boolean?,
        val capacity: BigDecimal?,
        val commissioned: LocalDate?,
        val period: LocalDateRange,
    ) : CriteriaSubject {
        override fun string(attribute: LotAttribute): String? = when (attribute) {
            LotAttribute.DEVICE_COUNTRY -> country
            LotAttribute.ENERGY_CARRIER -> carrier
            else -> null
        }

        override fun bool(attribute: LotAttribute): Boolean? = when (attribute) {
            LotAttribute.SUPPORT_PRODUCTION -> supportProduction
            LotAttribute.SUPPORT_INVESTMENT -> supportInvestment
            else -> null
        }

        override fun number(attribute: LotAttribute): BigDecimal? = if (attribute == LotAttribute.DEVICE_CAPACITY_KW) capacity else null

        override fun date(attribute: LotAttribute): LocalDate? = if (attribute == LotAttribute.COMMISSIONING_DATE) commissioned else null

        override fun taxonomyPath(attribute: LotAttribute): String? = when (attribute) {
            LotAttribute.ENERGY_SOURCE -> source
            LotAttribute.TECHNOLOGY -> technology
            else -> null
        }

        override val productionPeriod: LocalDateRange get() = period
    }

    private val sourcePaths = listOf(
        "RENEWABLE",
        "RENEWABLE.WIND",
        "RENEWABLE.WIND.WIND_ONSHORE",
        "RENEWABLE.WIND.WIND_OFFSHORE",
        "RENEWABLE.HYDRO",
        "RENEWABLE.HYDRO.HYDRO_RESERVOIR",
        "RENEWABLE.SOLAR",
        "RENEWABLE.BIOMASS.BIOGAS",
        "NON_RENEWABLE.NUCLEAR",
        "NON_RENEWABLE.FOSSIL.FOSSIL_GAS",
    )
    private val techPaths = listOf("WIND_TURBINE", "WIND_TURBINE.WIND_TURBINE_ONSHORE", "HYDRO_TURBINE", "SOLAR_PV", "THERMAL.THERMAL_CCGT")
    private val sourceCodes = sourcePaths.associateBy { it.substringAfterLast('.') }
    private val techCodes = techPaths.associateBy { it.substringAfterLast('.') }
    private val countries = listOf("FI", "SE", "NO", "DK", "DE", "FR", "ES", "NL")
    private val labelCodes = listOf("EKOENERGY", "BRA_MILJOVAL", "NATUREMADE_STAR", "OK_POWER")
    private val regions = mapOf("NORDIC" to setOf("FI", "SE", "NO", "DK", "IS"), "CWE" to setOf("DE", "FR", "NL", "BE"), "EMPTY" to emptySet())
    private val delivery = LocalDate.of(2026, 9, 15)
    private val ctx =
        BindContext(delivery, { regions[it] ?: emptySet() }, { attr, code -> if (attr == LotAttribute.ENERGY_SOURCE) sourceCodes[code] else techCodes[code] })

    private val rowArb: Arb<Row> = Arb.bind(
        Arb.element(countries).orNull(0.1),
        Arb.element(sourcePaths).orNull(0.05),
        Arb.element(techPaths).orNull(0.1),
        Arb.set(Arb.element(labelCodes), 0..3),
        Arb.boolean().orNull(0.2),
        Arb.boolean().orNull(0.2),
        Arb.int(500, 400_000).map { BigDecimal(it) }.orNull(0.1),
        Arb.int(1990, 2026).map { year -> LocalDate.of(year, (year % 12) + 1, 1) }.orNull(0.15),
        Arb.int(0, 35).map { offset -> LocalDateRange.ofMonth(YearMonth.of(2024, 1).plusMonths(offset.toLong())) },
    ) { country, source, tech, labels, sp, si, cap, comm, period ->
        Row(0, country, "ELECTRICITY", source, tech, labels, sp, si, cap, comm, period)
    }

    private val leafArb: Arb<Criterion> = Arb.choice(
        Arb.element(countries).map { Criterion.country(it) },
        Arb.set(Arb.element(countries), 1..3).map { Criterion.InSet(LotAttribute.DEVICE_COUNTRY, it) },
        Arb.element(regions.keys.toList()).map { Criterion.CountryInRegion(it) },
        Arb.element(sourceCodes.keys.toList() + "UNICORN").map { Criterion.energySource(it) },
        Arb.element(techCodes.keys.toList()).map { Criterion.technology(it) },
        Arb.set(Arb.element(labelCodes), 1..2).map { Criterion.LabelsAny(it) },
        Arb.set(Arb.element(labelCodes), 1..2).map { Criterion.LabelsAll(it) },
        Arb.boolean().map { Criterion.Equals(LotAttribute.SUPPORT_PRODUCTION, it.toString()) },
        Arb.boolean().map { Criterion.Equals(LotAttribute.SUPPORT_INVESTMENT, it.toString()) },
        Arb.int(1, 30).map { Criterion.DeviceAgeAtDelivery(it) },
        Arb.int(2024, 2026).map { Criterion.productionYear(it) },
        Arb.int(1, 24).map { Criterion.Vintage(VintageRule.MonthsBeforeDelivery(it)) },
        Arb.int(1000, 300_000).map { Criterion.NumberRange(LotAttribute.DEVICE_CAPACITY_KW, min = BigDecimal(it)) },
        Arb.int(1995, 2025).map { Criterion.DateRange(LotAttribute.COMMISSIONING_DATE, toExclusive = LocalDate.of(it, 1, 1)) },
        Arb.element(listOf(Criterion.MatchAll, Criterion.carrier("ELECTRICITY"), Criterion.carrier("GAS"))),
    )

    private fun treeArb(depth: Int): Arb<Criterion> = if (depth == 0) {
        leafArb
    } else {
        Arb.choice(
            leafArb,
            Arb.bind(treeArb(depth - 1), treeArb(depth - 1)) { a, b -> Criterion.all(a, b) },
            Arb.bind(treeArb(depth - 1), treeArb(depth - 1), treeArb(depth - 1)) { a, b, c -> Criterion.anyOf(a, b, c) },
            treeArb(depth - 1).map { Criterion.Not(it) },
        )
    }

    private lateinit var rows: List<Row>

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("product")
        dsl.execute(
            """
            CREATE TABLE etrm_product.criteria_fixture (
                id INT PRIMARY KEY, device_country TEXT, energy_carrier TEXT NOT NULL, energy_source public.ltree, technology public.ltree,
                labels TEXT[] NOT NULL, support_production BOOLEAN, support_investment BOOLEAN, device_capacity_kw NUMERIC,
                commissioning_date DATE, production_period DATERANGE NOT NULL)
            """.trimIndent(),
        )
        rows = runBlocking {
            val generated = mutableListOf<Row>()
            rowArb.samples().take(400).forEachIndexed { index, sample -> generated += sample.value.copy(id = index) }
            generated
        }
        val insert = dsl.insertInto(
            table,
            DSL.field("id"),
            DSL.field("device_country"),
            DSL.field("energy_carrier"),
            DSL.field("energy_source"),
            DSL.field("technology"),
            DSL.field("labels"),
            DSL.field("support_production"),
            DSL.field("support_investment"),
            DSL.field("device_capacity_kw"),
            DSL.field("commissioning_date"),
            DSL.field("production_period"),
        )
        rows.forEach { r ->
            insert.values(
                DSL.inline(r.id),
                DSL.inline(r.country),
                DSL.inline(r.carrier),
                r.source?.let { DSL.field("text2ltree({0})", DSL.inline(it)) } ?: DSL.inline(null as String?),
                r.technology?.let { DSL.field("text2ltree({0})", DSL.inline(it)) } ?: DSL.inline(null as String?),
                DSL.field("{0}::text[]", DSL.inline(r.labels.toTypedArray())),
                DSL.inline(r.supportProduction),
                DSL.inline(r.supportInvestment),
                DSL.inline(r.capacity),
                DSL.inline(r.commissioned),
                DSL.field("daterange({0}, {1}, '[)')", DSL.inline(r.period.start), DSL.inline(r.period.endExclusive)),
            )
        }
        insert.execute()
    }

    @Test
    fun `compiled SQL selects exactly the rows the in-memory evaluator accepts`() = runBlocking {
        checkAll(PropTestConfig(iterations = 300, seed = 20261010L), treeArb(3)) { criterion ->
            val bound = CriteriaBinder.bind(CriteriaCodec.parse(CriteriaCodec.toJson(criterion)), ctx)
            val expected = rows.filter { CriteriaEvaluator.matches(bound, it) }.map { it.id }.toSet()
            val actual = dsl.select(
                DSL.field("id", Int::class.java),
            ).from(table).where(JooqCriteriaCompiler.compile(bound, Columns)).fetchSet(0, Int::class.java)
            actual shouldBe expected
        }
    }
}
