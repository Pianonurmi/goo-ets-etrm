package com.pianonurmi.etrm.marketdata

import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqFxRateRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqImportRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqInstrumentRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqPriceIndexRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqQuoteRepository
import com.pianonurmi.etrm.marketdata.api.CreateInstrument
import com.pianonurmi.etrm.marketdata.api.CreatePriceIndex
import com.pianonurmi.etrm.marketdata.api.FixingRule
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.QuoteFlagged
import com.pianonurmi.etrm.marketdata.api.QuoteQuality
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.RecordFxRate
import com.pianonurmi.etrm.marketdata.api.RecordQuote
import com.pianonurmi.etrm.marketdata.application.MarketDataService
import com.pianonurmi.etrm.marketdata.application.MarketDataSettings
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.money.Money
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqFuturesContractRepository
import com.pianonurmi.etrm.product.api.CreateFuturesContract
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.application.FuturesContractService
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
import java.time.ZoneOffset

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarketDataServiceIT {
    private var user = Actor("analyst", "Analyst", setOf(Role.MIDDLE_OFFICE, Role.RISK), allEntities = true)
    private val published = mutableListOf<Any>()
    private var now = Instant.parse("2026-10-10T08:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }
    private lateinit var service: MarketDataService
    private lateinit var futures: FuturesContractService
    private val day = LocalDate.of(2026, 10, 9)

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("product", "marketdata")
        val dsl = TestDatabase.dsl
        val actor = ActorProvider { user }
        val catalogue = ProductCatalogueService(JooqAllowanceProductRepository(dsl))
        futures = FuturesContractService(JooqFuturesContractRepository(dsl), catalogue, actor)
        service = MarketDataService(
            JooqInstrumentRepository(dsl),
            JooqQuoteRepository(dsl),
            JooqFxRateRepository(dsl),
            JooqImportRepository(dsl),
            JooqPriceIndexRepository(dsl),
            catalogue,
            futures,
            mockk<ProductBucketQuery>(),
            MarketDataSettings(maxMovePct = BigDecimal(20)),
            actor,
            ApplicationEventPublisher { published += it },
            clock,
        )
    }

    @Test
    fun `ECB rates import once and convert directly, inversely and across`() {
        val xml = javaClass.getResourceAsStream("/marketdata/eurofxref-daily.xml")!!.readBytes()
        val first = service.importFile("ECB_DAILY_XML", "eurofxref-daily.xml", xml)
        first.accepted shouldBe 8
        first.duplicate shouldBe false
        service.importFile("ECB_DAILY_XML", "again.xml", xml).duplicate shouldBe true

        service.rate(day, "EUR", "SEK")!!.rate shouldBe BigDecimal("11.05400000")
        service.rate(day.plusDays(3), "SEK", "EUR")!!.let {
            it.rate shouldBe BigDecimal("0.0904649900")
            it.rateDate shouldBe day
        }
        service.rate(day, "SEK", "NOK")!!.path shouldBe "1/(EUR/SEK) x EUR/NOK"
        service.convert(Money("1000.00", "USD"), "EUR", day).amount shouldBe BigDecimal("858.37")
        // Rates older than the staleness limit are not used.
        service.rate(day.plusDays(30), "EUR", "SEK") shouldBe null
    }

    @Test
    fun `a corrected rate supersedes the earlier one`() {
        val d = LocalDate.of(2026, 9, 1)
        service.recordFxRate(RecordFxRate(d, "EUR", "CZK", BigDecimal("24.50")))
        service.recordFxRate(RecordFxRate(d, "EUR", "CZK", BigDecimal("24.55")))
        service.rate(d, "EUR", "CZK")!!.rate shouldBe BigDecimal("24.55000000")
        TestDatabase.dsl.fetchCount(
            TestDatabase.dsl.selectFrom(org.jooq.impl.DSL.table("etrm_marketdata.fx_rate")).where("quote_currency = 'CZK'"),
        ) shouldBe 2
    }

    @Test
    fun `settlement prices import through aliases, flag implausible moves and keep corrections as of`() {
        futures.create(
            CreateFuturesContract(
                "EEX_EUA_DEC26",
                "EUA",
                "XEEE",
                YearMonth.of(2026, 12),
                LocalDate.of(2026, 12, 21),
                LocalDate.of(2026, 12, 21),
                BigDecimal(1000),
            ),
        )
        service.createInstrument(CreateInstrument("EUA_DEC26", InstrumentKind.EUA_FUTURE, "EUA Dec-26", futuresContractCode = "EEX_EUA_DEC26")).let {
            it.allowanceProductCode shouldBe "EUA"
            it.unit shouldBe "TCO2E"
        }
        service.addAlias("EXCHANGE", "EUA Dec-26", "EUA_DEC26")
        val csv = "Date,Contract,Settlement,Currency\n2026-10-08,EUA Dec-26,72.40,EUR\n2026-10-09,EUA Dec-26,95.00,EUR\n2026-10-09,EUA Dec-99,70.00,EUR\n"
        val result = service.importFile("GENERIC_SETTLEMENT_CSV", "settle.csv", csv.toByteArray())
        result.accepted shouldBe 2
        result.suspect shouldBe 1
        result.errors.single().message shouldContain "EUA Dec-99"
        published.filterIsInstance<QuoteFlagged>().single().previous shouldBe BigDecimal("72.400000")

        // A suspect price is not used until someone else acknowledges it.
        service.latestQuote("EUA_DEC26", QuoteType.SETTLEMENT, day)!!.quoteDate shouldBe LocalDate.of(2026, 10, 8)
        val suspect = service.suspectQuotes().single()
        shouldThrow<SegregationOfDutiesException> { service.acknowledgeQuote(suspect.id, "checked with the exchange") }
        user = Actor("risk2", "Risk 2", setOf(Role.RISK), allEntities = true)
        service.acknowledgeQuote(suspect.id, "checked with the exchange").quality shouldBe QuoteQuality.ACKNOWLEDGED
        service.latestQuote("EUA_DEC26", QuoteType.SETTLEMENT, day)!!.value shouldBe BigDecimal("95.000000")

        // A correction supersedes the price; the earlier one stays visible as known before the correction.
        val before = now
        now = now.plusSeconds(60)
        service.recordQuote(RecordQuote("EUA_DEC26", day, QuoteType.SETTLEMENT, BigDecimal("75.10"), source = "EXCHANGE"))
        service.latestQuote("EUA_DEC26", QuoteType.SETTLEMENT, day)!!.value shouldBe BigDecimal("75.100000")
        service.latestQuote("EUA_DEC26", QuoteType.SETTLEMENT, day, knownAt = before)!!.value shouldBe BigDecimal("95.000000")
        service.quotes("EUA_DEC26", day, day) shouldHaveSize 1
        user = Actor("analyst", "Analyst", setOf(Role.MIDDLE_OFFICE, Role.RISK), allEntities = true)
    }

    @Test
    fun `index fixings follow the fixing rule`() {
        service.createInstrument(CreateInstrument("EUA_SPOT_IDX", InstrumentKind.EUA_SPOT, "EUA spot", allowanceProductCode = "EUA"))
        listOf(1 to "70.00", 2 to "72.00", 5 to "74.00").forEach { (d, p) ->
            service.recordQuote(RecordQuote("EUA_SPOT_IDX", LocalDate.of(2026, 9, d), QuoteType.CLOSE, BigDecimal(p)))
        }
        service.createIndex(CreatePriceIndex("EUA_DAILY", "EUA spot close", "EUA_SPOT_IDX", QuoteType.CLOSE, FixingRule.ON_DATE))
        service.createIndex(CreatePriceIndex("EUA_PREV", "EUA spot previous close", "EUA_SPOT_IDX", QuoteType.CLOSE, FixingRule.PRIOR_DAY))
        service.createIndex(CreatePriceIndex("EUA_MONTH", "EUA spot monthly average", "EUA_SPOT_IDX", QuoteType.CLOSE, FixingRule.MONTH_AVERAGE))
        service.fixing("EUA_DAILY", LocalDate.of(2026, 9, 2))!!.value shouldBe BigDecimal("72.000000")
        service.fixing("EUA_DAILY", LocalDate.of(2026, 9, 3)) shouldBe null
        service.fixing("EUA_PREV", LocalDate.of(2026, 9, 5))!!.value shouldBe BigDecimal("72.000000")
        service.fixing("EUA_MONTH", LocalDate.of(2026, 9, 30))!!.let {
            it.value shouldBe BigDecimal("72.000000")
            it.quoteIds shouldHaveSize 3
        }
        service.fixing("NO_SUCH_INDEX", day) shouldBe null
    }

    @Test
    fun `a month average is a fixing only once the month has ended`() {
        service.createInstrument(CreateInstrument("EUA_SPOT_AVG", InstrumentKind.EUA_SPOT, "EUA spot for averages", allowanceProductCode = "EUA"))
        service.createIndex(CreatePriceIndex("EUA_AVG", "EUA spot monthly average", "EUA_SPOT_AVG", QuoteType.CLOSE, FixingRule.MONTH_AVERAGE))
        listOf(1 to "70.00", 2 to "72.00").forEach { (d, p) ->
            service.recordQuote(RecordQuote("EUA_SPOT_AVG", LocalDate.of(2026, 10, d), QuoteType.CLOSE, BigDecimal(p)))
        }
        // 10 October: the month is running, so the two quotes so far are a partial average and not a fixing.
        service.fixing("EUA_AVG", LocalDate.of(2026, 10, 9)) shouldBe null
        val before = now
        now = Instant.parse("2026-11-01T08:00:00Z")
        try {
            service.fixing("EUA_AVG", LocalDate.of(2026, 10, 9))!!.value shouldBe BigDecimal("71.000000")
        } finally {
            now = before
        }
    }

    @Test
    fun `market data changes need market data roles`() {
        user = Actor("trader", "Trader", setOf(Role.TRADER), allEntities = true)
        shouldThrow<ForbiddenException> { service.recordFxRate(RecordFxRate(day, "EUR", "HUF", BigDecimal("400"))) }
        user = Actor("analyst", "Analyst", setOf(Role.MIDDLE_OFFICE, Role.RISK), allEntities = true)
        service.importFormats().map { it.code } shouldNotBe emptyList<String>()
    }
}
