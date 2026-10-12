package com.pianonurmi.etrm.risk

import com.pianonurmi.etrm.allocation.api.GooBasis
import com.pianonurmi.etrm.allocation.api.GooConcentration
import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
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
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeView
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
    private val openTrades = mutableListOf<TradeView>()
    private val published = mutableListOf<Any>()
    private lateinit var risk: RiskService
    private lateinit var withGoo: (ObjectProvider<GooRiskQuery>?, ProductBucketQuery?) -> RiskService
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
        openTrades.clear()
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
        }
        openTrades += existing
        val settlement = mockk<SettlementReports> {
            every { counterpartyBalances(entity) } returns listOf(
                CounterpartyBalance(counterparty, SettlementDirection.RECEIVABLE, "EUR", BigDecimal("150"), BigDecimal("50")),
                CounterpartyBalance(counterparty, SettlementDirection.PAYABLE, "EUR", BigDecimal("50"), BigDecimal.ZERO),
            )
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
                mockk(relaxed = true),
                actors,
                events,
                clock,
                goo = goo,
                buckets = buckets,
            )
        }
        risk = withGoo(null, null)
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
        // Without allocation the GoO limits read zero, with a note, and nothing else fails.
        withGoo(mockk { every { ifAvailable } returns null }, buckets).evaluate(basis).let {
            it.value.signum() shouldBe 0
            it.detail shouldBe "GoO measures are not available"
        }
    }

    private fun gooTrade(side: Side, bucket: String, quantity: Int): TradeView {
        val schedule = mockk<DeliveryScheduleView> {
            every { this@mockk.quantity } returns BigDecimal(quantity)
            every { deliveredQuantity } returns BigDecimal.ZERO
            every { remainingQuantity } returns BigDecimal(quantity)
            every { deliveryDate } returns LocalDate.of(2026, 12, 15)
        }
        val leg = mockk<TradeLegView> {
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
            every { legalEntityId } returns entity
            every { bookId } returns book
            every { counterpartyId } returns counterparty
            every { productFamily } returns ProductFamily.GUARANTEE_OF_ORIGIN
            every { legs } returns listOf(leg)
        }
    }
}
