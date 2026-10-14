package com.pianonurmi.etrm.valuation

import com.pianonurmi.etrm.allocation.api.InventoryBucketValue
import com.pianonurmi.etrm.allocation.api.PortfolioValue
import com.pianonurmi.etrm.allocation.api.PortfolioValueQuery
import com.pianonurmi.etrm.allocation.api.RealisedGiveAway
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.DiscountFactors
import com.pianonurmi.etrm.marketdata.api.FxRateView
import com.pianonurmi.etrm.marketdata.api.FixingView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.PriceOrigin
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.OptionTerms
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.adapter.jooq.JooqValuationRepository
import com.pianonurmi.etrm.valuation.api.ExplainComponent
import com.pianonurmi.etrm.valuation.api.PortfolioCause
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
import org.springframework.beans.factory.ObjectProvider
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
    private lateinit var referenceData: ReferenceDataQuery
    private lateinit var trades: TradeQuery
    private lateinit var curves: CurveQuery
    private lateinit var fx: FxRates
    private val published = mutableListOf<Any>()
    private val entity = UUID.randomUUID()
    private val book = UUID.randomUUID()
    private val d0 = LocalDate.of(2026, 10, 8)
    private val d1 = LocalDate.of(2026, 10, 9)
    private val forward = mutableMapOf(d0 to BigDecimal("72.00"), d1 to BigDecimal("74.00"))
    private val vols = mutableMapOf<LocalDate, BigDecimal>()
    private var euaRemaining = BigDecimal(1000)
    private lateinit var valuations: ValuationService

    /** Discount rates (percent, ACT/360, simple) by curve date; none in most tests, so their values stay undiscounted. */
    private val discountRates = mutableMapOf<LocalDate, BigDecimal>()
    private val discounting = object : DiscountFactors {
        private fun snapshotOf(date: LocalDate) = UUID.nameUUIDFromBytes("disc-$date".toByteArray())

        private fun factor(rate: BigDecimal, from: LocalDate, payDate: LocalDate, snapshot: UUID): DiscountFactor {
            val days = java.time.temporal.ChronoUnit.DAYS.between(from, payDate)
            val growth = BigDecimal.ONE + rate.divide(BigDecimal(100)) * BigDecimal(days).divide(BigDecimal(360), java.math.MathContext.DECIMAL128)
            val value = BigDecimal.ONE.divide(growth, java.math.MathContext.DECIMAL128).setScale(10, java.math.RoundingMode.HALF_EVEN)
            return DiscountFactor(value, rate, days, PriceOrigin.INTERPOLATED, "EUR_DISC", snapshot, from)
        }

        override fun discountFactor(currency: String, payDate: LocalDate, asOf: LocalDate, knownAt: Instant?): DiscountFactor =
            discountRates[asOf]?.let { factor(it, asOf, payDate, snapshotOf(asOf)) } ?: DiscountFactor.NONE

        override fun discountFactor(snapshotId: UUID, from: LocalDate, payDate: LocalDate): DiscountFactor =
            factor(discountRates.entries.first { snapshotOf(it.key) == snapshotId }.value, from, payDate, snapshotId)
    }

    private fun schedule(remaining: () -> BigDecimal, date: LocalDate, id: UUID, payment: LocalDate? = null) = mockk<DeliveryScheduleView> {
        every { this@mockk.id } returns id
        every { deliveryDate } returns date
        every { paymentDate } returns payment
        every { remainingQuantity } answers { remaining() }
    }

    private fun leg(
        side: Side,
        price: String,
        type: PriceType,
        currency: String,
        schedules: List<DeliveryScheduleView>,
        product: String? = "EUA",
        futures: String? = null,
        option: OptionTerms? = null,
        quantity: String = "0",
        index: String? = null,
    ) = mockk<TradeLegView> {
        every { id } returns UUID.randomUUID()
        every { legNo } returns 1
        every { this@mockk.option } returns option
        every { expiry } returns null
        every { this@mockk.quantity } returns BigDecimal(quantity)
        every { this@mockk.side } returns side
        every { this@mockk.price } returns BigDecimal(price)
        every { priceType } returns type
        every { priceCurrency } returns Currency.getInstance(currency)
        every { allowanceProductCode } returns product
        every { bucketCode } returns null
        every { criterion } returns null
        every { priceIndex } returns index
        every { deliveries } returns schedules
        every { futuresContractCode } returns futures
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
            every { volatility("EUA", any(), any(), any()) } answers {
                val asOf = thirdArg<LocalDate>()
                vols[asOf]?.let { CurvePrice(it, "EUR", PriceOrigin.INTERPOLATED, "EUA_VOL", UUID.nameUUIDFromBytes("vol-$asOf".toByteArray()), asOf) }
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
        this.referenceData = referenceData
        this.trades = trades
        this.curves = curves
        this.fx = fx
        valuations = ValuationService(
            JooqValuationRepository(TestDatabase.dsl),
            trades,
            curves,
            fx,
            { _, _ -> null },
            discounting,
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

    @Test
    fun `a run keeps its portfolio value, and sign-off explains its move by cause`() {
        user = "risk1"
        val first = LocalDate.of(2026, 10, 6)
        val second = LocalDate.of(2026, 10, 7)
        forward[first] = BigDecimal("70.00")
        forward[second] = BigDecimal("71.00")

        fun value(date: LocalDate, perMwh: String, held: Long, giveAway: String) = PortfolioValue(
            holderEntityId = entity,
            asOf = date,
            computedAt = Instant.parse("2026-10-09T18:00:00Z"),
            stamp = "s-$date",
            currency = "EUR",
            heldMwh = held,
            inventoryValueEur = (BigDecimal(perMwh) * BigDecimal(held)).setScale(2),
            allocatedMwh = 100,
            shortfallMwh = 0,
            deliveredValueEur = BigDecimal("200.00"),
            giveAwayEur = BigDecimal(giveAway),
            giveAwayMwh = 50,
            transferFeesEur = BigDecimal("1.00"),
            expiryHaircutEur = BigDecimal.ZERO.setScale(2),
            netEur = (BigDecimal(perMwh) * BigDecimal(held)).setScale(2) - BigDecimal(giveAway) - BigDecimal("1.00"),
            unpricedMwh = 0,
            byBucket = listOf(InventoryBucketValue("NORDIC_HYDRO", 2026, held, (BigDecimal(perMwh) * BigDecimal(held)).setScale(2), 100)),
            byObligation = emptyList(),
        )
        val allocation = mockk<PortfolioValueQuery> {
            every { value(entity, first) } returns value(first, "2.00", 500, "30.00")
            every { value(entity, second) } returns value(second, "2.20", 600, "45.00")
            every { realisedGiveAway(entity, any(), any()) } answers {
                RealisedGiveAway(entity, secondArg(), thirdArg(), BigDecimal("12.50"), emptyList())
            }
        }
        val withPortfolio = ValuationService(
            JooqValuationRepository(TestDatabase.dsl),
            trades,
            curves,
            fx,
            { _, _ -> null },
            discounting,
            mockk(),
            referenceData,
            ActorProvider { Actor(user, user, setOf(Role.RISK), allEntities = true) },
            ApplicationEventPublisher { },
            Clock.fixed(Instant.parse("2026-10-09T18:00:00Z"), ZoneOffset.UTC),
            mockk<ObjectProvider<PortfolioValueQuery>> { every { ifAvailable } returns allocation },
        )
        val run0 = withPortfolio.submit(withPortfolio.run(entity, first, RunKind.EOD).id)
        user = "risk2"
        withPortfolio.signOff(run0.id, "risk2", "checked")
        user = "risk1"
        val run1 = withPortfolio.submit(withPortfolio.run(entity, second, RunKind.EOD).id)
        user = "risk2"
        withPortfolio.signOff(run1.id, "risk2", "checked")

        val bridge = withPortfolio.portfolio(run1.id)!!
        bridge.inventoryValue shouldBe BigDecimal("1320.00")
        // GoO trades without a bucket have no MtM: the value is the book's own.
        bridge.portfolioValue shouldBe bridge.inventoryValue + bridge.tradeMtm - bridge.giveAway - bridge.transferFees - bridge.expiryHaircut
        bridge.priorRunId shouldBe run0.id
        val before = withPortfolio.portfolio(run0.id)!!
        // 500 MWh held in both runs gained 0.20 each; 100 MWh came in at 2.20; 15 EUR more given away.
        bridge.explain[PortfolioCause.INVENTORY_PRICE] shouldBe BigDecimal("100.00")
        bridge.explain[PortfolioCause.INVENTORY_IN_OUT] shouldBe BigDecimal("220.00")
        bridge.explain[PortfolioCause.GIVE_AWAY] shouldBe BigDecimal("-15.00")
        bridge.explain.values.fold(BigDecimal.ZERO, BigDecimal::add) shouldBe bridge.portfolioValue - before.portfolioValue
        bridge.explain[PortfolioCause.UNEXPLAINED] shouldBe BigDecimal("0.00")

        val period = withPortfolio.portfolioExplain(entity, first, second)
        period.steps.map { it.runId } shouldBe listOf(run1.id)
        period.totals[PortfolioCause.INVENTORY_PRICE] shouldBe BigDecimal("100.00")
        period.realisedGiveAway shouldBe BigDecimal("12.50")
        // Kept with the run: the stored rows cannot be changed.
        shouldThrow<Exception> {
            TestDatabase.dsl.execute("UPDATE etrm_valuation.portfolio_value SET give_away = 0 WHERE run_id = '${run1.id}'")
        }
    }

    /** A forward paid two days after delivery and an exchange future, valued with [discounting]. */
    @Test
    fun `an index leg past its delivery without a fixing is MISSING_PRICE, not valued at the forward`() {
        user = "risk1"
        val valuationDate = LocalDate.of(2026, 10, 12)
        forward[valuationDate] = BigDecimal("74.00")
        val indexEntity = entity
        fun indexSale(number: String, delivery: LocalDate) = trade(
            number,
            ProductFamily.EMISSION_ALLOWANCE,
            listOf(
                leg(
                    Side.SELL,
                    "-1.50", // a spread below the index
                    PriceType.INDEX,
                    "EUR",
                    listOf(schedule({ BigDecimal(100) }, delivery, UUID.randomUUID(), payment = delivery)),
                    index = "EUA_SPOT",
                ),
            ),
        )
        val fixings = mutableMapOf<LocalDate, BigDecimal>()
        val book = mockk<TradeQuery> {
            every { trades(any()) } returns listOf(
                indexSale("T-IDX-DUE", LocalDate.of(2026, 10, 1)),
                indexSale("T-IDX-TODAY", valuationDate),
                indexSale("T-IDX-LATER", LocalDate.of(2026, 12, 21)),
            )
            every { trade(any()) } returns null
        }
        fun service() = ValuationService(
            JooqValuationRepository(TestDatabase.dsl),
            book,
            curves,
            fx,
            { code, date -> fixings[date]?.let { FixingView(code, date, it, "EUR", emptyList()) } },
            discounting,
            mockk(),
            referenceData,
            ActorProvider { Actor(user, user, setOf(Role.RISK), allEntities = true) },
            ApplicationEventPublisher { },
            Clock.fixed(Instant.parse("2026-10-09T18:00:00Z"), ZoneOffset.UTC),
        )

        // Delivered on 1 October and the fixing is overdue: no value, rather than the forward standing in for it.
        val without = service().let { s -> s.results(s.run(indexEntity, valuationDate, RunKind.EOD).id).associateBy { it.tradeNumber } }
        without.getValue("T-IDX-DUE").let {
            it.status shouldBe ResultStatus.MISSING_PRICE
            it.mtm shouldBe null
            it.note shouldBe "No fixing of EUA_SPOT for 2026-10-01"
        }
        // A fixing not yet published on the delivery day, or a delivery still ahead, is valued at the forward.
        without.getValue("T-IDX-TODAY").status shouldBe ResultStatus.OK
        without.getValue("T-IDX-LATER").status shouldBe ResultStatus.OK

        // Once the fixing is there the leg is valued at fixing plus spread: sold at 71 - 1.50 against a forward of 74.
        fixings[LocalDate.of(2026, 10, 1)] = BigDecimal("71.00")
        val with = service().let { s -> s.results(s.run(indexEntity, valuationDate, RunKind.ADHOC).id).associateBy { it.tradeNumber } }
        with.getValue("T-IDX-DUE").let {
            it.status shouldBe ResultStatus.OK
            it.contractPrice!!.compareTo(BigDecimal("69.50")) shouldBe 0
            it.mtm!!.compareTo(BigDecimal("-450.00")) shouldBe 0 // 100 x (69.50 - 74.00): sold below the forward
        }
    }

    private fun discountedBook(): ValuationService {
        val forwardBuy = trade(
            "T-DISC-FWD",
            ProductFamily.EMISSION_ALLOWANCE,
            listOf(
                leg(
                    Side.BUY,
                    "70.00",
                    PriceType.FIXED,
                    "EUR",
                    listOf(schedule({ BigDecimal(1000) }, LocalDate.of(2026, 12, 21), UUID.randomUUID(), payment = LocalDate.of(2026, 12, 23))),
                ),
            ),
        )
        val futureBuy = trade(
            "T-DISC-FUT",
            ProductFamily.EMISSION_ALLOWANCE,
            listOf(
                leg(
                    Side.BUY,
                    "70.00",
                    PriceType.FIXED,
                    "EUR",
                    listOf(
                        schedule({
                            BigDecimal(10)
                        }, LocalDate.of(2026, 12, 21), UUID.randomUUID()),
                    ),
                    futures = "EUAZ26",
                ),
            ),
        )
        val book = mockk<TradeQuery> {
            every { trades(any()) } returns listOf(forwardBuy, futureBuy)
            every { trade(any()) } returns null
        }
        return ValuationService(
            JooqValuationRepository(TestDatabase.dsl),
            book,
            curves,
            fx,
            { _, _ -> null },
            discounting,
            mockk(),
            referenceData,
            ActorProvider { Actor(user, user, setOf(Role.RISK), allEntities = true) },
            ApplicationEventPublisher { },
            Clock.fixed(Instant.parse("2026-10-09T18:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun `MtM is discounted to the payment date, futures are not, and the explain separates time and rates`() {
        user = "risk1"
        val first = LocalDate.of(2026, 10, 1)
        val second = LocalDate.of(2026, 10, 2)
        forward[first] = BigDecimal("72.00")
        forward[second] = BigDecimal("73.00")
        discountRates[first] = BigDecimal("3.00")
        discountRates[second] = BigDecimal("3.50")
        val discounted = discountedBook()
        val run0 = discounted.run(entity, first, RunKind.EOD)
        discounted.results(run0.id).associateBy { it.tradeNumber }.let { results ->
            // 1000 x (72 - 70), paid 83 days later at 3 % ACT/360.
            results.getValue("T-DISC-FWD").let {
                it.payDate shouldBe LocalDate.of(2026, 12, 23)
                it.discountFactor shouldBe BigDecimal("0.9931308450")
                it.mtmUndiscounted shouldBe BigDecimal("2000.00")
                it.mtm shouldBe BigDecimal("1986.26")
                it.discountCurveCode shouldBe "EUR_DISC"
            }
            results.getValue("T-DISC-FUT").let {
                it.discountFactor!!.compareTo(BigDecimal.ONE) shouldBe 0
                it.discountSnapshotId shouldBe null
                it.mtm shouldBe BigDecimal("20.00")
            }
        }
        run0.inputs.keys shouldBe setOf("EUA_FWD", "EUR_DISC")
        discounted.submit(run0.id)
        discounted.signOff(run0.id, "risk2", null)

        val run1 = discounted.submit(discounted.run(entity, second, RunKind.EOD).id)
        discounted.signOff(run1.id, "risk2", null)
        val explain = discounted.explain(entity, first, second)!!
        explain.mtmFrom shouldBe BigDecimal("2006.26")
        explain.mtmTo shouldBe BigDecimal("3006.27")
        // TIME: the first curve a day closer to payment; PRICE: the forward's move at that rolled factor (plus the
        // future's undiscounted 10); RATES: the curve's move from 3 % to 3.5 %.
        explain.components.getValue(ExplainComponent.TIME) shouldBe BigDecimal("0.16")
        explain.components.getValue(ExplainComponent.PRICE) shouldBe BigDecimal("1003.21")
        explain.components.getValue(ExplainComponent.RATES) shouldBe BigDecimal("-3.37")
        explain.mtmTo - explain.mtmFrom shouldBe explain.components.values.fold(BigDecimal.ZERO, BigDecimal::add)
    }

    /** A bought call on EUAZ26 expiring on [expiry] and a sold put that expired before the runs, valued with [discounting]. */
    private fun optionBook(expiry: LocalDate): Pair<TradeView, ValuationService> {
        val call = trade(
            "T-OPT-CALL",
            ProductFamily.EMISSION_ALLOWANCE,
            listOf(
                leg(
                    Side.BUY,
                    "6.00",
                    PriceType.FIXED,
                    "EUR",
                    emptyList(),
                    option = OptionTerms(OptionType.CALL, BigDecimal("75.00"), expiry, "EUAZ26"),
                    quantity = "10000",
                ),
            ),
        )
        val expired = trade(
            "T-OPT-OLD",
            ProductFamily.EMISSION_ALLOWANCE,
            listOf(
                leg(
                    Side.SELL,
                    "2.00",
                    PriceType.FIXED,
                    "EUR",
                    emptyList(),
                    option = OptionTerms(OptionType.PUT, BigDecimal("60.00"), LocalDate.of(2026, 10, 9), "EUAZ26"),
                    quantity = "1000",
                ),
            ),
        )
        val book = mockk<TradeQuery> {
            every { trades(any()) } returns listOf(call, expired)
            every { trade(any()) } returns null
        }
        val futures = mockk<FuturesContractQuery> {
            every { futuresContract("EUAZ26") } returns mockk {
                every { code } returns "EUAZ26"
                every { deliveryDate } returns LocalDate.of(2026, 12, 21)
            }
        }
        val options = ValuationService(
            JooqValuationRepository(TestDatabase.dsl),
            book,
            curves,
            fx,
            { _, _ -> null },
            discounting,
            mockk(),
            referenceData,
            ActorProvider { Actor(user, user, setOf(Role.RISK), allEntities = true) },
            ApplicationEventPublisher { },
            Clock.fixed(Instant.parse("2026-10-13T18:00:00Z"), ZoneOffset.UTC),
            futures = futures,
        )
        return call to options
    }

    @Test
    fun `an option is valued by Black-76 until it expires and its move is explained by price, volatility and time`() {
        user = "risk1"
        val first = LocalDate.of(2026, 10, 12)
        val second = LocalDate.of(2026, 10, 13)
        forward[first] = BigDecimal("72.00")
        forward[second] = BigDecimal("74.00")
        vols[first] = BigDecimal("50.00")
        vols[second] = BigDecimal("45.00")
        discountRates[first] = BigDecimal("3.00")
        discountRates[second] = BigDecimal("3.50")
        val expiry = LocalDate.of(2026, 12, 14)
        val (call, options) = optionBook(expiry)
        val run0 = options.run(entity, first, RunKind.EOD)
        val result = options.results(run0.id).single()
        result.tradeNumber shouldBe "T-OPT-CALL"
        result.scheduleId shouldBe call.legs.single().id
        result.deliveryDate shouldBe expiry
        result.payDate shouldBe expiry
        result.contractPrice shouldBe BigDecimal("6.000000")
        result.status shouldBe ResultStatus.OK
        val option = result.option!!
        option.underlyingPrice shouldBe BigDecimal("72.000000")
        option.volatility shouldBe BigDecimal("50.000000")
        // 63 days to expiry, ACT/365.
        option.timeToExpiry shouldBe BigDecimal("0.17260274")
        option.volCurveCode shouldBe "EUA_VOL"
        // Out of the money but with two months at 50 %: worth less than the premium paid, delta under a half.
        (result.marketPriceContract!! > BigDecimal.ZERO && result.marketPriceContract < BigDecimal("6.00")) shouldBe true
        (option.delta > BigDecimal("0.3") && option.delta < BigDecimal("0.5")) shouldBe true
        (option.vega > BigDecimal.ZERO && option.gamma > BigDecimal.ZERO) shouldBe true
        result.mtm shouldBe (BigDecimal(10000) * (result.marketPriceContract - BigDecimal("6.00"))).setScale(2, java.math.RoundingMode.HALF_EVEN)
        result.discountCurveCode shouldBe "EUR_DISC"
        (result.mtmUndiscounted!! > result.mtm!!) shouldBe true
        run0.inputs.keys shouldBe setOf("EUA_FWD", "EUR_DISC", "EUA_VOL")
        options.submit(run0.id)
        options.signOff(run0.id, "risk2", null)

        // Without an official volatility the option is an exception, not a linear value.
        vols.remove(second)
        options.results(options.run(entity, second, RunKind.ADHOC).id).single().status shouldBe ResultStatus.MISSING_PRICE
        vols[second] = BigDecimal("45.00")
        val run1 = options.submit(options.run(entity, second, RunKind.EOD).id)
        options.signOff(run1.id, "risk2", null)
        val explain = options.explain(entity, first, second)!!
        // The future rose by 2 (gain), volatility fell by 5 points (loss), a day passed (decay), rates rose.
        (explain.components.getValue(ExplainComponent.PRICE) > BigDecimal.ZERO) shouldBe true
        (explain.components.getValue(ExplainComponent.VOL) < BigDecimal.ZERO) shouldBe true
        (explain.components.getValue(ExplainComponent.TIME) < BigDecimal.ZERO) shouldBe true
        (explain.components.getValue(ExplainComponent.RATES) < BigDecimal.ZERO) shouldBe true
        // Revalued step by step, so nothing is left unexplained but rounding.
        (explain.components[ExplainComponent.UNEXPLAINED]?.abs() ?: BigDecimal.ZERO) shouldBe BigDecimal.ZERO
        explain.mtmTo - explain.mtmFrom shouldBe explain.components.values.fold(BigDecimal.ZERO, BigDecimal::add)
    }
}
