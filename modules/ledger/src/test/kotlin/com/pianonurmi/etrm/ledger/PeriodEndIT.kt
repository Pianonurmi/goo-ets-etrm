package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.allocation.api.ObligationQuery
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionRecordView
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.EntityCompliance
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceQuery
import com.pianonurmi.etrm.etscompliance.api.InstallationCompliance
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.HoldingChanged
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqCostLayerRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqHedgeRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodEndRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.api.HedgeDirection
import com.pianonurmi.etrm.ledger.api.HedgeInstrument
import com.pianonurmi.etrm.ledger.api.HedgeStatus
import com.pianonurmi.etrm.ledger.api.HedgedItem
import com.pianonurmi.etrm.ledger.api.ManualJournal
import com.pianonurmi.etrm.ledger.api.ManualLine
import com.pianonurmi.etrm.ledger.api.MtmTreatment
import com.pianonurmi.etrm.ledger.api.PeriodEndComponentKind
import com.pianonurmi.etrm.ledger.api.PeriodEndRunView
import com.pianonurmi.etrm.ledger.api.PolicyChangeStatus
import com.pianonurmi.etrm.ledger.api.PolicyKind
import com.pianonurmi.etrm.ledger.api.RequestHedgeDesignation
import com.pianonurmi.etrm.ledger.api.RequestHedgeDiscontinuation
import com.pianonurmi.etrm.ledger.api.RequestPolicyChange
import com.pianonurmi.etrm.ledger.application.HedgeService
import com.pianonurmi.etrm.ledger.application.LedgerEventListeners
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PeriodEndService
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.PriceOrigin
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.AllowanceProductView
import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductBucketView
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.AccountingPolicy
import com.pianonurmi.etrm.referencedata.api.AllowanceClassification
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.FreeAllocationPolicy
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ForwardTerms
import com.pianonurmi.etrm.valuation.api.ForwardValue
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

/** Period end against the journal: MtM by policy, NRV, the emission provision, the grant release and FX revaluation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeriodEndIT {
    private val actor = ActorProvider { Actor("accountant1", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC)
    private val fx = MapFxRates()
    private val october = YearMonth.of(2026, 10)
    private val june = YearMonth.of(2026, 6)

    private val valuationRuns = mutableMapOf<UUID, MutableMap<LocalDate, ValuationRunView>>()
    private val valuationResults = mutableMapOf<UUID, List<ValuationResultView>>()
    private val installations = mutableMapOf<Pair<UUID, Int>, List<InstallationCompliance>>()
    private val emissionRecords = mutableMapOf<UUID, List<EmissionRecordView>>()
    private val liveLots = mutableMapOf<UUID, List<LotView>>()
    private var seq = 0L

    private lateinit var ledger: LedgerService
    private lateinit var listeners: LedgerEventListeners
    private lateinit var periodEnd: PeriodEndService
    private lateinit var withHedges: PeriodEndService
    private lateinit var hedges: HedgeService
    private val tradesById = mutableMapOf<UUID, TradeView>()
    private val marginedTrades = mutableMapOf<UUID, Set<UUID>>()
    private var hedgedForward = BigDecimal("80")
    private lateinit var referenceData: ReferenceDataService

    @BeforeAll
    @Suppress("LongMethod") // hand-wired ledger with stubbed neighbours
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "ledger")
        val dsl = TestDatabase.dsl
        val publisher = ApplicationEventPublisher { }
        referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), TaxonomyService(JooqTaxonomyRepository(dsl), publisher), publisher)
        ledger = LedgerService(
            JooqGlAccountRepository(dsl),
            JooqPostingRuleRepository(dsl),
            JooqJournalRepository(dsl),
            JooqPeriodRepository(dsl),
            fx,
            JooqGlExportRepository(dsl),
            referenceData,
            actor,
            publisher,
            clock,
            JooqGlInterfaceRepository(dsl),
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        listeners = LedgerEventListeners(ledger, JooqCostLayerRepository(dsl), referenceData, mockk(relaxed = true), mockk(relaxed = true), clock)

        val valuations = mockk<ValuationQuery>()
        every { valuations.official(any(), any(), true) } answers {
            valuationRuns[firstArg()].orEmpty().filterKeys { it <= secondArg<LocalDate>() }.maxByOrNull { it.key }?.value
        }
        every { valuations.results(any(), null) } answers { valuationResults[firstArg()] ?: emptyList() }
        val compliance = mockk<EtsComplianceQuery>()
        every { compliance.entityCompliance(any(), any()) } answers {
            EntityCompliance(firstArg(), secondArg(), LocalDate.MAX, installations[firstArg<UUID>() to secondArg<Int>()] ?: emptyList(), emptySet())
        }
        every { compliance.emissions(any(), any()) } answers { emissionRecords[firstArg()].orEmpty().filter { it.complianceYear == secondArg<Int?>() } }
        val eua = mockk<AllowanceProductView> {
            every { code } returns "EUA"
            every { jurisdiction } returns "EU_ETS"
        }
        val catalogue = mockk<ProductCatalogueQuery> { every { allowanceProducts(false) } returns listOf(eua) }
        val lots =
            mockk<LotQuery> { every { lots(any()) } answers { liveLots[firstArg<com.pianonurmi.etrm.inventory.api.LotFilter>().holderEntityId].orEmpty() } }
        val hydro = mockk<ProductBucketView> { every { code } returns "NORDIC_HYDRO" }
        val buckets = mockk<ProductBucketQuery> { every { bind(any(), any()) } returns BoundBuckets(listOf(hydro to mockk(relaxed = true))) { _, _ -> true } }
        val curves = mockk<CurveQuery> {
            every { forwardPrice("EUA", any(), any(), any()) } returns price("80")
            every { vintagePrice("NORDIC_HYDRO", 2026, any(), any()) } returns price("0.30")
        }
        val planId = UUID.randomUUID()
        val obligationId = UUID.randomUUID()
        val saleRef = UUID.randomUUID()
        val line = mockk<AllocationLineView> {
            every { reservedLotId } returns RESERVED_LOT
            every { this@mockk.obligationId } returns obligationId
        }
        val plans = mockk<AllocationQuery> { every { requirePlan(planId) } returns mockk { every { lines } returns listOf(line) } }
        val obligations = mockk<ObligationQuery> { every { obligation(obligationId) } returns mockk { every { sourceRef } returns saleRef } }
        every { valuations.run(any()) } answers { valuationRuns.values.flatMap { it.values }.firstOrNull { r -> r.id == firstArg<UUID>() } }
        // The hypothetical derivative: q x (forward - locked price), undiscounted, in EUR.
        every { valuations.forwardValue(any(), any(), any()) } answers {
            val terms = thirdArg<ForwardTerms>()
            val value = (terms.quantity * (hedgedForward - terms.price)).setScale(2)
            ForwardValue(hedgedForward, BigDecimal.ONE, value, value, "EUR")
        }
        val trades = mockk<TradeQuery> {
            every { trade(any()) } answers { tradesById[firstArg()] }
            every { obligation(saleRef) } returns mockk<DeliveryObligationView> {
                every { price } returns BigDecimal("0.80")
                every { priceCurrency } returns Currency.getInstance("EUR")
            }
        }
        reservationPlan = planId
        periodEnd = PeriodEndService(
            ledger,
            JooqJournalRepository(dsl),
            JooqCostLayerRepository(dsl),
            JooqPeriodRepository(dsl),
            JooqPeriodEndRepository(dsl),
            referenceData,
            catalogue,
            valuations,
            compliance,
            lots,
            buckets,
            mockk(relaxed = true),
            curves,
            fx,
            plans,
            obligations,
            trades,
            actor,
            publisher,
            clock,
            clearing = mockk<ClearingQuery> { every { marginedTradeIds(any()) } answers { marginedTrades[firstArg()].orEmpty() } },
        )
        val documents = mockk<DocumentCommand> { every { renderPdf(any()) } returns mockk { every { id } returns UUID.randomUUID() } }
        hedges = HedgeService(JooqHedgeRepository(dsl), trades, valuations, documents, referenceData, actor, publisher, clock)
        withHedges = PeriodEndService(
            ledger,
            JooqJournalRepository(dsl),
            JooqCostLayerRepository(dsl),
            JooqPeriodRepository(dsl),
            JooqPeriodEndRepository(dsl),
            referenceData,
            catalogue,
            valuations,
            compliance,
            lots,
            buckets,
            mockk(relaxed = true),
            curves,
            fx,
            plans,
            obligations,
            trades,
            actor,
            publisher,
            clock,
            hedges,
        )
    }

    private lateinit var reservationPlan: UUID

    private fun price(value: String) = CurvePrice(BigDecimal(value), "EUR", PriceOrigin.POINT, "CURVE", UUID.randomUUID(), LocalDate.of(2026, 10, 30))

    private fun entity(code: String, policy: AccountingPolicy = AccountingPolicy()): UUID {
        val id = referenceData.createLegalEntity(CreateLegalEntity(code, code, "FI", validFrom = LocalDate.of(2026, 1, 1), accountingPolicy = policy)).id
        official(id, LocalDate.of(2026, 10, 30))
        official(id, LocalDate.of(2026, 6, 30))
        return id
    }

    /** The entity's official valuation of [date]. */
    private fun official(entity: UUID, date: LocalDate, results: List<ValuationResultView> = emptyList()) {
        val run = mockk<ValuationRunView> {
            every { id } returns UUID.nameUUIDFromBytes("$entity:$date".toByteArray())
            every { valuationDate } returns date
        }
        valuationRuns.getOrPut(entity) { mutableMapOf() }[date] = run
        valuationResults[run.id] = results
    }

    private fun mtm(bookType: BookType, value: String) = mockk<ValuationResultView> {
        every { this@mockk.bookType } returns bookType
        every { status } returns ResultStatus.OK
        every { mtmBase } returns BigDecimal(value)
    }

    private fun balance(entity: UUID, period: YearMonth, account: String): BigDecimal =
        ledger.trialBalance(entity, period).firstOrNull { it.accountCode == account }?.balance ?: BigDecimal.ZERO

    private fun movement(entity: UUID, type: AllowanceMovementType, quantity: Long, cost: String?, on: LocalDate, year: Int? = 2026) {
        val holding = AllowanceHoldingView(UUID.nameUUIDFromBytes("$entity".toByteArray()), entity, ACCOUNT, "EUA", 0, 0, 1)
        val m =
            AllowanceMovementView(
                ++seq,
                holding.id,
                type,
                type.sign * quantity,
                on,
                null,
                cost?.let(::BigDecimal),
                cost?.let {
                    "EUR"
                },
                year,
                "test",
                Instant.now(),
            )
        listeners.on(HoldingChanged(holding, m))
    }

    private fun forecast(entity: UUID, quantity: Long, surrendered: Long) {
        val installationId = UUID.nameUUIDFromBytes("inst:$entity".toByteArray())
        installations[entity to 2026] = listOf(
            mockk {
                every { installation.id } returns installationId
                every { this@mockk.surrendered } returns surrendered
            },
        )
        emissionRecords[installationId] = listOf(
            EmissionRecordView(
                UUID.randomUUID(),
                installationId,
                2026,
                EmissionKind.FORECAST,
                null,
                quantity,
                EmissionStatus.APPROVED,
                null,
                null,
                null,
                null,
                "u",
                Instant.EPOCH,
                null,
                null,
            ),
        )
    }

    /** A bought EUA forward of 1 000 t, open, delivering in December. */
    private fun forwardPurchase(entity: UUID, number: String, side: Side = Side.BUY, future: String? = null): TradeView {
        val schedule = mockk<DeliveryScheduleView> {
            every { remainingQuantity } returns BigDecimal(1000)
            every { deliveryDate } returns LocalDate.of(2026, 12, 14)
        }
        val leg = mockk<TradeLegView> {
            every { futuresContractCode } returns future
            every { option } returns null
            every { priceType } returns PriceType.FIXED
            every { this@mockk.side } returns side
            every { allowanceProductCode } returns "EUA"
            every { bucketCode } returns null
            every { priceCurrency } returns Currency.getInstance("EUR")
            every { deliveries } returns listOf(schedule)
        }
        val trade = mockk<TradeView> {
            every { id } returns UUID.nameUUIDFromBytes(number.toByteArray())
            every { tradeNumber } returns number
            every { legalEntityId } returns entity
            every { status } returns TradeStatus.CONFIRMED
            every { productFamily } returns ProductFamily.EMISSION_ALLOWANCE
            every { legs } returns listOf(leg)
        }
        tradesById[trade.id] = trade
        return trade
    }

    private fun hedgeMtm(trade: TradeView, value: String) = mockk<ValuationResultView> {
        every { bookType } returns BookType.TRADING
        every { status } returns ResultStatus.OK
        every { mtmBase } returns BigDecimal(value)
        every { tradeId } returns trade.id
    }

    private fun PeriodEndRunView.component(kind: PeriodEndComponentKind, key: String) = components.single { it.kind == kind && it.key == key }

    @Test
    fun `trading MtM is posted and reversed on day one, own use is only disclosed, and a re-run posts only what changed`() {
        val e = entity("PE-MTM")
        official(e, LocalDate.of(2026, 10, 30), listOf(mtm(BookType.TRADING, "1000"), mtm(BookType.TRADING, "-400"), mtm(BookType.OWN_USE, "500")))
        val first = periodEnd.run(e, october)
        balance(e, october, "1800") shouldBe BigDecimal("1000.00")
        balance(e, october, "2800") shouldBe BigDecimal("-400.00")
        balance(e, october, "3500") shouldBe BigDecimal("-1000.00")
        balance(e, october, "4500") shouldBe BigDecimal("400.00")
        balance(e, october.plusMonths(1), "1800") shouldBe BigDecimal("-1000.00")
        first.component(PeriodEndComponentKind.MTM, "OWN_USE:ASSET").let {
            it.disclosedOnly shouldBe true
            it.required shouldBe BigDecimal("500.00")
            it.posted shouldBe BigDecimal("0.00")
        }
        first.warnings.joinToString() shouldContain "MtM policy for TRADING is not signed off"

        val again = periodEnd.run(e, october)
        again.seq shouldBe 2
        again.entryIds.shouldBeEmpty()

        official(e, LocalDate.of(2026, 10, 30), listOf(mtm(BookType.TRADING, "1200"), mtm(BookType.TRADING, "-400")))
        periodEnd.run(e, october).component(PeriodEndComponentKind.MTM, "TRADING:ASSET").posted shouldBe BigDecimal("200.00")
        balance(e, october, "1800") shouldBe BigDecimal("1200.00")
        balance(e, october.plusMonths(1), "1800") shouldBe BigDecimal("-1200.00")
        ledger.verifyJournal(e).shouldBeEmpty()
    }

    @Test
    fun `futures settled daily through variation margin are left out of the MtM`() {
        val e = entity("PE-MARGIN")
        val cleared = forwardPurchase(e, "PE-MARGIN-FUT")
        val bilateral = forwardPurchase(e, "PE-MARGIN-FWD")
        marginedTrades[e] = setOf(cleared.id)
        official(e, LocalDate.of(2026, 10, 30), listOf(hedgeMtm(cleared, "900"), hedgeMtm(bilateral, "-300")))
        periodEnd.run(e, october)
        balance(e, october, "1800").signum() shouldBe 0
        balance(e, october, "2800") shouldBe BigDecimal("-300.00")
    }

    @Test
    fun `the period end needs the official valuation of the last business day and an open next period`() {
        val e = entity("PE-GATE")
        valuationRuns.getValue(e).remove(LocalDate.of(2026, 10, 30))
        official(e, LocalDate.of(2026, 10, 29))
        official(e, LocalDate.of(2026, 11, 2))
        shouldThrow<ValidationException> { periodEnd.run(e, october) }.message!! shouldContain "No official valuation of 2026-10-30"
        official(e, LocalDate.of(2026, 10, 30))
        ledger.closePeriod(e, october.plusMonths(1), hard = false)
        shouldThrow<ValidationException> { periodEnd.run(e, october) }.message!! shouldContain "must be open"
        ledger.reopenPeriod(e, october.plusMonths(1))
        ledger.closePeriod(e, october, hard = false)
        periodEnd.run(e, october).period shouldBe "2026-10"
    }

    @Test
    fun `the emission provision covers emissions to date at cost, the shortfall at market, and a surrender between runs nets to nothing`() {
        val e = entity("PE-PROV")
        movement(e, AllowanceMovementType.PURCHASE, 1000, "70", LocalDate.of(2026, 3, 1))
        forecast(e, 2400, surrendered = 0)
        val first = periodEnd.run(e, june).component(PeriodEndComponentKind.EMISSION_PROVISION, "EMISSIONS")
        first.required shouldBe BigDecimal("86000.00") // 1000 x 70 held + 200 x 80 short, for 1200 t to date
        balance(e, june, "2300") shouldBe BigDecimal("-86000.00")

        movement(e, AllowanceMovementType.SURRENDER, 600, null, LocalDate.of(2026, 6, 20))
        forecast(e, 2400, surrendered = 600)
        balance(e, june, "2300") shouldBe BigDecimal("-44000.00")
        val second = periodEnd.run(e, june).component(PeriodEndComponentKind.EMISSION_PROVISION, "EMISSIONS")
        second.required shouldBe BigDecimal("44000.00") // 400 x 70 + 200 x 80 for the 600 t still owed
        second.posted shouldBe BigDecimal("0.00")
        balance(e, june, "4200") shouldBe BigDecimal("86000.00")
    }

    @Test
    fun `certificates are written down to the firm sale price or the vintage mark less cost to sell, nil near expiry`() {
        val e = entity("PE-NRV")

        fun lot(id: UUID, status: LotStatus, expiry: LocalDate, reservation: UUID? = null) = mockk<LotView>(relaxed = true) {
            every { this@mockk.id } returns id
            every { this@mockk.status } returns status
            every { quantityMwh } returns 100
            every { costBasisPerMwh } returns BigDecimal("0.50")
            every { costCurrency } returns "EUR"
            every { expiryDate } returns expiry
            every { reservationRef } returns reservation
            every { productionPeriod } returns LocalDateRange.ofMonth(YearMonth.of(2026, 3))
        }
        liveLots[e] = listOf(
            lot(UUID.randomUUID(), LotStatus.HELD, LocalDate.of(2027, 3, 31)),
            lot(UUID.randomUUID(), LotStatus.HELD, LocalDate.of(2026, 12, 1)),
            lot(RESERVED_LOT, LotStatus.RESERVED, LocalDate.of(2026, 12, 1), reservationPlan),
        )
        val nrv = periodEnd.run(e, october).component(PeriodEndComponentKind.NRV, "GOO")
        nrv.required shouldBe BigDecimal("72.00") // 100 x (0.50 - 0.28) + 50 (expiring, unreserved) + 0 (sold at 0.80)
        balance(e, october, "1490") shouldBe BigDecimal("-72.00")
        balance(e, october, "4310") shouldBe BigDecimal("72.00")
    }

    @Test
    fun `open foreign currency receivables are revalued at the closing rate and reversed on day one`() {
        val e = entity("PE-FX")
        fx.put(LocalDate.of(2026, 10, 5), "SEK", "EUR", BigDecimal("0.09"))
        fx.put(LocalDate.of(2026, 10, 31), "SEK", "EUR", BigDecimal("0.085"))
        ledger.postManual(
            ManualJournal(
                e,
                LocalDate.of(2026, 10, 5),
                "Invoice in SEK",
                "SEK",
                listOf(ManualLine("1510", debit = BigDecimal("1100")), ManualLine("3000", credit = BigDecimal("1100"))),
            ),
        )
        val run = periodEnd.run(e, october)
        run.component(PeriodEndComponentKind.FX_REVALUATION, "1510:SEK").posted shouldBe BigDecimal("-5.50")
        balance(e, october, "4910") shouldBe BigDecimal("5.50")
        balance(e, october, "1510") shouldBe BigDecimal("93.50")
        balance(e, october.plusMonths(1), "1510") shouldBe BigDecimal("5.50")
    }

    @Test
    fun `free allocation at fair value is released with emissions incurred`() {
        val e =
            entity(
                "PE-GRANT",
                AccountingPolicy(freeAllocationPolicy = FreeAllocationPolicy.FAIR_VALUE, allowanceClassification = AllowanceClassification.INVENTORY),
            )
        movement(e, AllowanceMovementType.FREE_ALLOCATION, 1000, "75", LocalDate.of(2026, 2, 28))
        forecast(e, 1200, surrendered = 0)
        val march = YearMonth.of(2026, 3)
        official(e, LocalDate.of(2026, 3, 31))
        val run = periodEnd.run(e, march)
        run.component(PeriodEndComponentKind.GRANT_RELEASE, "2026").required shouldBe BigDecimal("18750.00") // 75000 x 300/1200
        balance(e, march, "4210") shouldBe BigDecimal("-18750.00")
        // Allowances held as inventory at cost 75 against a price of 80: no write-down.
        run.component(PeriodEndComponentKind.NRV, "ALLOWANCE").required shouldBe BigDecimal("0.00")
    }

    @Test
    fun `accounting policies change only with a second accountant's approval`() {
        val change = periodEnd.requestPolicyChange(
            RequestPolicyChange(null, PolicyKind.MTM, "Hedges documented for cash flow hedge accounting", BookType.HEDGING, MtmTreatment.POST_OCI),
        )
        shouldThrow<SegregationOfDutiesException> { periodEnd.applyPolicyChange(change.id, "accountant1") }
        shouldThrow<ValidationException> { periodEnd.requestPolicyChange(RequestPolicyChange(null, PolicyKind.MTM, "no treatment", BookType.HEDGING)) }
        periodEnd.applyPolicyChange(change.id, "accountant2")
        periodEnd.mtmPolicies(null).single { it.bookType == BookType.HEDGING }.let {
            it.treatment shouldBe MtmTreatment.POST_OCI
            it.signedOffBy shouldBe "accountant2"
        }
        periodEnd.policyChanges(PolicyChangeStatus.APPLIED).single().id shouldBe change.id
        shouldThrow<ValidationException> { periodEnd.applyPolicyChange(change.id, "accountant3") }
    }

    @Test
    @Suppress("LongMethod") // one hedge from designation through two period ends and its discontinuation
    fun `a cash flow hedge is designated under four eyes, splits its value into the reserve and ineffectiveness, and freezes the reserve when discontinued`() {
        val e = entity("PE-HEDGE")
        val forward = forwardPurchase(e, "T-HEDGE-1")
        hedgedForward = BigDecimal("80")
        official(e, LocalDate.of(2026, 10, 30), listOf(hedgeMtm(forward, "2000")))
        val item = HedgedItem(HedgeDirection.PURCHASE, ProductFamily.EMISSION_ALLOWANCE, "EUA", null, null, BigDecimal(1000), LocalDate.of(2026, 12, 14), "EUR")
        shouldThrow<ValidationException> {
            hedges.requestDesignation(RequestHedgeDesignation(e, item, listOf(HedgeInstrument(forwardPurchase(e, "T-HEDGE-SOLD", Side.SELL).id)), "x"))
        }.message!! shouldContain "hedged by buying"
        shouldThrow<ValidationException> {
            hedges.requestDesignation(RequestHedgeDesignation(e, item, listOf(HedgeInstrument(forwardPurchase(e, "T-HEDGE-FUT", future = "EUAZ26").id)), "x"))
        }.message!! shouldContain "settled daily by variation margin"
        val proposed = hedges.requestDesignation(
            RequestHedgeDesignation(e, item, listOf(HedgeInstrument(forward.id)), "Fix the price of the allowances bought for 2026 compliance"),
        )
        proposed.status shouldBe HedgeStatus.PENDING
        proposed.hedgeRatio.compareTo(BigDecimal.ONE) shouldBe 0
        proposed.checks.all { it.passed } shouldBe true
        shouldThrow<SegregationOfDutiesException> { hedges.approveDesignation(proposed.id, "accountant1") }
        val active = hedges.approveDesignation(proposed.id, "accountant2")
        active.designationPrice!!.compareTo(BigDecimal("80")) shouldBe 0
        active.instrumentBaseline shouldBe BigDecimal("2000.00")
        // The trade cannot be designated a second time.
        shouldThrow<ValidationException> { hedges.requestDesignation(RequestHedgeDesignation(e, item, listOf(HedgeInstrument(forward.id)), "again")) }

        // November: the forward rises to 85. The instruments gained 5 500, the hypothetical 5 000: 5 000 is effective.
        hedgedForward = BigDecimal("85")
        official(e, LocalDate.of(2026, 11, 30), listOf(hedgeMtm(forward, "7500")))
        val november = YearMonth.of(2026, 11)
        val run = withHedges.run(e, november)
        run.component(PeriodEndComponentKind.HEDGE, "${active.number}:RESERVE").required shouldBe BigDecimal("5000.00")
        run.component(PeriodEndComponentKind.HEDGE, "${active.number}:INEFFECTIVE").required shouldBe BigDecimal("2500.00")
        balance(e, november, "1800") shouldBe BigDecimal("7500.00")
        balance(e, november, "2950") shouldBe BigDecimal("-5000.00")
        balance(e, november, "3500") shouldBe BigDecimal("-2500.00")
        // The designated trade is not in the trading book's MtM as well.
        run.components.none { it.kind == PeriodEndComponentKind.MTM && it.key == "TRADING:ASSET" && it.required.signum() != 0 } shouldBe true
        withHedges.run(e, november).entryIds.shouldBeEmpty()
        hedges.hedge(active.id).measurements.single().let {
            it.instrumentChange shouldBe BigDecimal("5500.00")
            it.offsetRatio shouldBe BigDecimal("110.0")
        }

        // Discontinued while the purchase is still expected: the reserve stays at 5 000, the rest goes to profit or loss.
        shouldThrow<ValidationException> { hedges.requestDiscontinuation(active.id, RequestHedgeDiscontinuation(" ", true)) }
        hedges.requestDiscontinuation(active.id, RequestHedgeDiscontinuation("Purchase moved to a spot auction", stillExpected = true))
        shouldThrow<SegregationOfDutiesException> { hedges.approveDiscontinuation(active.id, "accountant1") }
        hedges.approveDiscontinuation(active.id, "accountant2").discontinuation!!.frozenReserve shouldBe BigDecimal("5000.00")
        official(e, LocalDate.of(2026, 12, 31), listOf(hedgeMtm(forward, "6000")))
        val december = withHedges.run(e, YearMonth.of(2026, 12))
        december.component(PeriodEndComponentKind.HEDGE, "${active.number}:RESERVE").required shouldBe BigDecimal("5000.00")
        december.component(PeriodEndComponentKind.HEDGE, "${active.number}:INEFFECTIVE").required shouldBe BigDecimal("1000.00")
        ledger.verifyJournal(e).shouldBeEmpty()
    }

    private companion object {
        val ACCOUNT: UUID = UUID.randomUUID()
        val RESERVED_LOT: UUID = UUID.randomUUID()
    }
}
