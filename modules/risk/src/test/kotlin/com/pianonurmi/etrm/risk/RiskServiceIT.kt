package com.pianonurmi.etrm.risk

import com.pianonurmi.etrm.allocation.api.DeliveryGiveAway
import com.pianonurmi.etrm.allocation.api.GooBasis
import com.pianonurmi.etrm.allocation.api.GooConcentration
import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.allocation.api.SaleProjection
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.MarginCallQuery
import com.pianonurmi.etrm.clearing.api.MarginPostedView
import com.pianonurmi.etrm.clearing.fixtures.NoClearing
import com.pianonurmi.etrm.clearing.fixtures.NoMarginCalls
import com.pianonurmi.etrm.marketdata.api.FxRateView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.Providers
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CounterpartyView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.adapter.jooq.JooqRiskRepository
import com.pianonurmi.etrm.risk.api.BreachStatus
import com.pianonurmi.etrm.risk.api.CreditSupportKind
import com.pianonurmi.etrm.risk.api.LimitBreachResolved
import com.pianonurmi.etrm.risk.api.LimitBreached
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.RequestCreditSupport
import com.pianonurmi.etrm.risk.api.RequestLimit
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.UtilisationStatus
import com.pianonurmi.etrm.risk.application.CreditLimitCarryOver
import com.pianonurmi.etrm.risk.application.RiskService
import com.pianonurmi.etrm.settlement.api.CounterpartyBalance
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementReports
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.FindingSeverity
import com.pianonurmi.etrm.trade.api.OptionTerms
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.OptionValuation
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

/** Limits under four eyes, credit exposure, breaches and the pre-trade check against the risk schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskServiceIT {
    private val today = LocalDate.of(2026, 10, 10)
    private val clock = Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC)
    private var user = "risk1"
    private val entity = UUID.randomUUID()
    private val book = UUID.randomUUID()
    private val parent = UUID.randomUUID()
    private val counterparty = UUID.randomUUID()
    private val unlimited = UUID.randomUUID()
    private var netting = false

    /** EUR to USD; null means there is no USD rate at all. */
    private var eurUsd: BigDecimal? = BigDecimal("1.10")
    private val fx = mockk<FxRates> {
        every { rate(any(), any(), any()) } answers {
            val (date, from, to) = args.let { Triple(it[0] as LocalDate, it[1] as String, it[2] as String) }
            when {
                from == "EUR" && to == "USD" -> eurUsd?.let { FxRateView(date, from, to, it, "DIRECT", date) }
                from == "USD" && to == "EUR" -> eurUsd?.let {
                    FxRateView(date, from, to, BigDecimal.ONE.divide(it, 6, RoundingMode.HALF_EVEN), "INVERSE", date)
                }
                else -> null
            }
        }
    }
    private val openTrades = mutableListOf<TradeView>()
    private val extraBalances = mutableListOf<CounterpartyBalance>()

    /** No product buckets: limits in these tests name none outside the GoO cases, which bring their own. */
    private val noBuckets = mockk<ProductBucketQuery> { every { buckets(true) } returns emptyList() }
    private val published = mutableListOf<Any>()
    private lateinit var risk: RiskService
    private lateinit var withGoo: (ObjectProvider<GooRiskQuery>, ProductBucketQuery) -> RiskService
    private lateinit var withClearing: (ClearingQuery, MarginCallQuery) -> RiskService
    private lateinit var carryOver: CreditLimitCarryOver

    private fun counterpartyView(id: UUID, code: String, parentId: UUID?, limit: BigDecimal? = null) = mockk<CounterpartyView> {
        every { this@mockk.id } returns id
        every { this@mockk.code } returns code
        every { counterpartyType } returns CounterpartyType.EXTERNAL
        every { nettingAgreed } answers { netting }
        every { parentCounterpartyId } returns parentId
        every { creditLimit } returns limit
        every { settlementCurrency } returns Currency.getInstance("EUR")
    }

    /** An open EUA purchase of [quantity] t at 70 EUR delivering on [delivery]. */
    private fun trade(quantity: Int, delivery: LocalDate, cp: UUID = counterparty, id: UUID = UUID.randomUUID()): TradeView {
        val schedule = mockk<DeliveryScheduleView> {
            every { remainingQuantity } returns BigDecimal(quantity)
            every { deliveryDate } returns delivery
        }
        val leg = mockk<TradeLegView> {
            every { option } returns null
            every { side } returns Side.BUY
            every { priceType } returns PriceType.FIXED
            every { price } returns BigDecimal("70")
            every { priceCurrency } returns Currency.getInstance("EUR")
            every { allowanceProductCode } returns "EUA"
            every { deliveries } returns listOf(schedule)
        }
        return mockk {
            every { this@mockk.id } returns id
            every { legalEntityId } returns entity
            every { bookId } returns book
            every { counterpartyId } returns cp
            every { productFamily } returns ProductFamily.EMISSION_ALLOWANCE
            every { legs } returns listOf(leg)
        }
    }

    /** An open EUA option bought on the clearing house, [quantity] t of the underlying, expiring on [expiry]. */
    private fun option(quantity: Int, type: OptionType, expiry: LocalDate): TradeView {
        val leg = mockk<TradeLegView> {
            every { option } returns OptionTerms(type, BigDecimal("75"), expiry, "EUAZ27")
            every { this@mockk.expiry } returns null
            every { id } returns UUID.randomUUID()
            every { side } returns Side.BUY
            every { this@mockk.quantity } returns BigDecimal(quantity)
            every { priceCurrency } returns Currency.getInstance("EUR")
            every { allowanceProductCode } returns "EUA"
        }
        return mockk {
            every { id } returns UUID.randomUUID()
            every { tradeNumber } returns "T-OPT"
            every { legalEntityId } returns entity
            every { bookId } returns book
            every { counterpartyId } returns unlimited
            every { productFamily } returns ProductFamily.EMISSION_ALLOWANCE
            every { legs } returns listOf(leg)
        }
    }

    private fun mtm(tradeId: UUID, value: String) = mockk<ValuationResultView> {
        every { this@mockk.tradeId } returns tradeId
        every { counterpartyId } returns counterparty
        every { status } returns ResultStatus.OK
        every { mtmBase } returns BigDecimal(value)
    }

    @BeforeEach
    @Suppress("LongMethod") // stubs for the modules risk reads from
    fun setUp() {
        TestDatabase.migrate("risk")
        user = "risk1"
        netting = false
        eurUsd = BigDecimal("1.10")
        openTrades.clear()
        extraBalances.clear()
        published.clear()
        val counterparties = listOf(
            counterpartyView(parent, "PARENT", null),
            counterpartyView(counterparty, "CP", parent),
            counterpartyView(unlimited, "NOLIMIT", null, BigDecimal("250000")),
        )
        val referenceData = mockk<ReferenceDataQuery>(relaxed = true) {
            every { counterparties() } returns counterparties
            every { counterparty(any()) } answers { counterparties.firstOrNull { it.id == firstArg() } }
            every { requireLegalEntity(entity) } returns mockk(relaxed = true) {
                every { id } returns entity
                every { baseCurrency } returns Currency.getInstance("EUR")
            }
        }
        val trades = mockk<TradeQuery> { every { trades(any()) } answers { openTrades.toList() } }
        val runId = UUID.randomUUID()
        val existing = trade(1000, LocalDate.of(2026, 12, 10))
        val valuations = mockk<ValuationQuery> {
            every { official(entity, any(), true) } returns mockk<ValuationRunView> { every { id } returns runId }
            every { results(runId, null) } returns listOf(mtm(existing.id, "300"), mtm(UUID.randomUUID(), "-100"))
            // Calls are valued at a delta of 0.4; puts cannot be valued (no volatility curve).
            every { optionValuation(entity, any(), any()) } answers {
                thirdArg<TradeLegView>().option?.takeIf { it.optionType == OptionType.CALL }?.let {
                    OptionValuation(
                        "CALL",
                        it.strike,
                        "EUAZ27",
                        BigDecimal("72"),
                        BigDecimal("45"),
                        BigDecimal("0.5"),
                        BigDecimal("0.4"),
                        BigDecimal("0.01"),
                        BigDecimal("0.2"),
                        "EUA_VOL",
                        UUID(0, 1),
                    )
                }
            }
        }
        openTrades += existing
        val settlement = mockk<SettlementReports> {
            every { counterpartyBalances(entity) } answers {
                listOf(
                    CounterpartyBalance(counterparty, SettlementDirection.RECEIVABLE, "EUR", BigDecimal("150"), BigDecimal("50")),
                    CounterpartyBalance(counterparty, SettlementDirection.PAYABLE, "EUR", BigDecimal("50"), BigDecimal.ZERO),
                ) + extraBalances
            }
        }
        val events = ApplicationEventPublisher { published += it }
        val actors = ActorProvider { Actor(user, user, setOf(Role.RISK), allEntities = true) }
        val repository = JooqRiskRepository(TestDatabase.dsl)
        withGoo = { goo, buckets ->
            RiskService(
                repository,
                referenceData,
                trades,
                valuations,
                settlement,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                fx,
                actors,
                events,
                clock,
                Providers.none(),
                goo,
                buckets,
                Savepoints.NONE,
                NoClearing,
                NoMarginCalls,
            )
        }
        withClearing = { clearing, margin ->
            RiskService(
                repository,
                referenceData,
                trades,
                valuations,
                settlement,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                fx,
                actors,
                events,
                clock,
                Providers.none(),
                Providers.none(),
                noBuckets,
                Savepoints.NONE,
                clearing,
                margin,
            )
        }
        risk = withGoo(Providers.none(), noBuckets)
        carryOver = CreditLimitCarryOver(repository, referenceData, clock)
    }

    private fun approved(request: RequestLimit): RiskLimitView {
        val limit = risk.requestLimit(request)
        return risk.approveLimit(limit.id, "risk2")
    }

    private fun creditLimit(amount: String, cp: UUID = counterparty, replaces: UUID? = null) =
        RequestLimit(
            entity,
            LimitType.CREDIT_EXPOSURE,
            LimitScope(counterpartyId = cp),
            BigDecimal(amount),
            "EUR",
            replaces = replaces,
            reason = "annual review",
        )

    @Test
    fun `a limit takes effect only when someone else in risk approves it, replacing the one it names`() {
        val requested = risk.requestLimit(creditLimit("5000"))
        requested.status shouldBe LimitStatus.PENDING_APPROVAL
        risk.limits(entity) shouldBe emptyList()
        shouldThrow<SegregationOfDutiesException> { risk.approveLimit(requested.id, "risk1") }
        val first = risk.approveLimit(requested.id, "risk2")
        val second = approved(creditLimit("6000", replaces = first.id))
        risk.limits(entity).map { it.id } shouldBe listOf(second.id)
        risk.limit(first.id)!!.status shouldBe LimitStatus.RETIRED
        shouldThrow<ValidationException> { risk.requestLimit(creditLimit("1", replaces = first.id)) }
        shouldThrow<ValidationException> {
            risk.requestLimit(
                RequestLimit(null, LimitType.POSITION_NET, LimitScope(productFamily = ProductFamily.EMISSION_ALLOWANCE), BigDecimal.TEN, reason = "x"),
            )
        }
    }

    @Test
    fun `exposure is positive MtM plus receivables plus PFE less collateral, netted under a netting agreement`() {
        val support = risk.requestCreditSupport(RequestCreditSupport(entity, counterparty, CreditSupportKind.CASH_RECEIVED, BigDecimal("1000"), "EUR", today))
        risk.approveCreditSupport(support.id, "risk2")
        risk.measureEntity(entity)
        // 300 MtM + 200 receivables + 70000 x 6% PFE (two months) - 1000 cash received
        risk.exposures(entity).single { it.counterpartyId == counterparty }.let {
            it.mtm shouldBe BigDecimal("300.00")
            it.receivables shouldBe BigDecimal("200.00")
            it.pfe shouldBe BigDecimal("4200.00")
            it.collateral shouldBe BigDecimal("1000.00")
            it.exposure shouldBe BigDecimal("3700.00")
        }
        netting = true
        risk.measureEntity(entity)
        risk.exposures(entity).single { it.counterpartyId == counterparty }.exposure shouldBe BigDecimal("3550.00") // 200 + 200 - 50 + 4200 - 1000
    }

    @Test
    fun `margined futures leave MtM and PFE, the margin held at the clearing house is exposure, and margin liquidity is limited`() {
        val margined = openTrades.single()
        val clearing = mockk<ClearingQuery> { every { marginedTradeIds(entity) } returns setOf(margined.id) }
        val margin = mockk<MarginCallQuery> {
            every { marginPosted(entity) } returns listOf(
                MarginPostedView(UUID.randomUUID(), counterparty, "EUR", BigDecimal("5000.00"), BigDecimal("1000.00"), BigDecimal("2000.00")),
            )
        }
        risk = withClearing(clearing, margin)
        val limit = approved(RequestLimit(entity, LimitType.MARGIN_LIQUIDITY, LimitScope(), BigDecimal("6000"), "EUR", reason = "Cash tied up at ECC"))
        val measured = risk.measureEntity(entity)
        // No MtM or PFE on the future; 200 receivables, and the 5 000 initial margin and 1 000 cash the house holds.
        risk.exposures(entity).single { it.counterpartyId == counterparty }.let {
            it.mtm shouldBe BigDecimal("0.00")
            it.pfe shouldBe BigDecimal("0.00")
            it.collateral shouldBe BigDecimal("-6000.00")
            it.exposure shouldBe BigDecimal("6200.00")
        }
        measured.single { it.limitId == limit.id }.let {
            it.value shouldBe BigDecimal("7000.00")
            it.status shouldBe UtilisationStatus.BREACH
        }
        shouldThrow<ValidationException> { risk.requestLimit(RequestLimit(entity, LimitType.MARGIN_LIQUIDITY, LimitScope(), BigDecimal("1"), reason = "x")) }
    }

    @Test
    fun `a breach opens when exposure exceeds the limit and closes when a higher limit is approved`() {
        val limit = approved(creditLimit("3000"))
        risk.measureEntity(entity).single { it.limitId == limit.id }.status shouldBe UtilisationStatus.BREACH // 4700
        published.filterIsInstance<LimitBreached>() shouldHaveSize 1
        risk.measureEntity(entity)
        published.filterIsInstance<LimitBreached>() shouldHaveSize 1
        risk.breaches(entity).single().status shouldBe BreachStatus.OPEN

        approved(creditLimit("6000", replaces = limit.id))
        published.filterIsInstance<LimitBreachResolved>() shouldHaveSize 1
        risk.measureEntity(entity).single().status shouldBe UtilisationStatus.OK // 4700 of 6000 is below the 80% warning level
        risk.breaches(entity, openOnly = true) shouldBe emptyList()
    }

    @Test
    fun `a limit on the group parent covers its subsidiaries`() {
        val group = approved(creditLimit("10000", cp = parent))
        risk.measureEntity(entity).single { it.limitId == group.id }.value shouldBe BigDecimal("4700.00")
    }

    @Test
    fun `the pre-trade check asks for risk approval when the trade's PFE takes exposure over the limit`() {
        approved(creditLimit("8000"))
        risk.measureEntity(entity)
        val small = trade(100, LocalDate.of(2026, 12, 10))
        risk.preTradeFindings(small).map { it.severity } shouldBe emptyList<FindingSeverity>() // 4700 + 420
        val large = trade(1000, LocalDate.of(2027, 6, 10))
        risk.preTradeFindings(large).single().let {
            it.code shouldBe "CREDIT_LIMIT"
            it.severity shouldBe FindingSeverity.REQUIRES_RISK_APPROVAL // 4700 + 70000 x 10%
        }
        risk.preTradeFindings(trade(10, today, cp = unlimited)).single().code shouldBe "NO_CREDIT_LIMIT"
    }

    @Test
    fun `position and tenor limits flag trades that would exceed them`() {
        approved(
            RequestLimit(entity, LimitType.TENOR_MAX_MONTHS, LimitScope(productFamily = ProductFamily.EMISSION_ALLOWANCE), BigDecimal(12), reason = "tenor"),
        )
        approved(
            RequestLimit(
                entity,
                LimitType.POSITION_NET,
                LimitScope(bookId = null, productFamily = ProductFamily.EMISSION_ALLOWANCE),
                BigDecimal(1500),
                reason = "position",
            ),
        )
        val findings = risk.preTradeFindings(trade(600, LocalDate.of(2028, 1, 15))).map { it.code }
        findings shouldContain "TENOR_LIMIT"
        findings shouldContain "POSITION_LIMIT" // 1000 open + 600
        risk.preTradeFindings(trade(400, LocalDate.of(2027, 3, 15))).map { it.code }.filter { it != "NO_CREDIT_LIMIT" } shouldBe emptyList()

        // An option counts its delta-equivalent (ADR 0047): a call on 2 000 t at delta 0.4 is 800 t, so 1 800 in all.
        val expiry = LocalDate.of(2027, 3, 10)
        risk.preTradeFindings(option(2_000, OptionType.CALL, expiry)).map { it.code } shouldContain "POSITION_LIMIT"
        risk.preTradeFindings(option(1_000, OptionType.CALL, expiry)).map { it.code } shouldNotContain "POSITION_LIMIT"
        // A put that cannot be valued counts in full, as a sale: 1 000 − 400.
        risk.preTradeFindings(option(400, OptionType.PUT, expiry)).map { it.code } shouldNotContain "POSITION_LIMIT"
        risk.preTradeFindings(option(3_000, OptionType.PUT, expiry)).map { it.code } shouldContain "POSITION_LIMIT"
    }

    @Test
    fun `counterparty credit limits from reference data are carried over once`() {
        carryOver.carryOver() shouldBe 1
        carryOver.carryOver() shouldBe 0
        risk.limits(null).single().let {
            it.scope.counterpartyId shouldBe unlimited
            it.amount shouldBe BigDecimal("250000.00")
            it.legalEntityId shouldBe null
        }
    }

    @Test
    fun `GoO limits measure the book netted through the flow and flag purchases that would concentrate it`() {
        val free = 900L
        val goo = mockk<GooRiskQuery> {
            every { concentration(entity, "NORDIC_HYDRO") } answers { GooConcentration("NORDIC_HYDRO", free, BigDecimal("2.00")) }
            every { unusedExpiringMwh(entity, 60, null) } returns 120
            every { basis(entity) } returns GooBasis(300, BigDecimal("450.00"))
        }
        val buckets = mockk<ProductBucketQuery> {
            every { buckets(true) } returns listOf(mockk { every { code } returns "NORDIC_HYDRO" })
        }
        val gooRisk = withGoo(mockk { every { ifAvailable } returns goo }, buckets)

        fun approved(request: RequestLimit) = gooRisk.approveLimit(gooRisk.requestLimit(request).id, "risk2")

        // Scope rules: the bucket must exist, the horizon is required, an expiry limit is in MWh, books cannot be split.
        shouldThrow<ValidationException> {
            gooRisk.requestLimit(RequestLimit(entity, LimitType.GOO_CONCENTRATION, LimitScope(bucketCode = "MOON_HYDRO"), BigDecimal(1000), reason = "x"))
        }
        shouldThrow<ValidationException> { gooRisk.requestLimit(RequestLimit(entity, LimitType.GOO_EXPIRY, LimitScope(), BigDecimal(100), reason = "x")) }
        shouldThrow<ValidationException> {
            gooRisk.requestLimit(RequestLimit(entity, LimitType.GOO_EXPIRY, LimitScope(horizonDays = 60), BigDecimal(100), currency = "EUR", reason = "x"))
        }
        shouldThrow<ValidationException> {
            gooRisk.requestLimit(RequestLimit(entity, LimitType.GOO_BASIS, LimitScope(bookId = book), BigDecimal(100), reason = "x"))
        }

        val concentration =
            approved(RequestLimit(entity, LimitType.GOO_CONCENTRATION, LimitScope(bucketCode = "NORDIC_HYDRO"), BigDecimal(1000), reason = "hydro"))
        val inEur = approved(
            RequestLimit(
                entity,
                LimitType.GOO_CONCENTRATION,
                LimitScope(bucketCode = "NORDIC_HYDRO"),
                BigDecimal(2000),
                currency = "EUR",
                reason = "hydro EUR",
            ),
        )
        val expiry = approved(RequestLimit(entity, LimitType.GOO_EXPIRY, LimitScope(horizonDays = 60), BigDecimal(100), reason = "expiry"))
        val basis = approved(RequestLimit(entity, LimitType.GOO_BASIS, LimitScope(), BigDecimal(1000), currency = "EUR", reason = "basis"))

        gooRisk.evaluate(concentration).let {
            it.value.compareTo(BigDecimal(900)) shouldBe 0
            it.status shouldBe UtilisationStatus.WARNING
        }
        gooRisk.evaluate(inEur).value.compareTo(BigDecimal("1800")) shouldBe 0
        gooRisk.evaluate(expiry).status shouldBe UtilisationStatus.BREACH
        gooRisk.evaluate(basis).value.compareTo(BigDecimal("450")) shouldBe 0
        // Externalised to Kafka (ADR 0029): the new type and scope fields are additive in the event's JSON.
        val breached = published.filterIsInstance<LimitBreached>().single { it.limit.id == expiry.id }
        Json.mapper.writeValueAsString(breached).let {
            it shouldContain "\"GOO_EXPIRY\""
            it shouldContain "\"horizonDays\":60"
        }

        // A purchase of Nordic hydro adds to the free certificates once delivered: 900 + 200 is over 1000.
        val purchase = gooTrade(Side.BUY, "NORDIC_HYDRO", 200)
        gooRisk.preTradeFindings(purchase).filter { it.code == "GOO_CONCENTRATION" }.map { it.severity } shouldContain FindingSeverity.REQUIRES_RISK_APPROVAL
        // A sale adds nothing to it, but GoO trades are told about GoO limits already in breach.
        gooRisk.preTradeFindings(gooTrade(Side.SELL, "NORDIC_HYDRO", 200)).map { it.code }.let {
            it shouldNotContain "GOO_CONCENTRATION"
            it shouldContain "LIMIT_IN_BREACH"
        }
        // Without allocation the GoO limits are not measured (they keep their last value and count as breached), and nothing else fails.
        withGoo(mockk { every { ifAvailable } returns null }, buckets).evaluate(basis).let {
            it.status shouldBe UtilisationStatus.NOT_MEASURED
            it.value.compareTo(BigDecimal("450")) shouldBe 0
            it.detail shouldBe "not measured: GoO measures are not available"
        }
    }

    @Test
    fun `GoO limits by vintage count one production year, and sales are checked against the basis limit`() {
        var markedAt = 2025
        var ready = true
        val goo = mockk<GooRiskQuery> {
            every { concentration(entity, "NORDIC_HYDRO", 2025) } returns GooConcentration("NORDIC_HYDRO", 400, BigDecimal("1.50"))
            every { basis(entity, null) } returns GooBasis(300, BigDecimal("450.00"))
            every { basis(entity, 2025) } returns GooBasis(100, BigDecimal("150.00"))
            every { vintages(any()) } answers { firstArg<TradeView>().legs.flatMap { it.deliveries }.associate { it.id to markedAt } }
            every { projectSale(any()) } answers {
                val delivery = firstArg<TradeView>().legs.single().deliveries.single()
                val premium = DeliveryGiveAway(delivery.id, delivery.deliveryDate, markedAt, "EU_RENEWABLE", 200, 200, 200, BigDecimal("700.00"))
                if (!ready) null else SaleProjection(listOf(premium))
            }
        }
        val buckets = mockk<ProductBucketQuery> {
            every { buckets(true) } returns listOf(mockk { every { code } returns "NORDIC_HYDRO" })
        }
        val gooRisk = withGoo(mockk { every { ifAvailable } returns goo }, buckets)

        fun approved(request: RequestLimit) = gooRisk.approveLimit(gooRisk.requestLimit(request).id, "risk2")

        // A vintage scopes GoO limits only, and is a plausible year.
        shouldThrow<ValidationException> {
            gooRisk.requestLimit(
                RequestLimit(
                    entity,
                    LimitType.POSITION_NET,
                    LimitScope(productFamily = ProductFamily.GUARANTEE_OF_ORIGIN, productionYear = 2025),
                    BigDecimal(10),
                    reason = "x",
                ),
            )
        }
        shouldThrow<ValidationException> {
            gooRisk.requestLimit(RequestLimit(entity, LimitType.GOO_BASIS, LimitScope(productionYear = 1990), BigDecimal(10), reason = "x"))
        }

        val vintage = approved(
            RequestLimit(
                entity,
                LimitType.GOO_CONCENTRATION,
                LimitScope(bucketCode = "NORDIC_HYDRO", productionYear = 2025),
                BigDecimal(300),
                reason = "hydro 2025",
            ),
        )
        gooRisk.evaluate(vintage).let {
            it.value.compareTo(BigDecimal(400)) shouldBe 0
            it.status shouldBe UtilisationStatus.BREACH
            it.detail shouldContain "vintage 2025"
        }
        // The vintage travels in the LimitBreached JSON (ADR 0029), additive.
        Json.mapper.writeValueAsString(published.filterIsInstance<LimitBreached>().single { it.limit.id == vintage.id }) shouldContain "\"productionYear\":2025"

        // A purchase counts against a vintage limit only when its deliveries are marked at that vintage.
        gooRisk.preTradeFindings(gooTrade(Side.BUY, "NORDIC_HYDRO", 200)).filter { it.code == "GOO_CONCENTRATION" }.map { it.severity } shouldContain
            FindingSeverity.REQUIRES_RISK_APPROVAL
        markedAt = 2026
        gooRisk.preTradeFindings(gooTrade(Side.BUY, "NORDIC_HYDRO", 200)).map { it.code } shouldNotContain "GOO_CONCENTRATION"
        markedAt = 2025

        // A sale that would hand over 700 EUR of premium: 450 + 700 is over the entity's 1 000, 150 + 700 is a warning for 2025.
        val basis = approved(RequestLimit(entity, LimitType.GOO_BASIS, LimitScope(), BigDecimal(1000), currency = "EUR", reason = "basis"))
        val basis2025 =
            approved(RequestLimit(entity, LimitType.GOO_BASIS, LimitScope(productionYear = 2025), BigDecimal(1000), currency = "EUR", reason = "basis 2025"))
        gooRisk.evaluate(basis)
        gooRisk.evaluate(basis2025).value.compareTo(BigDecimal("150")) shouldBe 0
        val sale = gooTrade(Side.SELL, "EU_RENEWABLE", 200)
        gooRisk.preTradeFindings(sale).filter { it.code == "GOO_BASIS" }.map { it.severity }.toSet() shouldBe
            setOf(FindingSeverity.REQUIRES_RISK_APPROVAL, FindingSeverity.WARN)
        // While the figures are being prepared the check says so, and does not block.
        ready = false
        gooRisk.preTradeFindings(sale).filter { it.code == "GOO_BASIS" }.map { it.severity } shouldBe listOf(FindingSeverity.INFO)
    }

    @Test
    fun `exposure in a currency without a rate is flagged as incomplete, not left out silently`() {
        extraBalances += CounterpartyBalance(counterparty, SettlementDirection.RECEIVABLE, "USD", BigDecimal("1100"), BigDecimal.ZERO)
        risk.measureEntity(entity)
        risk.exposures(entity).single { it.counterpartyId == counterparty }.let {
            it.incomplete shouldBe false
            it.receivables shouldBe BigDecimal("1200.00") // 200 + 1 100 USD at 1.10
        }

        eurUsd = null
        risk.measureEntity(entity)
        risk.exposures(entity).single { it.counterpartyId == counterparty }.let {
            it.incomplete shouldBe true
            it.leftOutCurrencies shouldBe listOf("USD")
            it.incompleteReasons shouldBe listOf("no USD/EUR rate on 2026-10-10")
            it.receivables shouldBe BigDecimal("200.00") // the USD receivable is not counted, and the exposure says so
        }
    }

    @Test
    fun `a credit limit keeps its last value when a rate is missing and the pre-trade check says it is incomplete`() {
        val limit = approved(
            RequestLimit(entity, LimitType.CREDIT_EXPOSURE, LimitScope(counterpartyId = counterparty), BigDecimal("10000"), "USD", reason = "in dollars"),
        )
        risk.measureEntity(entity).single { it.limitId == limit.id }.let {
            it.value shouldBe BigDecimal("5170.00") // 4 700 EUR at 1.10
            it.incomplete shouldBe false
        }

        eurUsd = null
        risk.measureEntity(entity).single { it.limitId == limit.id }.let {
            it.value shouldBe BigDecimal("5170.00") // not zero: the last measured value stays
            it.incomplete shouldBe true
            it.leftOutCurrencies shouldBe listOf("EUR")
            it.status shouldBe UtilisationStatus.OK
        }
        risk.utilisation(limit.id)!!.incomplete shouldBe true

        risk.preTradeFindings(trade(100, LocalDate.of(2026, 12, 10))).single().let {
            it.code shouldBe "CREDIT_CHECK_INCOMPLETE"
            it.severity shouldBe FindingSeverity.WARN
            it.message shouldContain "incomplete"
            it.message shouldContain "no EUR/USD rate"
        }
    }

    @Test
    fun `an option without a value counts at delta one but the position limit says it is incomplete`() {
        val limit = approved(
            RequestLimit(
                entity,
                LimitType.POSITION_NET,
                LimitScope(productFamily = ProductFamily.EMISSION_ALLOWANCE),
                BigDecimal(1500),
                reason = "position",
            ),
        )
        risk.evaluate(limit).let {
            it.incomplete shouldBe false
            it.value.compareTo(BigDecimal(1000)) shouldBe 0
        }
        // A put on 400 t cannot be valued (no volatility curve): it counts in full, as a sale, and the figure is marked.
        openTrades += option(400, OptionType.PUT, LocalDate.of(2027, 3, 10))
        risk.evaluate(limit).let {
            it.value.compareTo(BigDecimal(600)) shouldBe 0
            it.incomplete shouldBe true
            it.incompleteReasons shouldBe listOf("option of trade T-OPT has no value: a delta of -1 is assumed")
        }
        // A valued call is complete again.
        openTrades.removeLast()
        openTrades += option(400, OptionType.CALL, LocalDate.of(2027, 3, 10))
        risk.evaluate(limit).incomplete shouldBe false
        openTrades.removeLast()

        risk.preTradeFindings(option(3_000, OptionType.PUT, LocalDate.of(2027, 3, 10))).single { it.code == "POSITION_LIMIT" }.message shouldContain
            "incomplete"
    }

    @Test
    fun `a GoO limit that cannot be measured is NOT_MEASURED, keeps its last value and counts as a breach`() {
        var failing = false
        val goo = mockk<GooRiskQuery> {
            every { basis(entity) } answers { if (failing) error("the flow is unavailable") else GooBasis(300, BigDecimal("450.00")) }
            every { projectSale(any()) } returns SaleProjection(emptyList())
        }
        val gooRisk = withGoo(mockk { every { ifAvailable } returns goo }, noBuckets)
        val basis = gooRisk.approveLimit(
            gooRisk.requestLimit(RequestLimit(entity, LimitType.GOO_BASIS, LimitScope(), BigDecimal(1000), currency = "EUR", reason = "basis")).id,
            "risk2",
        )
        gooRisk.evaluate(basis).let {
            it.status shouldBe UtilisationStatus.OK
            it.value.compareTo(BigDecimal("450")) shouldBe 0
        }
        gooRisk.breaches(entity, openOnly = true) shouldBe emptyList()

        failing = true
        gooRisk.evaluate(basis).let {
            it.status shouldBe UtilisationStatus.NOT_MEASURED
            it.value.compareTo(BigDecimal("450")) shouldBe 0 // the last measure, not zero
            it.detail shouldBe "not measured: the flow is unavailable"
        }
        gooRisk.utilisation(basis.id)!!.status shouldBe UtilisationStatus.NOT_MEASURED
        gooRisk.breaches(entity, openOnly = true).single().status shouldBe BreachStatus.OPEN
        published.filterIsInstance<LimitBreached>().single { it.limit.id == basis.id }.utilisation.status shouldBe UtilisationStatus.NOT_MEASURED
        gooRisk.preTradeFindings(gooTrade(Side.SELL, "NORDIC_HYDRO", 100)).single { it.code == "LIMIT_NOT_MEASURED" }.message shouldContain
            "counts as in breach"

        // Measured again, the breach closes.
        failing = false
        gooRisk.evaluate(basis).status shouldBe UtilisationStatus.OK
        gooRisk.breaches(entity, openOnly = true) shouldBe emptyList()
    }

    @Test
    fun `a concentration check that cannot run warns instead of passing the trade`() {
        var failure = "vintages"
        val goo = mockk<GooRiskQuery> {
            every { vintages(any()) } answers { if (failure == "vintages") error("vintages unavailable") else emptyMap() }
            every { concentration(any(), any(), any()) } answers { if (failure == "concentration") error("flow unavailable") else null }
        }
        val buckets = mockk<ProductBucketQuery> { every { buckets(true) } returns listOf(mockk { every { code } returns "NORDIC_HYDRO" }) }
        val gooRisk = withGoo(mockk { every { ifAvailable } returns goo }, buckets)
        gooRisk.approveLimit(
            gooRisk.requestLimit(
                RequestLimit(
                    entity,
                    LimitType.GOO_CONCENTRATION,
                    LimitScope(bucketCode = "NORDIC_HYDRO", productionYear = 2025),
                    BigDecimal(1000),
                    reason = "2025",
                ),
            ).id,
            "risk2",
        )
        gooRisk.approveLimit(
            gooRisk.requestLimit(
                RequestLimit(entity, LimitType.GOO_CONCENTRATION, LimitScope(bucketCode = "NORDIC_HYDRO"), BigDecimal(1000), currency = "EUR", reason = "EUR"),
            ).id,
            "risk2",
        )
        val purchase = gooTrade(Side.BUY, "NORDIC_HYDRO", 200)

        fun notChecked() = gooRisk.preTradeFindings(purchase).filter { it.code == "GOO_CONCENTRATION_NOT_CHECKED" }

        // The vintage cannot be worked out for the 2025 limit; the other limit, in EUR, finds no mark for the bucket.
        failure = "vintages"
        notChecked().let { findings ->
            findings shouldHaveSize 2
            findings.map { it.severity }.toSet() shouldBe setOf(FindingSeverity.WARN)
            findings.map { it.message }.single { it.contains("vintages unavailable") } shouldContain "not checked"
            findings.map { it.message }.single { it.contains("no mark") } shouldContain "NORDIC_HYDRO"
        }
        // The mark cannot be read at all.
        failure = "concentration"
        notChecked().map { it.message }.single { it.contains("flow unavailable") } shouldContain "not checked"
    }

    private fun gooTrade(side: Side, bucket: String, quantity: Int): TradeView {
        val schedule = mockk<DeliveryScheduleView> {
            every { id } returns UUID.randomUUID()
            every { this@mockk.quantity } returns BigDecimal(quantity)
            every { deliveredQuantity } returns BigDecimal.ZERO
            every { remainingQuantity } returns BigDecimal(quantity)
            every { deliveryDate } returns LocalDate.of(2026, 12, 15)
        }
        val leg = mockk<TradeLegView> {
            every { option } returns null
            every { this@mockk.side } returns side
            every { bucketCode } returns bucket
            every { priceType } returns PriceType.FIXED
            every { price } returns BigDecimal("2.10")
            every { priceCurrency } returns Currency.getInstance("EUR")
            every { allowanceProductCode } returns null
            every { deliveries } returns listOf(schedule)
        }
        return mockk {
            every { id } returns UUID.randomUUID()
            every { tradeNumber } returns "T-GOO"
            every { legalEntityId } returns entity
            every { bookId } returns book
            every { counterpartyId } returns counterparty
            every { productFamily } returns ProductFamily.GUARANTEE_OF_ORIGIN
            every { legs } returns listOf(leg)
        }
    }
}
