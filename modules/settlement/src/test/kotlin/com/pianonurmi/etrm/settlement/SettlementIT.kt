package com.pianonurmi.etrm.settlement

import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.FinalSettlement
import com.pianonurmi.etrm.clearing.fixtures.NoClearing
import com.pianonurmi.etrm.document.adapter.jooq.JooqDocumentRepository
import com.pianonurmi.etrm.document.adapter.pdf.OpenHtmlPdfRenderer
import com.pianonurmi.etrm.document.application.DocumentService
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqFxRateRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqImportRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqInstrumentRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqPriceIndexRepository
import com.pianonurmi.etrm.marketdata.adapter.jooq.JooqQuoteRepository
import com.pianonurmi.etrm.marketdata.api.CreateInstrument
import com.pianonurmi.etrm.marketdata.api.CreatePriceIndex
import com.pianonurmi.etrm.marketdata.api.FixingRule
import com.pianonurmi.etrm.marketdata.api.FixingView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuotesImported
import com.pianonurmi.etrm.marketdata.api.RecordQuote
import com.pianonurmi.etrm.marketdata.application.MarketDataService
import com.pianonurmi.etrm.marketdata.application.MarketDataSettings
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.Providers
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqFuturesContractRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqProductBucketRepository
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.FuturesContractService
import com.pianonurmi.etrm.product.application.ProductBucketSeeder
import com.pianonurmi.etrm.product.application.ProductBucketService
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import com.pianonurmi.etrm.product.application.TaxonomySnapshots
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqPartyDetailsRepository
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.AddressType
import com.pianonurmi.etrm.referencedata.api.BankAccountView
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestCommand
import com.pianonurmi.etrm.referencedata.api.CreateBook
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.UpdateCounterparty
import com.pianonurmi.etrm.referencedata.api.UpsertAddress
import com.pianonurmi.etrm.referencedata.api.UpsertVatRegistration
import com.pianonurmi.etrm.referencedata.application.PartyDetailsService
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqAllocationRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqInvoiceRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqNettingRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqNumberSeriesRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqPaymentRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqSettlementItemRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqStandardRateRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqVatRuleRepository
import com.pianonurmi.etrm.settlement.api.AllocatePayment
import com.pianonurmi.etrm.settlement.api.AllocationKind
import com.pianonurmi.etrm.settlement.api.FixPrice
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceLineType
import com.pianonurmi.etrm.settlement.api.InvoiceRun
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.NettingStatus
import com.pianonurmi.etrm.settlement.api.PaymentDirection
import com.pianonurmi.etrm.settlement.api.PaymentStatus
import com.pianonurmi.etrm.settlement.api.PrepareNetting
import com.pianonurmi.etrm.settlement.api.RecordPayment
import com.pianonurmi.etrm.settlement.api.RegisterPurchaseInvoice
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.api.VatTreatment
import com.pianonurmi.etrm.settlement.application.InvoiceService
import com.pianonurmi.etrm.settlement.application.InvoiceSettlement
import com.pianonurmi.etrm.settlement.application.NettingService
import com.pianonurmi.etrm.settlement.application.PaymentService
import com.pianonurmi.etrm.settlement.application.SettlementItemService
import com.pianonurmi.etrm.settlement.application.SettlementReportService
import com.pianonurmi.etrm.settlement.application.SettlementRepricingJob
import com.pianonurmi.etrm.settlement.application.VatRuleService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.adapter.jooq.JooqTradeRepository
import com.pianonurmi.etrm.trade.api.CaptureDelivery
import com.pianonurmi.etrm.trade.api.CaptureLeg
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.application.TradeService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementIT {
    private var user = "trader"
    private val fixings = mutableMapOf<Pair<String, LocalDate>, FixingView>()
    private val actor = ActorProvider { Actor(user, user, setOf(Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC) // a Friday
    private lateinit var items: SettlementItemService
    private val bus = ApplicationEventPublisher { if (it is DeliveryRecorded) items.on(it) }
    private lateinit var referenceData: ReferenceDataService
    private lateinit var parties: PartyDetailsService
    private lateinit var trades: TradeService
    private lateinit var vat: VatRuleService
    private lateinit var invoices: InvoiceService
    private lateinit var numbers: JooqNumberSeriesRepository
    private lateinit var payments: PaymentService
    private lateinit var netting: NettingService
    private lateinit var invoiceSettlement: InvoiceSettlement
    private lateinit var documentService: DocumentService
    private lateinit var reports: SettlementReportService
    private lateinit var entity: UUID
    private lateinit var book: UUID
    private lateinit var domestic: UUID
    private lateinit var german: UUID
    private lateinit var selfBilled: UUID

    @BeforeAll
    @Suppress("LongMethod") // wires the module graph by hand
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "marketdata", "trade", "document", "settlement")
        val dsl = TestDatabase.dsl
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), bus)
        referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, bus)
        val details = JooqPartyDetailsRepository(dsl)
        parties = PartyDetailsService(details, referenceData, NO_CHANGES)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        val buckets = ProductBucketService(JooqProductBucketRepository(dsl), criteria)
        ProductBucketSeeder(buckets, buckets).seed()
        trades = TradeService(
            JooqTradeRepository(dsl),
            referenceData,
            criteria,
            buckets,
            ProductCatalogueService(JooqAllowanceProductRepository(dsl)),
            actor,
            bus,
            clock,
            BigDecimal.ZERO,
            mockk<FxRates>(relaxed = true),
            "EUR",
            mockk(relaxed = true),
            Providers.none(),
        )
        vat = VatRuleService(JooqVatRuleRepository(dsl), actor, bus)
        items = SettlementItemService(
            JooqSettlementItemRepository(dsl),
            vat,
            referenceData,
            parties,
            actor,
            bus,
            trades,
            IndexFixings {
                code,
                date,
                ->
                fixings[code to date]
            },
            Savepoints.NONE,
            NoClearing,
        )
        numbers = JooqNumberSeriesRepository(dsl)
        val documents = DocumentService(JooqDocumentRepository(dsl), OpenHtmlPdfRenderer(), actor, bus, clock)
        val settlement = InvoiceSettlement(
            JooqInvoiceRepository(dsl),
            JooqAllocationRepository(dsl),
            JooqSettlementItemRepository(dsl),
            trades,
            trades,
            bus,
            clock,
        )
        invoiceSettlement = settlement
        documentService = documents
        invoices = InvoiceService(
            JooqInvoiceRepository(dsl),
            JooqSettlementItemRepository(dsl),
            numbers,
            JooqVatRuleRepository(dsl),
            referenceData,
            parties,
            documents,
            actor,
            bus,
            clock,
            settlement,
            JooqStandardRateRepository(dsl),
        )
        payments = PaymentService(JooqPaymentRepository(dsl), JooqInvoiceRepository(dsl), settlement, referenceData, actor, bus)
        netting = NettingService(
            JooqNettingRepository(dsl),
            JooqInvoiceRepository(dsl),
            JooqPaymentRepository(dsl),
            settlement,
            referenceData,
            documents,
            actor,
            bus,
        )
        reports = SettlementReportService(JooqInvoiceRepository(dsl), JooqSettlementItemRepository(dsl), referenceData, actor)
        entity = referenceData.createLegalEntity(
            CreateLegalEntity("SETT-FI", "Settlement Oy", "FI", vatNumber = "FI12345678", validFrom = LocalDate.of(2026, 1, 1)),
        ).id
        book = referenceData.createBook(CreateBook(entity, "SETT-B", "Settlement book", BookType.TRADING)).id
        domestic = referenceData.createCounterparty(CreateCounterparty("SETT-FI-CP", "Ostaja Oy", "FI", vatNumber = "FI87654321", paymentTermsDays = 14)).id
        german = referenceData.createCounterparty(CreateCounterparty("SETT-DE-CP", "Käufer GmbH", "DE", paymentTermsDays = 30)).id
        selfBilled = referenceData.createCounterparty(CreateCounterparty("SETT-SB-CP", "Producer Oy", "FI", vatNumber = "FI11111111", selfBilling = true)).id
        parties.upsertAddress(PartyType.LEGAL_ENTITY, entity, UpsertAddress(AddressType.REGISTERED, "Energiakatu 1", null, "00100", "Helsinki", "FI"))
        details.insertBankAccount(
            BankAccountView(Ids.newId(), PartyType.LEGAL_ENTITY, entity, "EUR", "FI2112345600000785", "NDEAFIHH", "Settlement Oy", true, 1),
        )
    }

    private fun delivered(
        counterparty: UUID,
        side: Side = Side.SELL,
        quantity: Long = 1_000,
        price: String = "2.00",
        priceType: PriceType = PriceType.FIXED,
        deliveredOn: LocalDate = LocalDate.of(2026, 10, 5),
        priceIndex: String = "NORDIC-HYDRO-2026",
    ): SettlementItemView {
        user = "trader"
        val trade = trades.capture(
            CaptureTrade(
                legalEntityId = entity,
                bookId = book,
                counterpartyId = counterparty,
                productFamily = ProductFamily.GUARANTEE_OF_ORIGIN,
                instrumentType = InstrumentType.SPOT,
                tradeDate = LocalDate.of(2026, 10, 1),
                legs = listOf(
                    CaptureLeg(
                        side = side,
                        criterion = Criterion.region("NORDIC"),
                        quantity = BigDecimal(quantity),
                        unit = QuantityUnit.MWH,
                        price = BigDecimal(price),
                        priceType = priceType,
                        priceIndex = if (priceType == PriceType.FIXED) null else priceIndex,
                        deliveries = listOf(CaptureDelivery(LocalDateRange.ofMonth(java.time.YearMonth.of(2026, 10)), deliveredOn, BigDecimal(quantity))),
                    ),
                ),
            ),
        )
        user = "middle"
        trades.approve(trade.id)
        trades.confirm(trade.id, ConfirmTrade("C-${trade.tradeNumber}"))
        val schedule = trades.requireTrade(trade.id).legs.single().deliveries.single()
        trades.recordDelivery(trade.id, RecordDelivery(schedule.id, BigDecimal(quantity), deliveredOn, "TX-${trade.tradeNumber}", "test:${trade.id}"))
        return items.items(SettlementItemFilter(tradeId = trade.id)).single()
    }

    @Test
    fun `VAT rules are selected by product, countries and tax point, including the end of the EUA reverse charge`() {
        fun rule(family: ProductFamily, from: String, to: String, date: String) = vat.determine(family, from, to, LocalDate.parse(date)).rule
        rule(ProductFamily.GUARANTEE_OF_ORIGIN, "FI", "FI", "2026-10-05")!!.let {
            it.code shouldBe "GOO-STD-FI"
            it.rate.compareTo(BigDecimal("25.5")) shouldBe 0
        }
        rule(ProductFamily.GUARANTEE_OF_ORIGIN, "FI", "FI", "2024-08-31")!!.rate.compareTo(BigDecimal(24)) shouldBe 0
        rule(ProductFamily.GUARANTEE_OF_ORIGIN, "DE", "DE", "2026-10-05")!!.treatment shouldBe VatTreatment.REVERSE_CHARGE_DOMESTIC
        rule(ProductFamily.EMISSION_ALLOWANCE, "FI", "FI", "2026-12-31")!!.code shouldBe "EUA-RC-FI"
        rule(ProductFamily.EMISSION_ALLOWANCE, "FI", "FI", "2027-01-01")!!.let {
            it.code shouldBe "EUA-STD-FI"
            it.treatment shouldBe VatTreatment.STANDARD
        }
        rule(ProductFamily.EMISSION_ALLOWANCE, "SE", "DE", "2026-10-05")!!.code shouldBe "B2B-INTRA-EU"
        rule(ProductFamily.GUARANTEE_OF_ORIGIN, "FI", "NO", "2026-10-05")!!.treatment shouldBe VatTreatment.OUT_OF_SCOPE
        rule(ProductFamily.GUARANTEE_OF_ORIGIN, "NO", "FI", "2026-10-05")!!.code shouldBe "B2B-IMPORT"
        vat.determine(ProductFamily.GUARANTEE_OF_ORIGIN, "DK", "DK", LocalDate.of(2026, 10, 5)).problem!! shouldStartWith "No approved VAT rule"
    }

    @Test
    fun `a domestic sale is invoiced with VAT, approved by a second person and issued with a number, due date and PDF`() {
        val buyer = referenceData.createCounterparty(
            CreateCounterparty("SETT-FI-INV", "Laskutettava Oy", "FI", vatNumber = "FI87654321", paymentTermsDays = 14),
        ).id
        val item = delivered(buyer)
        item.direction shouldBe SettlementDirection.RECEIVABLE
        item.status shouldBe SettlementItemStatus.OPEN
        item.netAmount!!.compareTo(BigDecimal("2000.00")) shouldBe 0
        item.vatAmount!!.compareTo(BigDecimal("510.00")) shouldBe 0

        user = "billing"
        val draft = invoices.run(InvoiceRun(entity, LocalDate.of(2026, 10, 31), buyer)).single()
        draft.kind shouldBe InvoiceKind.SALES
        draft.grossTotal.compareTo(BigDecimal("2510.00")) shouldBe 0
        items.item(item.id)!!.status shouldBe SettlementItemStatus.INVOICED
        invoices.submit(draft.id).status shouldBe InvoiceStatus.PENDING_APPROVAL
        shouldThrow<SegregationOfDutiesException> { invoices.approve(draft.id, "billing") }
        val issued = invoices.approve(draft.id, "controller")
        issued.status shouldBe InvoiceStatus.ISSUED
        issued.number!! shouldStartWith "INV-2026-"
        // Friday 9 October + 14 days = Friday 23 October, a TARGET2 business day.
        issued.dueDate shouldBe LocalDate.of(2026, 10, 23)
        issued.supplier!!.address shouldBe "Energiakatu 1, 00100 Helsinki, FI"
        issued.customer!!.vatNumber shouldBe "FI87654321"
        issued.bankAccount!!.iban shouldBe "FI2112345600000785"
        issued.documentId shouldNotBe null
        // An issued invoice is frozen in the database too.
        shouldThrow<Exception> {
            TestDatabase.dsl.execute("UPDATE etrm_settlement.invoice SET net_total = 1, gross_total = 1 + vat_total WHERE id = ?", issued.id)
        }
        invoices.export(com.pianonurmi.etrm.settlement.api.InvoiceFilter(legalEntityId = entity)).lines().any { it.startsWith(issued.number) } shouldBe true
    }

    @Test
    fun `cross-border sales need the customer's VAT number and are reverse charged`() {
        val item = delivered(german)
        item.status shouldBe SettlementItemStatus.NEEDS_ATTENTION
        item.problem!! shouldStartWith "The customer has no VAT number"
        parties.upsertVatRegistration(PartyType.COUNTERPARTY, german, UpsertVatRegistration("DE", "DE123456789", LocalDate.of(2020, 1, 1)))
        items.reevaluate(item.id).let {
            it.status shouldBe SettlementItemStatus.OPEN
            it.vatTreatment shouldBe VatTreatment.REVERSE_CHARGE_CROSS_BORDER
            it.vatAmount!!.signum() shouldBe 0
        }
        user = "billing"
        val draft = invoices.run(InvoiceRun(entity, LocalDate.of(2026, 10, 31), german)).single()
        invoices.submit(draft.id)
        invoices.approve(draft.id, "controller").notes shouldContain "Reverse charge: Reverse charge, VAT Directive Art. 44 and 196"
    }

    @Test
    fun `index-priced deliveries wait for the price to be fixed`() {
        fixings.clear()
        val item = delivered(domestic, priceType = PriceType.INDEX, price = "0")
        item.status shouldBe SettlementItemStatus.NEEDS_ATTENTION
        item.problem!! shouldStartWith "The price of trade"
        user = "middle"
        items.fixPrice(FixPrice(item.scheduleId, BigDecimal("1.50"), "index published")).single().let {
            it.status shouldBe SettlementItemStatus.OPEN
            it.netAmount!!.compareTo(BigDecimal("1500.00")) shouldBe 0
        }
    }

    @Test
    fun `index-priced deliveries are priced from the index fixing plus the spread`() {
        val item = delivered(domestic, priceType = PriceType.INDEX, price = "0.10")
        item.status shouldBe SettlementItemStatus.NEEDS_ATTENTION
        val schedule = trades.obligation(item.scheduleId)!!
        fixings["NORDIC-HYDRO-2026" to schedule.deliveryDate] = FixingView("NORDIC-HYDRO-2026", schedule.deliveryDate, BigDecimal("1.40"), "EUR", emptyList())
        items.on(QuotesImported(UUID.randomUUID(), "TEST", 1, setOf(schedule.deliveryDate)))
        items.item(item.id)!!.let {
            it.status shouldBe SettlementItemStatus.OPEN
            it.unitPrice!!.compareTo(BigDecimal("1.50")) shouldBe 0
        }
        fixings.clear()
    }

    @Test
    fun `a margined future delivers at the final settlement price, waiting for it when not yet stored`() {
        user = "trader"
        val captured = trades.capture(
            CaptureTrade(
                legalEntityId = entity,
                bookId = book,
                counterpartyId = domestic,
                productFamily = ProductFamily.GUARANTEE_OF_ORIGIN,
                instrumentType = InstrumentType.SPOT,
                tradeDate = LocalDate.of(2026, 10, 1),
                legs = listOf(
                    CaptureLeg(
                        side = Side.SELL,
                        criterion = Criterion.region("NORDIC"),
                        quantity = BigDecimal(1000),
                        unit = QuantityUnit.MWH,
                        price = BigDecimal("2.00"),
                        deliveries = listOf(
                            CaptureDelivery(LocalDateRange.ofMonth(java.time.YearMonth.of(2026, 10)), LocalDate.of(2026, 10, 5), BigDecimal(1000)),
                        ),
                    ),
                ),
            ),
        )
        // Stands in for an exchange future: its leg names a contract, and clearing knows it as margined.
        val trade = captured.copy(legs = captured.legs.map { it.copy(futuresContractCode = "EUAZ26") })
        var published: BigDecimal? = null
        val clearing = mockk<ClearingQuery> {
            every { finalSettlement(any()) } returns null
            every { finalSettlement(trade.id) } answers { FinalSettlement("EUAZ26", LocalDate.of(2026, 10, 2), published, "EUR") }
        }
        val noFixings = IndexFixings { _, _ -> null }
        val futures = SettlementItemService(
            JooqSettlementItemRepository(TestDatabase.dsl),
            vat,
            referenceData,
            parties,
            actor,
            bus,
            trades,
            noFixings,
            Savepoints.NONE,
            clearing,
        )
        val obligation = trades.obligation(trade.legs.single().deliveries.single().id)!!
        futures.on(DeliveryRecorded(trade, UUID.randomUUID(), obligation, BigDecimal(1000), LocalDate.of(2026, 10, 5), "TX-FUT"))
        val parked = futures.items(SettlementItemFilter(tradeId = trade.id)).single()
        parked.status shouldBe SettlementItemStatus.NEEDS_ATTENTION
        parked.problem shouldBe "No final settlement price of EUAZ26 on 2026-10-02 yet"
        parked.description shouldContain "final settlement price of EUAZ26"
        published = BigDecimal("2.30")
        futures.reevaluate(parked.id).let {
            it.status shouldBe SettlementItemStatus.OPEN
            it.unitPrice!!.compareTo(BigDecimal("2.30")) shouldBe 0
            it.netAmount!!.compareTo(BigDecimal("2300.00")) shouldBe 0
        }
    }

    @Test
    fun `purchases are invoiced by the supplier and matched within the tolerance, or self-billed`() {
        val bought = delivered(domestic, side = Side.BUY, quantity = 300, price = "1.10")
        bought.direction shouldBe SettlementDirection.PAYABLE
        user = "billing"
        // 330.00 + 84.15 VAT expected; the supplier rounded differently.
        shouldThrow<ValidationException> {
            invoices.registerPurchaseInvoice(
                RegisterPurchaseInvoice(entity, domestic, "SUP-1", LocalDate.of(2026, 10, 7), "EUR", BigDecimal("331.00"), BigDecimal("84.15")),
            )
        }
        val purchase = invoices.registerPurchaseInvoice(
            RegisterPurchaseInvoice(entity, domestic, "SUP-1", LocalDate.of(2026, 10, 7), "EUR", BigDecimal("330.02"), BigDecimal("84.15")),
        )
        purchase.kind shouldBe InvoiceKind.PURCHASE
        purchase.grossTotal.compareTo(BigDecimal("414.17")) shouldBe 0
        purchase.lines.last().lineType shouldBe InvoiceLineType.ADJUSTMENT
        shouldThrow<ConflictException> {
            invoices.registerPurchaseInvoice(
                RegisterPurchaseInvoice(entity, domestic, "SUP-1", LocalDate.of(2026, 10, 7), "EUR", BigDecimal("1"), BigDecimal("0")),
            )
        }
        invoices.submit(purchase.id)
        invoices.approve(purchase.id, "controller").number!! shouldStartWith "PI-2026-"

        delivered(selfBilled, side = Side.BUY, quantity = 100, price = "1.00")
        user = "billing"
        val selfBill = invoices.run(InvoiceRun(entity, LocalDate.of(2026, 10, 31), selfBilled)).single()
        selfBill.kind shouldBe InvoiceKind.SELF_BILLED
        invoices.submit(selfBill.id)
        invoices.approve(selfBill.id, "controller").let {
            it.number!! shouldStartWith "SB-2026-"
            it.notes shouldContain "Self-billing"
            it.supplier!!.name shouldBe "Producer Oy"
        }
    }

    @Test
    fun `a discarded draft releases its items`() {
        val item = delivered(domestic, quantity = 10)
        user = "billing"
        val draft = invoices.run(InvoiceRun(entity, LocalDate.of(2026, 10, 31), domestic)).single { inv -> inv.lines.any { it.settlementItemId == item.id } }
        invoices.discard(draft.id, "wrong period").status shouldBe InvoiceStatus.CANCELLED
        items.item(item.id)!!.status shouldBe SettlementItemStatus.OPEN
    }

    @Test
    fun `numbers are gap-free under concurrency and a rolled back number is reused`() {
        val tx = TransactionTemplate(DataSourceTransactionManager(TestDatabase.dataSource))
        val series = "T${System.nanoTime() % 100_000}"
        val pool = Executors.newFixedThreadPool(4)
        val taken = pool.invokeAll((1..40).map { Callable { tx.execute { numbers.next(entity, series, 2026) } } }).map { it.get() }
        pool.shutdown()
        taken.sorted() shouldBe (1L..40L).toList()
        tx.execute { status ->
            numbers.next(entity, series, 2026) shouldBe 41L
            status.setRollbackOnly()
        }
        tx.execute { numbers.next(entity, series, 2026) } shouldBe 41L
    }

    /** Delivers a sale to a fresh counterparty and issues its invoice; returns the issued invoice. */
    private fun issuedSale(code: String, quantity: Long = 1_000, price: String = "2.00", netting: Boolean = false): InvoiceView {
        val buyer = referenceData.createCounterparty(
            CreateCounterparty(code, "$code Oy", "FI", vatNumber = "FI87654321", paymentTermsDays = 14, nettingAgreed = netting),
        ).id
        return issuedSaleTo(buyer, quantity, price)
    }

    /** Delivers a sale to the counterparty and issues its invoice (the counterparty has no other open delivery). */
    private fun issuedSaleTo(buyer: UUID, quantity: Long = 1_000, price: String = "2.00"): InvoiceView {
        delivered(buyer, quantity = quantity, price = price)
        user = "billing"
        val draft = invoices.run(InvoiceRun(entity, LocalDate.of(2026, 10, 31), buyer)).single()
        invoices.submit(draft.id)
        return invoices.approve(draft.id, "controller")
    }

    /** Pays the invoice in full by an incoming payment that names it. */
    private fun pay(invoice: InvoiceView, reference: String) {
        user = "billing"
        payments.record(
            RecordPayment(
                entity,
                PaymentDirection.INCOMING,
                invoice.grossTotal,
                "EUR",
                LocalDate.of(2026, 10, 20),
                reference,
                invoiceIds = listOf(invoice.id),
            ),
        )
        invoices.invoice(invoice.id)!!.paymentStatus shouldBe PaymentStatus.PAID
    }

    private fun issuedCreditNote(original: InvoiceView): InvoiceView {
        user = "billing"
        val creditNote = invoices.creditNote(original.id, "price corrected")
        invoices.submit(creditNote.id)
        return invoices.approve(creditNote.id, "controller")
    }

    @Test
    fun `a payment quoting the invoice number settles it and the fully paid trade becomes settled`() {
        val invoice = issuedSale("PAY-CP")
        val tradeId = items.item(invoice.lines.single().settlementItemId!!)!!.tradeId
        trades.requireTrade(tradeId).status shouldBe TradeStatus.DELIVERED
        user = "billing"
        val first = payments.record(
            RecordPayment(
                entity,
                PaymentDirection.INCOMING,
                BigDecimal("1000.00"),
                "EUR",
                LocalDate.of(2026, 10, 20),
                "BANK-1",
                remittanceInfo = "Inv ${invoice.number}",
            ),
        )
        first.unappliedAmount.signum() shouldBe 0
        invoices.invoice(invoice.id)!!.paymentStatus shouldBe PaymentStatus.PARTIALLY_PAID
        payments.record(
            RecordPayment(entity, PaymentDirection.INCOMING, BigDecimal("1000.00"), "EUR", LocalDate.of(2026, 10, 20), "BANK-1"),
        ).id shouldBe first.id
        payments.importCsv(entity, "bank_reference,value_date,amount,currency,remittance\nBANK-2,2026-10-22,1510.00,EUR,${invoice.number}\n")
        invoices.invoice(invoice.id)!!.let {
            it.paymentStatus shouldBe PaymentStatus.PAID
            it.outstanding.signum() shouldBe 0
        }
        trades.requireTrade(tradeId).status shouldBe TradeStatus.SETTLED
        payments.allocations(invoice.id).size shouldBe 2
    }

    @Test
    fun `unapplied cash is allocated by hand`() {
        val invoice = issuedSale("UNAPPLIED-CP", quantity = 100)
        user = "billing"
        val payment = payments.record(
            RecordPayment(
                entity,
                PaymentDirection.INCOMING,
                BigDecimal("300.00"),
                "EUR",
                LocalDate.of(2026, 10, 20),
                "BANK-U1",
                counterpartyId = invoice.counterpartyId,
            ),
        )
        payment.unappliedAmount.compareTo(BigDecimal("300.00")) shouldBe 0
        payments.allocate(payment.id, AllocatePayment(invoice.id)).unappliedAmount.compareTo(BigDecimal("49.00")) shouldBe 0
        invoices.invoice(invoice.id)!!.paymentStatus shouldBe PaymentStatus.PAID
        shouldThrow<ValidationException> {
            payments.record(
                RecordPayment(
                    entity,
                    PaymentDirection.OUTGOING,
                    BigDecimal("10"),
                    "EUR",
                    LocalDate.of(2026, 10, 20),
                    "BANK-U2",
                    invoiceIds = listOf(invoice.id),
                ),
            )
        }
    }

    @Test
    fun `a credit note reverses an issued invoice and its deliveries can be invoiced again`() {
        val original = issuedSale("CREDIT-CP", quantity = 50)
        user = "billing"
        val creditNote = invoices.creditNote(original.id, "wrong price")
        creditNote.kind shouldBe InvoiceKind.CREDIT_NOTE
        creditNote.grossTotal.compareTo(original.grossTotal.negate()) shouldBe 0
        shouldThrow<ConflictException> { invoices.creditNote(original.id, "again") }
        invoices.submit(creditNote.id)
        val issued = invoices.approve(creditNote.id, "controller")
        issued.number!! shouldStartWith "CN-2026-"
        issued.paymentStatus shouldBe PaymentStatus.PAID
        invoices.invoice(original.id)!!.let {
            it.credited shouldBe true
            it.paymentStatus shouldBe PaymentStatus.PAID
        }
        val itemId = original.lines.single().settlementItemId!!
        items.item(itemId)!!.status shouldBe SettlementItemStatus.OPEN
        invoices.run(InvoiceRun(entity, LocalDate.of(2026, 10, 31), original.counterpartyId)).single().lines.single().settlementItemId shouldBe itemId
    }

    @Test
    fun `receivables and payables with a counterparty are netted and only the net amount is paid`() {
        val sale = issuedSale("NET-CP", quantity = 1_000, price = "2.00", netting = true)
        val cp = sale.counterpartyId
        delivered(cp, side = Side.BUY, quantity = 300, price = "1.10")
        user = "billing"
        val purchase = invoices.registerPurchaseInvoice(
            RegisterPurchaseInvoice(entity, cp, "NET-SUP-1", LocalDate.of(2026, 10, 7), "EUR", BigDecimal("330.00"), BigDecimal("84.15")),
        )
        invoices.submit(purchase.id)
        invoices.approve(purchase.id, "controller")

        val statement = netting.prepare(PrepareNetting(entity, cp, "EUR", LocalDate.of(2026, 11, 30)))
        statement.receivableTotal.compareTo(BigDecimal("2510.00")) shouldBe 0
        statement.payableTotal.compareTo(BigDecimal("414.15")) shouldBe 0
        statement.netAmount.compareTo(BigDecimal("2095.85")) shouldBe 0
        statement.documentId shouldNotBe null
        shouldThrow<SegregationOfDutiesException> { netting.agree(statement.id, "THEIR-OK") }
        user = "controller"
        netting.agree(statement.id, "THEIR-OK").status shouldBe NettingStatus.AGREED
        shouldThrow<ValidationException> { netting.settle(statement.id, null) }
        val net = payments.record(
            RecordPayment(entity, PaymentDirection.INCOMING, BigDecimal("2095.85"), "EUR", LocalDate.of(2026, 11, 30), "BANK-NET-1", counterpartyId = cp),
        )
        netting.settle(statement.id, net.id).status shouldBe NettingStatus.SETTLED
        invoices.invoice(sale.id)!!.paymentStatus shouldBe PaymentStatus.PAID
        invoices.invoice(purchase.id)!!.paymentStatus shouldBe PaymentStatus.PAID
        payments.payment(net.id)!!.unappliedAmount.signum() shouldBe 0
    }

    @Test
    fun `a credit note issued after full payment can be refunded, and only by a payment in the other direction`() {
        val original = issuedSale("CN-REFUND-CP", quantity = 50)
        pay(original, "BANK-CNR-1")
        val creditNote = issuedCreditNote(original)
        // The original was paid, so nothing of the credit note was used: 125.50 is owed to the customer.
        invoices.invoice(creditNote.id)!!.let {
            it.paymentStatus shouldBe PaymentStatus.UNPAID
            it.outstanding.compareTo(BigDecimal("-125.50")) shouldBe 0
        }
        shouldThrow<ValidationException> {
            payments.record(
                RecordPayment(
                    entity,
                    PaymentDirection.INCOMING,
                    BigDecimal("125.50"),
                    "EUR",
                    LocalDate.of(2026, 10, 21),
                    "BANK-CNR-2",
                    invoiceIds = listOf(creditNote.id),
                ),
            )
        }
        val refund = payments.record(
            RecordPayment(
                entity,
                PaymentDirection.OUTGOING,
                BigDecimal("125.50"),
                "EUR",
                LocalDate.of(2026, 10, 21),
                "BANK-CNR-3",
                invoiceIds = listOf(creditNote.id),
            ),
        )
        refund.unappliedAmount.signum() shouldBe 0
        invoices.invoice(creditNote.id)!!.let {
            it.paymentStatus shouldBe PaymentStatus.PAID
            it.outstanding.signum() shouldBe 0
        }
        payments.allocations(creditNote.id).single().let {
            it.kind shouldBe com.pianonurmi.etrm.settlement.api.AllocationKind.PAYMENT
            it.amount.compareTo(BigDecimal("125.50")) shouldBe 0
        }
        // The original stays paid; nothing was applied to it twice.
        invoices.invoice(original.id)!!.paidAmount.compareTo(original.grossTotal) shouldBe 0
    }

    @Test
    fun `a credit note with a balance left is netted against an invoice of the counterparty`() {
        val cp = referenceData.createCounterparty(
            CreateCounterparty("NET-CN-CP", "Net Credit Oy", "FI", vatNumber = "FI87654321", paymentTermsDays = 14, nettingAgreed = true),
        ).id
        val paid = issuedSaleTo(cp, quantity = 50)
        pay(paid, "BANK-NCN-1")
        val open = issuedSaleTo(cp, quantity = 1_000)
        val creditNote = issuedCreditNote(paid)

        val statement = netting.prepare(PrepareNetting(entity, cp, "EUR", LocalDate.of(2026, 11, 30)))
        // We claim the open invoice (2510.00), the customer claims the credit note (125.50).
        statement.receivableTotal.compareTo(BigDecimal("2510.00")) shouldBe 0
        statement.payableTotal.compareTo(BigDecimal("125.50")) shouldBe 0
        statement.netAmount.compareTo(BigDecimal("2384.50")) shouldBe 0
        statement.invoices.keys shouldContain creditNote.id
        user = "controller"
        netting.agree(statement.id, "THEIR-OK-CN")
        val net = payments.record(
            RecordPayment(entity, PaymentDirection.INCOMING, BigDecimal("2384.50"), "EUR", LocalDate.of(2026, 11, 30), "BANK-NCN-2", counterpartyId = cp),
        )
        netting.settle(statement.id, net.id).status shouldBe NettingStatus.SETTLED
        invoices.invoice(open.id)!!.paymentStatus shouldBe PaymentStatus.PAID
        invoices.invoice(creditNote.id)!!.let {
            it.paymentStatus shouldBe PaymentStatus.PAID
            it.outstanding.signum() shouldBe 0
        }
        payments.allocations(creditNote.id).single().kind shouldBe com.pianonurmi.etrm.settlement.api.AllocationKind.NETTING
        payments.payment(net.id)!!.unappliedAmount.signum() shouldBe 0
    }

    private fun nettingFixture(code: String): Pair<InvoiceView, UUID> {
        val sale = issuedSale(code, quantity = 1_000, price = "2.00", netting = true)
        val cp = sale.counterpartyId
        delivered(cp, side = Side.BUY, quantity = 300, price = "1.10")
        user = "billing"
        val purchase = invoices.registerPurchaseInvoice(
            RegisterPurchaseInvoice(entity, cp, "$code-SUP", LocalDate.of(2026, 10, 7), "EUR", BigDecimal("330.00"), BigDecimal("84.15")),
        )
        invoices.submit(purchase.id)
        invoices.approve(purchase.id, "controller")
        return sale to cp
    }

    @Test
    fun `a statement is refused when a payment changed an invoice after it was prepared, at agreement and at settlement`() {
        val (sale, cp) = nettingFixture("NET-STALE")
        user = "billing"
        val stale = netting.prepare(PrepareNetting(entity, cp, "EUR", LocalDate.of(2026, 11, 30)))
        // A partial payment is applied to the sale between preparation and agreement.
        payments.record(
            RecordPayment(
                entity,
                PaymentDirection.INCOMING,
                BigDecimal("500.00"),
                "EUR",
                LocalDate.of(2026, 10, 20),
                "BANK-ST-1",
                invoiceIds = listOf(sale.id),
            ),
        )
        user = "controller"
        shouldThrow<ValidationException> { netting.agree(stale.id, "THEIR-OK") }.message shouldContain sale.number!!
        user = "billing"
        netting.cancel(stale.id, "invoice paid in part")

        // A new statement is agreed, and then another payment arrives before it is settled.
        val fresh = netting.prepare(PrepareNetting(entity, cp, "EUR", LocalDate.of(2026, 11, 30)))
        fresh.receivableTotal.compareTo(BigDecimal("2010.00")) shouldBe 0
        user = "controller"
        netting.agree(fresh.id, "THEIR-OK").status shouldBe NettingStatus.AGREED
        user = "billing"
        val net = payments.record(
            RecordPayment(entity, PaymentDirection.INCOMING, BigDecimal("1595.85"), "EUR", LocalDate.of(2026, 11, 30), "BANK-ST-2", counterpartyId = cp),
        )
        payments.record(
            RecordPayment(
                entity,
                PaymentDirection.INCOMING,
                BigDecimal("100.00"),
                "EUR",
                LocalDate.of(2026, 10, 21),
                "BANK-ST-3",
                invoiceIds = listOf(sale.id),
            ),
        )
        shouldThrow<ValidationException> { netting.settle(fresh.id, net.id) }.message shouldContain sale.number
        netting.statement(fresh.id)!!.status shouldBe NettingStatus.AGREED
    }

    private class AgreedStatement(val id: UUID, val paymentId: UUID, val sale: InvoiceView, val purchase: InvoiceView)

    /** A netting statement with the counterparty's agreement recorded and its net payment received, ready to settle. */
    private fun agreedStatement(code: String): AgreedStatement {
        val (sale, cp) = nettingFixture(code)
        val purchase = invoices.invoices(InvoiceFilter(entity, cp)).single { it.id != sale.id }
        user = "billing"
        val statement = netting.prepare(PrepareNetting(entity, cp, "EUR", LocalDate.of(2026, 11, 30)))
        user = "controller"
        netting.agree(statement.id, "THEIR-OK")
        user = "billing"
        val net = payments.record(
            RecordPayment(entity, PaymentDirection.INCOMING, BigDecimal("2095.85"), "EUR", LocalDate.of(2026, 11, 30), "BANK-$code", counterpartyId = cp),
        )
        return AgreedStatement(statement.id, net.id, sale, purchase)
    }

    private fun nettingWith(events: ApplicationEventPublisher) = NettingService(
        JooqNettingRepository(TestDatabase.dsl),
        JooqInvoiceRepository(TestDatabase.dsl),
        JooqPaymentRepository(TestDatabase.dsl),
        invoiceSettlement,
        referenceData,
        documentService,
        actor,
        events,
    )

    /** The statement is settled and its offsets and net payment were applied once: nothing was allocated twice. */
    private fun assertSettledOnce(fixture: AgreedStatement) {
        netting.statement(fixture.id)!!.status shouldBe NettingStatus.SETTLED
        payments.allocations(fixture.sale.id).map { it.kind }.sorted() shouldBe listOf(AllocationKind.PAYMENT, AllocationKind.NETTING).sorted()
        payments.allocations(fixture.purchase.id).map { it.kind } shouldBe listOf(AllocationKind.NETTING)
        invoices.invoice(fixture.sale.id)!!.let {
            it.paymentStatus shouldBe PaymentStatus.PAID
            it.paidAmount.compareTo(it.grossTotal) shouldBe 0
        }
        invoices.invoice(fixture.purchase.id)!!.let {
            it.paymentStatus shouldBe PaymentStatus.PAID
            it.paidAmount.compareTo(it.grossTotal) shouldBe 0
        }
        payments.payment(fixture.paymentId)!!.unappliedAmount.signum() shouldBe 0
    }

    /** The statement row lock: the second settlement waits for the first (see [NettingRace.held]) and is then refused. */
    @Test
    fun `settling a statement twice at once settles it once because the second waits for the statement row`() {
        val fixture = agreedStatement("NET-RACE-HELD")
        user = "billing"
        val race = NettingRace(::nettingWith).held(fixture.id, fixture.paymentId)
        race.settled shouldBe 1
        race.refusals.single().shouldBeInstanceOf<IllegalStateTransitionException>()
        assertSettledOnce(fixture)
    }

    /** The same race released together by a barrier (see [NettingRace.together]). */
    @Test
    fun `two settlements released together settle the statement once`() {
        val fixture = agreedStatement("NET-RACE-OPEN")
        user = "billing"
        val race = NettingRace(::nettingWith).together(fixture.id, fixture.paymentId)
        race.settled shouldBe 1
        race.refusals.single().shouldBeInstanceOf<IllegalStateTransitionException>()
        assertSettledOnce(fixture)
    }

    @Test
    fun `a remittance names an invoice only by its whole number, not by a longer number that contains it`() {
        val invoice = issuedSale("TOKEN-CP", quantity = 100)
        user = "billing"
        // The number followed by one more digit, or preceded by a letter, is another reference.
        listOf("Payment for ${invoice.number}3", "ref X${invoice.number}").forEachIndexed { i, text ->
            val unmatched = payments.record(
                RecordPayment(entity, PaymentDirection.INCOMING, invoice.grossTotal, "EUR", LocalDate.of(2026, 10, 20), "BANK-TOK-$i", remittanceInfo = text),
            )
            unmatched.unappliedAmount.compareTo(invoice.grossTotal) shouldBe 0
            invoices.invoice(invoice.id)!!.paidAmount.signum() shouldBe 0
        }
        // The number between other words and punctuation is the same reference.
        val matched = payments.record(
            RecordPayment(
                entity,
                PaymentDirection.INCOMING,
                invoice.grossTotal,
                "EUR",
                LocalDate.of(2026, 10, 20),
                "BANK-TOK-9",
                remittanceInfo = "Invoice (${invoice.number}), thanks",
            ),
        )
        matched.unappliedAmount.signum() shouldBe 0
        invoices.invoice(invoice.id)!!.paymentStatus shouldBe PaymentStatus.PAID
    }

    @Test
    fun `a month average does not price a delivery before the month has ended`() {
        val dsl = TestDatabase.dsl
        val catalogue = ProductCatalogueService(JooqAllowanceProductRepository(dsl))
        var marketNow = Instant.parse("2026-10-09T10:00:00Z")
        val marketClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = marketNow
        }
        val marketData = MarketDataService(
            JooqInstrumentRepository(dsl),
            JooqQuoteRepository(dsl),
            JooqFxRateRepository(dsl),
            JooqImportRepository(dsl),
            JooqPriceIndexRepository(dsl),
            catalogue,
            FuturesContractService(JooqFuturesContractRepository(dsl), catalogue, actor),
            mockk<ProductBucketQuery>(),
            MarketDataSettings(maxMovePct = BigDecimal(20)),
            actor,
            bus,
            marketClock,
        )
        user = "middle"
        marketData.createInstrument(CreateInstrument("SETT_EUA_IDX", InstrumentKind.EUA_SPOT, "EUA spot for settlement", allowanceProductCode = "EUA"))
        marketData.createIndex(CreatePriceIndex("SETT_AVG", "Monthly average", "SETT_EUA_IDX", QuoteType.CLOSE, FixingRule.MONTH_AVERAGE))
        listOf(1 to "70.00", 2 to "72.00").forEach { (d, p) ->
            marketData.recordQuote(RecordQuote("SETT_EUA_IDX", LocalDate.of(2026, 10, d), QuoteType.CLOSE, BigDecimal(p)))
        }
        val priced =
            SettlementItemService(JooqSettlementItemRepository(dsl), vat, referenceData, parties, actor, bus, trades, marketData, Savepoints.NONE, NoClearing)

        val item = delivered(domestic, priceType = PriceType.INDEX, price = "0.10", priceIndex = "SETT_AVG")
        item.status shouldBe SettlementItemStatus.NEEDS_ATTENTION
        // Two days of October are known on 9 October: that is a partial average, so the item stays unpriced.
        priced.reevaluate(item.id).let {
            it.status shouldBe SettlementItemStatus.NEEDS_ATTENTION
            it.unitPrice shouldBe null
            it.problem!! shouldStartWith "The price of trade"
        }
        // Once October has ended the average is final. No import announces that: the morning job prices the item
        // (71.00 + 0.10 spread).
        marketNow = Instant.parse("2026-11-02T10:00:00Z")
        SettlementRepricingJob(priced).run()
        priced.item(item.id)!!.let {
            it.status shouldBe SettlementItemStatus.OPEN
            it.unitPrice!!.compareTo(BigDecimal("71.10")) shouldBe 0
        }
    }

    @Test
    fun `reports show ageing, expected cash, VAT by treatment and the EC sales list`() {
        val overdue = issuedSale("AGE-CP", quantity = 10)
        val ageing = reports.ageing(entity, overdue.dueDate!!.plusDays(40)).single { it.counterpartyId == overdue.counterpartyId }
        ageing.days31to60.compareTo(overdue.grossTotal) shouldBe 0
        reports.cashForecast(entity, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1)).any {
            it.date == overdue.dueDate && it.basis == "invoiced"
        } shouldBe true
        val vatRows = reports.vatReport(entity, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
        vatRows.any {
            it.direction == SettlementDirection.RECEIVABLE && it.treatment == VatTreatment.STANDARD && it.rate.compareTo(BigDecimal("25.5")) == 0
        } shouldBe true
        // The German sale of another test is reverse charged; the EC sales list shows it once it is invoiced.
        reports.ecSalesList(entity, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)).all { it.customerVatNumber.startsWith("DE") } shouldBe true
    }

    private companion object {
        /** Bank account changes are not exercised here. */
        val NO_CHANGES = object : ChangeRequestCommand {
            override fun request(command: com.pianonurmi.etrm.referencedata.api.RequestChange) = error("unused")

            override fun requestRegistryChange(code: String, change: com.pianonurmi.etrm.referencedata.api.RegistryChange) = error("unused")

            override fun requestCounterpartyAccountChange(counterpartyId: UUID, change: com.pianonurmi.etrm.referencedata.api.CounterpartyAccountChange) =
                error("unused")

            override fun cancel(id: UUID, note: String) = error("unused")
        }
    }
}
