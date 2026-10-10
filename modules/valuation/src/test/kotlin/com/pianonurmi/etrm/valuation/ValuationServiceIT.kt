package com.pianonurmi.etrm.valuation

import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRateView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.PriceOrigin
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.adapter.jooq.JooqValuationRepository
import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.RunKind
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import com.pianonurmi.etrm.valuation.application.ValuationService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

/** End-of-day runs against stubbed curves and trades: MtM per schedule, exceptions, sign-off and explain. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValuationServiceIT {
    private var user = "risk1"
    private val published = mutableListOf<Any>()
    private val entity = UUID.randomUUID()
    private val book = UUID.randomUUID()
    private val d0 = LocalDate.of(2026, 10, 8)
    private val d1 = LocalDate.of(2026, 10, 9)
    private val forward = mutableMapOf(d0 to BigDecimal("72.00"), d1 to BigDecimal("74.00"))
    private var euaRemaining = BigDecimal(1000)
    private lateinit var valuations: ValuationService

    private fun schedule(remaining: () -> BigDecimal, date: LocalDate, id: UUID) = mockk<DeliveryScheduleView> {
        every { this@mockk.id } returns id
        every { deliveryDate } returns date
        every { remainingQuantity } answers { remaining() }
    }

    private fun leg(
        side: Side,
        price: String,
        type: PriceType,
        currency: String,
        schedules: List<DeliveryScheduleView>,
        product: String? = "EUA",
    ) = mockk<TradeLegView> {
        every { legNo } returns 1
        every { this@mockk.side } returns side
        every { this@mockk.price } returns BigDecimal(price)
        every { priceType } returns type
        every { priceCurrency } returns Currency.getInstance(currency)
        every { allowanceProductCode } returns product
        every { bucketCode } returns null
        every { criterion } returns null
        every { priceIndex } returns null
        every { deliveries } returns schedules
    }

    private fun trade(number: String, family: ProductFamily, legs: List<TradeLegView>) = mockk<TradeView> {
        every { id } returns UUID.nameUUIDFromBytes(number.toByteArray())
        every { tradeNumber } returns number
        every { version } returns 1
        every { bookId } returns book
        every { counterpartyId } returns UUID.randomUUID()
        every { productFamily } returns family
        every { this@mockk.legs } returns legs
        every { status } returns TradeStatus.CONFIRMED
    }

    @BeforeAll
    @Suppress("LongMethod") // stubs for trades, curves and rates
    fun setUp() {
        TestDatabase.migrate("valuation")
        val euaBuy =
            trade(
                "T-EUA-BUY",
                ProductFamily.EMISSION_ALLOWANCE,
                listOf(
                    leg(
                        Side.BUY,
                        "70.00",
                        PriceType.FIXED,
                        "EUR",
                        listOf(
                            schedule({
                                euaRemaining
                            }, LocalDate.of(2026, 12, 21), UUID.randomUUID()),
                        ),
                    ),
                ),
            )
        val euaSekSell =
            trade(
                "T-EUA-SEK",
                ProductFamily.EMISSION_ALLOWANCE,
                listOf(
                    leg(
                        Side.SELL,
                        "800.00",
                        PriceType.FIXED,
                        "SEK",
                        listOf(
                            schedule({
                                BigDecimal(100)
                            }, LocalDate.of(2026, 12, 21), UUID.randomUUID()),
                        ),
                    ),
                ),
            )
        val gooWithoutBucket =
            trade(
                "T-GOO",
                ProductFamily.GUARANTEE_OF_ORIGIN,
                listOf(
                    leg(
                        Side.SELL,
                        "0.90",
                        PriceType.FIXED,
                        "EUR",
                        listOf(
                            schedule({
                                BigDecimal(500)
                            }, LocalDate.of(2026, 12, 31), UUID.randomUUID()),
                        ),
                        product = null,
                    ),
                ),
            )
        val formula =
            trade(
                "T-FORMULA",
                ProductFamily.EMISSION_ALLOWANCE,
                listOf(
                    leg(
                        Side.BUY,
                        "1.00",
                        PriceType.FORMULA,
                        "EUR",
                        listOf(
                            schedule({
                                BigDecimal(10)
                            }, LocalDate.of(2026, 12, 21), UUID.randomUUID()),
                        ),
                    ),
                ),
            )
        val trades = mockk<TradeQuery> {
            every { trades(any()) } returns listOf(euaBuy, euaSekSell, gooWithoutBucket, formula)
            every { trade(any()) } returns null
        }
        val curves = mockk<CurveQuery> {
            every { forwardPrice("EUA", any(), any(), any()) } answers {
                val asOf = thirdArg<LocalDate>()
                CurvePrice(forward.getValue(asOf), "EUR", PriceOrigin.INTERPOLATED, "EUA_FWD", UUID.nameUUIDFromBytes(asOf.toString().toByteArray()), asOf)
            }
        }
        val fx = mockk<FxRates> {
            every { rate(any(), any(), any()) } answers {
                val (date, from, to) = Triple(firstArg<LocalDate>(), secondArg<String>(), thirdArg<String>())
                when {
                    from == to -> FxRateView(date, from, to, BigDecimal.ONE, "identity", date)
                    from == "EUR" && to == "SEK" -> FxRateView(date, from, to, BigDecimal("11.00"), "EUR/SEK", date)
                    from == "SEK" && to == "EUR" -> FxRateView(
                        date,
                        from,
                        to,
                        if (date == d0) BigDecimal("0.0900000000") else BigDecimal("0.0910000000"),
                        "1/(EUR/SEK)",
                        date,
                    )
                    else -> null
                }
            }
        }
        val referenceData = mockk<ReferenceDataQuery> {
            every { requireLegalEntity(entity) } returns mockk { every { baseCurrency } returns Currency.getInstance("EUR") }
            every { requireBook(book) } returns mockk { every { bookType } returns BookType.TRADING }
        }
        valuations = ValuationService(
            JooqValuationRepository(TestDatabase.dsl),
            trades,
            curves,
            fx,
            { _, _ -> null },
            mockk(),
            referenceData,
            ActorProvider { Actor(user, user, setOf(Role.RISK), allEntities = true) },
            ApplicationEventPublisher { published += it },
            Clock.fixed(Instant.parse("2026-10-09T18:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun `runs value open schedules, need a second person and a reason for exceptions, and explain the move`() {
        user = "risk1"
        val run0 = valuations.run(entity, d0, RunKind.EOD)
        val results = valuations.results(run0.id).associateBy { it.tradeNumber }
        results.getValue("T-EUA-BUY").let {
            it.mtm shouldBe BigDecimal("2000.00") // 1000 x (72 - 70)
            it.status shouldBe ResultStatus.OK
        }
        // Sold 100 at SEK 800; market 72 EUR = SEK 792: worth +800 SEK, 72 EUR at 0.09.
        results.getValue("T-EUA-SEK").let {
            it.marketPriceContract shouldBe BigDecimal("792.000000")
            it.mtm shouldBe BigDecimal("800.00")
            it.mtmBase shouldBe BigDecimal("72.00")
        }
        results.getValue("T-GOO").status shouldBe ResultStatus.MISSING_BUCKET
        results.getValue("T-FORMULA").status shouldBe ResultStatus.EXCLUDED
        run0.exceptions shouldBe 1
        run0.totalMtmBase shouldBe BigDecimal("2072.00")
        run0.inputs shouldBe mapOf("EUA_FWD" to UUID.nameUUIDFromBytes(d0.toString().toByteArray()).toString())

        valuations.submit(run0.id).status shouldBe RunStatus.PENDING_SIGN_OFF
        shouldThrow<SegregationOfDutiesException> { valuations.signOff(run0.id, "risk1", "ok") }
        shouldThrow<ValidationException> { valuations.signOff(run0.id, "risk2", null) }
        valuations.signOff(run0.id, "risk2", "GoO trade awaiting its bucket").status shouldBe RunStatus.OFFICIAL

        // Next day: the forward rises by 2, the SEK strengthens, and 400 of the EUA purchase is delivered.
        euaRemaining = BigDecimal(600)
        val run1 = valuations.submit(valuations.run(entity, d1, RunKind.EOD).id)
        valuations.signOff(run1.id, "risk2", "GoO trade awaiting its bucket")
        published.filterIsInstance<ValuationRunSignedOff>().last().run.id shouldBe run1.id
        val explain = valuations.explain(entity, d0, d1)!!
        explain.mtmTo - explain.mtmFrom shouldBe explain.components.values.fold(BigDecimal.ZERO, BigDecimal::add)
        explain.components.getValue(ExplainComponent.DELIVERED) shouldBe BigDecimal("-800.00") // 400 x (72 - 70)
        // 600 x (74 - 72) on the purchase; the SEK sale loses 100 x (814 - 792) SEK at 0.09.
        explain.components.getValue(ExplainComponent.PRICE) shouldBe BigDecimal("1002.00")
        // The sale's later value of -1400 SEK, at 0.091 instead of 0.09.
        explain.components.getValue(ExplainComponent.FX) shouldBe BigDecimal("-1.40")
        explain.mtmTo shouldBe BigDecimal("2272.60")

        // A second official run for the same date supersedes the first.
        val rerun = valuations.submit(valuations.run(entity, d1, RunKind.ADHOC).id)
        valuations.signOff(rerun.id, "risk2", "rerun")
        valuations.run(run1.id)!!.status shouldBe RunStatus.SUPERSEDED
        valuations.official(entity, d1)!!.id shouldBe rerun.id
    }
}
