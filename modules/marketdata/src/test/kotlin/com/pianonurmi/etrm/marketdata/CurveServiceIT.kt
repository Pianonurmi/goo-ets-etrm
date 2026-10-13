package com.pianonurmi.etrm.marketdata

import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqCurveRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqFxRateRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqImportRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqInstrumentRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqPriceIndexRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqQuoteRepository
import com.pianonurmi.etrm.marketdata.api.CreateCurve
import com.pianonurmi.etrm.marketdata.api.CreateInstrument
import com.pianonurmi.etrm.marketdata.api.CurveKind
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotSignedOff
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotSubmitted
import com.pianonurmi.etrm.marketdata.api.DayCount
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.Interpolation
import com.pianonurmi.etrm.marketdata.api.PointOrigin
import com.pianonurmi.etrm.marketdata.api.PriceOrigin
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.RecordQuote
import com.pianonurmi.etrm.marketdata.api.SnapshotStatus
import com.pianonurmi.etrm.marketdata.application.CurveService
import com.pianonurmi.etrm.marketdata.application.MarketDataService
import com.pianonurmi.etrm.marketdata.application.MarketDataSettings
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqFuturesContractRepository
import com.pianonurmi.etrm.product.api.CreateFuturesContract
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductBucketView
import com.pianonurmi.etrm.product.application.FuturesContractService
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CurveServiceIT {
    private var user = "builder"
    private val published = mutableListOf<Any>()
    private var now = Instant.parse("2026-10-09T17:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?) = this

        override fun instant() = now
    }
    private lateinit var marketData: MarketDataService
    private lateinit var curves: CurveService
    private val asOf = LocalDate.of(2026, 10, 9)

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("product", "marketdata")
        val dsl = TestDatabase.dsl
        val actor = ActorProvider { Actor(user, user, setOf(Role.MIDDLE_OFFICE, Role.RISK), allEntities = true) }
        val catalogue = ProductCatalogueService(JooqAllowanceProductRepository(dsl))
        val futures = FuturesContractService(JooqFuturesContractRepository(dsl), catalogue, actor)
        val buckets = mockk<ProductBucketQuery> {
            every { requireBucket(any()) } answers {
                val bucket = firstArg<String>()
                mockk<ProductBucketView> { every { code } returns bucket }
            }
        }
        val bus = ApplicationEventPublisher { published += it }
        marketData = MarketDataService(
            JooqInstrumentRepository(dsl),
            JooqQuoteRepository(dsl),
            JooqFxRateRepository(dsl),
            JooqImportRepository(dsl),
            JooqPriceIndexRepository(dsl),
            catalogue,
            futures,
            buckets,
            MarketDataSettings(),
            actor,
            bus,
            clock,
        )
        curves = CurveService(JooqCurveRepository(dsl), JooqInstrumentRepository(dsl), JooqQuoteRepository(dsl), futures, catalogue, buckets, actor, bus, clock)

        listOf(Triple("DEC26", LocalDate.of(2026, 12, 21), "71.20"), Triple("DEC27", LocalDate.of(2027, 12, 20), "74.80")).forEach { (code, delivery, price) ->
            futures.create(CreateFuturesContract("CRV_EUA_$code", "EUA", "XEEE", YearMonth.from(delivery), delivery, delivery, BigDecimal(1000)))
            marketData.createInstrument(CreateInstrument("CRV_$code", InstrumentKind.EUA_FUTURE, "EUA $code", futuresContractCode = "CRV_EUA_$code"))
            marketData.recordQuote(RecordQuote("CRV_$code", asOf, QuoteType.SETTLEMENT, BigDecimal(price), source = "EXCHANGE"))
        }
        marketData.createInstrument(CreateInstrument("CRV_EUA_SPOT", InstrumentKind.EUA_SPOT, "EUA spot", allowanceProductCode = "EUA"))
        marketData.recordQuote(RecordQuote("CRV_EUA_SPOT", asOf, QuoteType.CLOSE, BigDecimal("70.50")))
        marketData.createInstrument(
            CreateInstrument("CRV_FI_HYDRO_2026", InstrumentKind.GOO_VINTAGE, "FI hydro 2026", bucketCode = "FI_HYDRO", productionYear = 2026),
        )
        marketData.recordQuote(RecordQuote("CRV_FI_HYDRO_2026", asOf, QuoteType.MID, BigDecimal("0.85"), source = "BROKER"))
        listOf("3M" to "2.00", "1Y" to "-0.10").forEach { (tenor, rate) ->
            marketData.createInstrument(CreateInstrument("CRV_EUR_$tenor", InstrumentKind.RATE, "EUR $tenor", unit = "PCT", tenor = tenor))
            marketData.recordQuote(RecordQuote("CRV_EUR_$tenor", asOf, QuoteType.MID, BigDecimal(rate)))
        }
        curves.createCurve(CreateCurve("EUA_FWD", CurveKind.EUA_FORWARD, "EUA forward", allowanceProductCode = "EUA"))
        curves.createCurve(CreateCurve("FI_HYDRO", CurveKind.GOO_VINTAGE, "Finnish hydro", bucketCode = "FI_HYDRO", quoteTypes = listOf(QuoteType.MID)))
    }

    private fun signOff(id: java.util.UUID, by: String) = curves.signOff(id, by)

    @Test
    fun `a forward curve is built from futures and spot, signed off by someone else, and priced between pillars`() {
        user = "builder"
        val draft = curves.build("EUA_FWD", asOf)
        draft.points.map { it.pillar } shouldBe listOf("SPOT", "CRV_EUA_DEC26", "CRV_EUA_DEC27")
        draft.points.first().pillarDate shouldBe LocalDate.of(2026, 10, 13) // two working days after a Friday
        curves.forwardPrice("EUA", LocalDate.of(2026, 12, 21), asOf) shouldBe null // nothing official yet
        curves.submit(draft.id).status shouldBe SnapshotStatus.PENDING_SIGN_OFF
        published.filterIsInstance<CurveSnapshotSubmitted>().last().snapshot.id shouldBe draft.id
        shouldThrow<SegregationOfDutiesException> { signOff(draft.id, "builder") }
        signOff(draft.id, "checker").status shouldBe SnapshotStatus.OFFICIAL
        curves.forwardPrice("EUA", LocalDate.of(2026, 12, 21), asOf)!!.let {
            it.value shouldBe BigDecimal("71.200000")
            it.origin shouldBe PriceOrigin.POINT
        }
        curves.forwardPrice("EUA", LocalDate.of(2027, 6, 21), asOf)!!.origin shouldBe PriceOrigin.INTERPOLATED
        shouldThrow<ValidationException> { curves.submit(draft.id) }

        // An override is a new version; once signed off it supersedes the first, which stays readable as known before.
        val signedOffAt = now
        now = now.plusSeconds(600)
        val corrected = curves.override(draft.id, "CRV_EUA_DEC26", BigDecimal("71.00"), "exchange corrected the settlement")
        corrected.version shouldBe 2
        corrected.points.first { it.pillar == "CRV_EUA_DEC26" }.origin shouldBe PointOrigin.OVERRIDE
        curves.submit(corrected.id)
        signOff(corrected.id, "checker")
        published.filterIsInstance<CurveSnapshotSignedOff>().last().superseded shouldBe draft.id
        curves.forwardPrice("EUA", LocalDate.of(2026, 12, 21), asOf)!!.value shouldBe BigDecimal("71.000000")
        curves.forwardPrice("EUA", LocalDate.of(2026, 12, 21), asOf, knownAt = signedOffAt)!!.value shouldBe BigDecimal("71.200000")
        curves.snapshots("EUA_FWD", asOf).map { it.status } shouldBe listOf(SnapshotStatus.SUPERSEDED, SnapshotStatus.OFFICIAL)

        // Read with carry from the next snapshot on: beyond Dec-27 the price grows instead of staying flat.
        val flat = curves.forwardPrice("EUA", LocalDate.of(2028, 6, 1), asOf)!!.value
        curves.setInterpolation("EUA_FWD", Interpolation.CARRY).interpolation shouldBe Interpolation.CARRY
        curves.forwardPrice("EUA", LocalDate.of(2028, 6, 1), asOf)!!.value shouldBe flat
        val carried = curves.build("EUA_FWD", asOf)
        carried.interpolation shouldBe Interpolation.CARRY
        curves.submit(carried.id)
        signOff(carried.id, "checker")
        (curves.forwardPrice("EUA", LocalDate.of(2028, 6, 1), asOf)!!.value > flat) shouldBe true
        shouldThrow<ValidationException> { curves.setInterpolation("FI_HYDRO", Interpolation.CARRY) }
    }

    @Test
    fun `vintage curves give allocation its marks, and a curve without prices is not built`() {
        user = "builder"
        curves.mark("FI_HYDRO", 2026, asOf) shouldBe null
        val draft = curves.build("FI_HYDRO", asOf)
        curves.submit(draft.id)
        signOff(draft.id, "checker")
        curves.mark("FI_HYDRO", 2026, asOf) shouldBe BigDecimal("0.850000")
        curves.mark("FI_HYDRO", 2027, asOf.plusDays(30)) shouldBe BigDecimal("0.850000") // nearest earlier vintage, latest official curve
        curves.mark("NORDIC_WIND", 2026, asOf) shouldBe null
        shouldThrow<ValidationException> { curves.build("FI_HYDRO", LocalDate.of(2025, 1, 2)) }
        curves.official("FI_HYDRO", asOf) shouldNotBe null
    }

    @Test
    fun `a volatility curve is built from volatility quotes on futures and read in total variance`() {
        user = "builder"
        shouldThrow<ValidationException> { marketData.recordQuote(RecordQuote("CRV_EUA_SPOT", asOf, QuoteType.VOLATILITY, BigDecimal("50"))) }
        shouldThrow<ValidationException> {
            curves.createCurve(CreateCurve("EUA_BAD", CurveKind.EUA_FORWARD, "bad", allowanceProductCode = "EUA", quoteTypes = listOf(QuoteType.VOLATILITY)))
        }
        listOf("DEC26" to "50", "DEC27" to "40").forEach { (code, vol) ->
            marketData.recordQuote(RecordQuote("CRV_$code", asOf, QuoteType.VOLATILITY, BigDecimal(vol), source = "EXCHANGE"))
        }
        // Whatever the command says, a volatility curve reads volatility quotes.
        val curve = curves.createCurve(CreateCurve("EUA_VOL", CurveKind.EUA_VOLATILITY, "EUA option volatility", allowanceProductCode = "EUA"))
        curve.quoteTypes shouldBe listOf(QuoteType.VOLATILITY)
        shouldThrow<ConflictException> { curves.createCurve(CreateCurve("EUA_VOL2", CurveKind.EUA_VOLATILITY, "again", allowanceProductCode = "EUA")) }
        curves.volatility("EUA", LocalDate.of(2026, 12, 21), asOf) shouldBe null

        val draft = curves.build("EUA_VOL", asOf)
        draft.points.map { Triple(it.pillar, it.pillarDate, it.price) } shouldBe listOf(
            Triple("CRV_EUA_DEC26", LocalDate.of(2026, 12, 21), BigDecimal("50.000000")),
            Triple("CRV_EUA_DEC27", LocalDate.of(2027, 12, 20), BigDecimal("40.000000")),
        )
        curves.submit(draft.id)
        signOff(draft.id, "checker")
        curves.volatility("EUA", LocalDate.of(2026, 12, 21), asOf)!!.let {
            it.value shouldBe BigDecimal("50.000000")
            it.origin shouldBe PriceOrigin.POINT
        }
        // Total variance 50² × 73 and 40² × 437 days, linear in days to 255: √(440 850 / 255).
        curves.volatility("EUA", LocalDate.of(2027, 6, 21), asOf)!!.let {
            it.value shouldBe BigDecimal("41.579124")
            it.origin shouldBe PriceOrigin.INTERPOLATED
        }
        curves.volatility("EUA", LocalDate.of(2026, 11, 20), asOf)!!.value shouldBe BigDecimal("50.000000")
        curves.volatility("EUA", LocalDate.of(2028, 6, 1), asOf)!!.origin shouldBe PriceOrigin.EXTRAPOLATED
        curves.forwardPrice("EUA", LocalDate.of(2026, 12, 21), asOf)!!.curveCode shouldBe "EUA_FWD"
    }

    @Test
    fun `a discount curve is built from rate instruments by tenor and gives discount factors once official`() {
        user = "builder"
        shouldThrow<ValidationException> {
            marketData.createInstrument(CreateInstrument("CRV_BAD", InstrumentKind.RATE, "bad", unit = "PCT", tenor = "3Q"))
        }
        shouldThrow<ValidationException> {
            marketData.recordQuote(RecordQuote("CRV_EUA_SPOT", asOf, QuoteType.MID, BigDecimal("-1")))
        }
        curves.createCurve(CreateCurve("EUR_DISC", CurveKind.DISCOUNT, "EUR discounting", currency = "EUR")).dayCount shouldBe DayCount.ACT_360
        shouldThrow<ConflictException> { curves.createCurve(CreateCurve("EUR_DISC2", CurveKind.DISCOUNT, "again", currency = "EUR")) }
        curves.discountFactor("EUR", asOf.plusDays(90), asOf) shouldBe DiscountFactor.NONE

        val draft = curves.build("EUR_DISC", asOf)
        draft.points.map { Triple(it.pillar, it.pillarDate, it.price) } shouldBe listOf(
            Triple("3M", LocalDate.of(2027, 1, 9), BigDecimal("2.000000")),
            Triple("1Y", LocalDate.of(2027, 10, 9), BigDecimal("-0.100000")),
        )
        draft.dayCount shouldBe DayCount.ACT_360
        curves.submit(draft.id)
        signOff(draft.id, "checker")
        curves.discountFactor("EUR", LocalDate.of(2027, 1, 9), asOf).let {
            it.days shouldBe 92
            it.value shouldBe BigDecimal("0.9949148795")
            it.snapshotId shouldBe draft.id
        }
        // Seen from a later date, the same snapshot discounts over fewer days.
        curves.discountFactor(draft.id, asOf.plusDays(30), LocalDate.of(2027, 1, 9)).days shouldBe 62
        curves.discountFactor("SEK", LocalDate.of(2027, 1, 9), asOf) shouldBe DiscountFactor.NONE
    }
}
