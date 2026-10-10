package com.pianonurmi.etrm.settlement

import com.pianonurmi.etrm.document.adapter.jooq.JooqDocumentRepository
import com.pianonurmi.etrm.document.adapter.pdf.OpenHtmlPdfRenderer
import com.pianonurmi.etrm.document.application.DocumentService
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqProductBucketRepository
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
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
import com.pianonurmi.etrm.referencedata.api.UpsertAddress
import com.pianonurmi.etrm.referencedata.api.UpsertVatRegistration
import com.pianonurmi.etrm.referencedata.application.PartyDetailsService
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqInvoiceRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqNumberSeriesRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqSettlementItemRepository
import com.pianonurmi.etrm.settlement.adapter.jooq.JooqVatRuleRepository
import com.pianonurmi.etrm.settlement.api.FixPrice
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceLineType
import com.pianonurmi.etrm.settlement.api.InvoiceRun
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.RegisterPurchaseInvoice
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemFilter
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.api.VatTreatment
import com.pianonurmi.etrm.settlement.application.InvoiceService
import com.pianonurmi.etrm.settlement.application.SettlementItemService
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
import com.pianonurmi.etrm.trade.application.TradeService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
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
    private val actor = ActorProvider { Actor(user, user, setOf(Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.ACCOUNTANT)) }
    private val clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC) // a Friday
    private lateinit var items: SettlementItemService
    private val bus = ApplicationEventPublisher { if (it is DeliveryRecorded) items.on(it) }
    private lateinit var referenceData: ReferenceDataService
    private lateinit var parties: PartyDetailsService
    private lateinit var trades: TradeService
    private lateinit var vat: VatRuleService
    private lateinit var invoices: InvoiceService
    private lateinit var numbers: JooqNumberSeriesRepository
    private lateinit var entity: UUID
    private lateinit var book: UUID
    private lateinit var domestic: UUID
    private lateinit var german: UUID
    private lateinit var selfBilled: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "trade", "document", "settlement")
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
        )
        vat = VatRuleService(JooqVatRuleRepository(dsl), actor, bus)
        items = SettlementItemService(JooqSettlementItemRepository(dsl), vat, referenceData, parties, actor, bus)
        numbers = JooqNumberSeriesRepository(dsl)
        invoices = InvoiceService(
            JooqInvoiceRepository(dsl),
            JooqSettlementItemRepository(dsl),
            numbers,
            JooqVatRuleRepository(dsl),
            referenceData,
            parties,
            DocumentService(JooqDocumentRepository(dsl), OpenHtmlPdfRenderer(), actor, bus, clock),
            actor,
            bus,
            clock,
        )
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
                        priceIndex = if (priceType == PriceType.FIXED) null else "NORDIC-HYDRO-2026",
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
