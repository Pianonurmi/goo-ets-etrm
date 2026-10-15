package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.allocation.api.AllocationLineConfirmed
import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CancellationPurpose
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.HoldingChanged
import com.pianonurmi.etrm.inventory.api.LotCancelled
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotReceived
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqCostLayerRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.api.EntryStatus
import com.pianonurmi.etrm.ledger.api.ManualJournal
import com.pianonurmi.etrm.ledger.api.ManualLine
import com.pianonurmi.etrm.ledger.api.PeriodStatus
import com.pianonurmi.etrm.ledger.application.LedgerEventListeners
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.ledger.domain.CostLayer
import com.pianonurmi.etrm.ledger.domain.CostingEngine
import com.pianonurmi.etrm.ledger.domain.CostingMethod
import com.pianonurmi.etrm.ledger.domain.GlExportFormat
import com.pianonurmi.etrm.marketdata.api.FixingView
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestClock
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.AccountingPolicy
import com.pianonurmi.etrm.referencedata.api.AllowanceCostingMethod
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.TradeQuery
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LedgerServiceIT {
    private val actor = ActorProvider { Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC)
    private val fx = MapFxRates()
    private lateinit var ledger: LedgerService
    private lateinit var listeners: LedgerEventListeners
    private lateinit var entityId: UUID
    private lateinit var wacEntityId: UUID
    private val accountId: UUID = UUID.randomUUID()
    private lateinit var referenceDataService: ReferenceDataService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "ledger")
        val dsl = TestDatabase.dsl
        val publisher = ApplicationEventPublisher { }
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        referenceDataService = referenceData
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
        listeners = LedgerEventListeners(
            ledger,
            JooqCostLayerRepository(dsl),
            referenceData,
            mockk<LotQuery>(relaxed = true),
            mockk<TradeQuery>(relaxed = true),
            clock,
            fixings = IndexFixings { _, _ -> null },
        )
        entityId = referenceData.createLegalEntity(CreateLegalEntity("LEDGER-CO", "Ledger Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        wacEntityId = referenceData.createLegalEntity(
            CreateLegalEntity(
                "WAC-CO",
                "WAC Co",
                "SE",
                baseCurrency = "SEK",
                validFrom = LocalDate.of(2026, 1, 1),
                accountingPolicy = AccountingPolicy(allowanceCostingMethod = AllowanceCostingMethod.WEIGHTED_AVERAGE),
            ),
        ).id
    }

    private fun lot(quantity: Long, costBasis: String?, source: AcquisitionSource = AcquisitionSource.ISSUANCE): LotView {
        val id = UUID.randomUUID()
        val period = LocalDateRange.ofMonth(YearMonth.of(2026, 6))
        return LotView(
            id,
            entityId,
            accountId,
            "FINGRID",
            "ISS-$id",
            CertificateRange.of(1, quantity),
            quantity,
            EnergyCarrier.ELECTRICITY,
            "EECS_GO",
            "FINEXTRA",
            "FI",
            null,
            "643000000000000016",
            "Device",
            "FI",
            null,
            LocalDate.of(2020, 1, 1),
            BigDecimal(1000),
            "WIND_ONSHORE",
            "RENEWABLE.WIND.WIND_ONSHORE",
            null,
            null,
            period,
            LocalDate.of(2026, 7, 10),
            LocalDate.of(2027, 7, 1),
            false,
            false,
            null,
            emptySet(),
            LotStatus.HELD,
            id,
            null,
            source,
            null,
            LocalDate.of(2026, 7, 10),
            costBasis?.let(::BigDecimal),
            "EUR",
            null,
            null,
            emptyMap(),
            null,
            1,
        )
    }

    @Test
    fun `lot events post balanced, idempotent entries from the default rules`() {
        val received = LotReceived(lot(1_000, "1.50", AcquisitionSource.TRANSFER_IN))
        listeners.on(received)
        listeners.on(received) // same event id: no duplicate
        val entries = ledger.entries(entityId, sourceRef = received.lot.id.toString())
        entries shouldHaveSize 1
        entries.single().let {
            it.ruleCode shouldBe "GOO_PURCHASE_RECEIPT"
            it.totalDebit shouldBe BigDecimal("1500.00")
            it.totalCredit shouldBe BigDecimal("1500.00")
            it.lines.map { l -> l.accountCode } shouldBe listOf("1400", "2200")
        }
        listeners.on(LotReceived(lot(100, null))) // no cost basis: nothing to post
        ledger.entries(entityId, period = YearMonth.of(2026, 7)).count { it.eventType == "LotReceived" } shouldBe 1

        val cancelled = lot(400, "2.00")
        listeners.on(LotCancelled(cancelled, CancellationDetails(CancellationPurpose.DISCLOSURE, "Customer", null, "FI", LocalDateRange.ofYear(2026))))
        ledger.entries(entityId, sourceRef = cancelled.id.toString()).single().let {
            it.ruleCode shouldBe "GOO_DISCLOSURE_CANCELLATION"
            it.lines.first { l -> l.accountCode == "4100" }.debit shouldBe BigDecimal("800.00")
        }
        ledger.verifyJournal(entityId).shouldBeEmpty()
    }

    @Test
    fun `allowance movements use FIFO cost layers and policy-driven accounts`() {
        val holding = AllowanceHoldingView(UUID.randomUUID(), entityId, UUID.randomUUID(), "EUA", 0, 0, 1)

        fun movement(type: AllowanceMovementType, qty: Long, price: String?, seq: Long) =
            AllowanceMovementView(
                seq,
                holding.id,
                type,
                qty,
                LocalDate.of(2026, 8, 1).plusDays(seq),
                "REF-$seq",
                price?.let(::BigDecimal),
                "EUR",
                2026,
                "ops",
                TestClock.DEFAULT_START,
            )

        listeners.on(HoldingChanged(holding.copy(quantity = 1_000), movement(AllowanceMovementType.PURCHASE, 1_000, "70.00", 1)))
        listeners.on(HoldingChanged(holding.copy(quantity = 2_000), movement(AllowanceMovementType.PURCHASE, 1_000, "80.00", 2)))
        listeners.on(HoldingChanged(holding.copy(quantity = 3_000), movement(AllowanceMovementType.FREE_ALLOCATION, 1_000, null, 3)))
        listeners.on(HoldingChanged(holding.copy(quantity = 1_500), movement(AllowanceMovementType.SURRENDER, -1_500, null, 4)))
        listeners.on(HoldingChanged(holding.copy(quantity = 1_000), movement(AllowanceMovementType.SALE, -500, "90.00", 5)))

        val entries = ledger.entries(entityId, period = YearMonth.of(2026, 8))
        entries.first { it.sourceRef == "1" }.lines.map { it.accountCode } shouldBe listOf("1610", "2200")
        // FIFO: 1000 @ 70 + 500 @ 80 = 110 000
        entries.first { it.sourceRef == "4" }.let {
            it.ruleCode shouldBe "EUA_SURRENDER"
            it.totalDebit shouldBe BigDecimal("110000.00")
        }
        val sale = entries.filter { it.sourceRef == "5" }
        sale.first { it.ruleCode == "EUA_SALE_COST" }.totalDebit shouldBe BigDecimal("40000.00") // 500 @ 80
        sale.first { it.ruleCode == "EUA_SALE_PROCEEDS" }.totalCredit shouldBe BigDecimal("45000.00")
        // the nominal free allocation layer is left: zero cost
        entries.none { it.sourceRef == "3" } shouldBe true

        // Filtered by account and paged by entry number.
        val onCost = ledger.entries(entityId, period = YearMonth.of(2026, 8), accountCode = "1610")
        onCost.isNotEmpty() shouldBe true
        onCost.all { entry -> entry.lines.any { it.accountCode == "1610" } } shouldBe true
        val firstPage = ledger.entries(entityId, period = YearMonth.of(2026, 8), limit = 2)
        val nextPage = ledger.entries(entityId, period = YearMonth.of(2026, 8), afterEntryNumber = firstPage.last().entryNumber)
        (firstPage + nextPage).map { it.id } shouldBe entries.map { it.id }
    }

    @Test
    fun `a reconciliation correction moves the cost layers with the holding`() {
        val holding = AllowanceHoldingView(UUID.randomUUID(), entityId, UUID.randomUUID(), "EUA", 0, 0, 1)

        fun movement(type: AllowanceMovementType, qty: Long, price: String?, seq: Long) =
            AllowanceMovementView(
                seq,
                holding.id,
                type,
                qty,
                LocalDate.of(2026, 8, 20),
                "COR-$seq",
                price?.let(::BigDecimal),
                "EUR",
                2026,
                "ops",
                TestClock.DEFAULT_START,
            )

        listeners.on(HoldingChanged(holding.copy(quantity = 100), movement(AllowanceMovementType.PURCHASE, 100, "70.00", 101)))
        // The registry holds ten more than we booked: they come in at the statement's price.
        listeners.on(HoldingChanged(holding.copy(quantity = 110), movement(AllowanceMovementType.CORRECTION, 10, "75.00", 102)))
        // Surrendering all of them needs a layer for every unit (it threw "Insufficient cost layers" before).
        listeners.on(HoldingChanged(holding.copy(quantity = 0), movement(AllowanceMovementType.SURRENDER, -110, null, 103)))
        listeners.on(HoldingChanged(holding.copy(quantity = 100), movement(AllowanceMovementType.PURCHASE, 100, "80.00", 104)))
        // Five are missing in the registry: they leave at cost.
        listeners.on(HoldingChanged(holding.copy(quantity = 95), movement(AllowanceMovementType.CORRECTION, -5, null, 105)))

        val entries = ledger.entries(entityId, period = YearMonth.of(2026, 8))
        entries.single { it.sourceRef == "102" }.let {
            it.ruleCode shouldBe "EUA_CORRECTION_FOUND"
            it.lines.map { l -> l.accountCode } shouldBe listOf("1610", "3920")
            it.totalDebit shouldBe BigDecimal("750.00")
        }
        entries.single { it.sourceRef == "103" }.totalDebit shouldBe BigDecimal("7750.00") // 100 @ 70 + 10 @ 75
        entries.single { it.sourceRef == "105" }.let {
            it.ruleCode shouldBe "EUA_CORRECTION_MISSING"
            it.lines.map { l -> l.accountCode } shouldBe listOf("4920", "1610")
            it.totalDebit shouldBe BigDecimal("400.00") // 5 @ 80
        }
        ledger.verifyJournal(entityId).shouldBeEmpty()
    }

    @Test
    fun `an index-priced sale books revenue at the fixing plus the spread, never at the spread alone`() {
        val fixedOn = LocalDate.of(2026, 9, 10)
        val fixings = IndexFixings { index, date ->
            if (index == "GO_NORDIC" && date == fixedOn) FixingView(index, date, BigDecimal("5.00"), "EUR", emptyList()) else null
        }

        fun deliver(deliveryDate: LocalDate): UUID {
            val sold = lot(100, "0.50")
            val scheduleId = UUID.randomUUID()
            val delivery = mockk<DeliveryObligationView> {
                every { priceType } returns PriceType.INDEX
                every { priceIndex } returns "GO_NORDIC"
                every { price } returns BigDecimal("0.10") // the spread over the index
                every { priceCurrency } returns Currency.getInstance("EUR")
                every { this@mockk.deliveryDate } returns deliveryDate
                every { tradeNumber } returns "T-IDX"
            }
            val sale = LedgerEventListeners(
                ledger,
                JooqCostLayerRepository(TestDatabase.dsl),
                referenceDataService,
                mockk<LotQuery> { every { lot(sold.id) } returns sold },
                mockk<TradeQuery> { every { obligation(scheduleId) } returns delivery },
                clock,
                fixings = fixings,
            )
            val lineId = UUID.randomUUID()
            val line = mockk<AllocationLineView>(relaxed = true) {
                every { id } returns lineId
                every { reservedLotId } returns sold.id
                every { quantityMwh } returns 100L
            }
            val obligation = mockk<ObligationView>(relaxed = true) {
                every { kind } returns ObligationKind.SALE
                every { holderEntityId } returns entityId
                every { sourceRef } returns scheduleId
                every { tradeRef } returns "T-IDX"
                every { counterpartyId } returns null
            }
            sale.on(AllocationLineConfirmed(mockk(relaxed = true), line, obligation))
            return lineId
        }

        val fixed = ledger.entries(entityId, sourceRef = deliver(fixedOn).toString())
        fixed.single { it.ruleCode == "GOO_SALE_DELIVERY_REVENUE" }.lines.single { it.accountCode == "3000" }.credit shouldBe BigDecimal("510.00")
        fixed.single { it.ruleCode == "GOO_SALE_DELIVERY_COGS" }.totalDebit shouldBe BigDecimal("50.00")
        // Not fixed yet: the cost of sales is booked, the revenue waits rather than being booked at the spread.
        val unfixed = ledger.entries(entityId, sourceRef = deliver(fixedOn.plusDays(1)).toString())
        unfixed.map { it.ruleCode } shouldBe listOf("GOO_SALE_DELIVERY_COGS")
    }

    @Test
    fun `weighted average costing preserves the average across consumptions`() {
        val layers = listOf(
            CostLayer(UUID.randomUUID(), wacEntityId, "A", LocalDate.of(2026, 1, 1), BigDecimal(100), BigDecimal(100), BigDecimal("10"), "SEK", null),
            CostLayer(UUID.randomUUID(), wacEntityId, "A", LocalDate.of(2026, 2, 1), BigDecimal(100), BigDecimal(100), BigDecimal("20"), "SEK", null),
        )
        val wac = CostingEngine.consume(layers, BigDecimal(50), CostingMethod.WEIGHTED_AVERAGE)
        wac.cost shouldBe BigDecimal("750.00")
        wac.consumed.sumOf { it.second } shouldBe BigDecimal(50)
        // Whole units only (review finding F10): one unit out of two equal layers comes from one of them, not half from each.
        CostingEngine.consume(layers, BigDecimal.ONE, CostingMethod.WEIGHTED_AVERAGE).consumed.map { it.second } shouldBe listOf(BigDecimal.ONE)
        CostingEngine.consume(layers, BigDecimal(150), CostingMethod.FIFO).cost shouldBe BigDecimal("2000.00")
        shouldThrow<ValidationException> { CostingEngine.consume(layers, BigDecimal(201), CostingMethod.FIFO) }
    }

    @Test
    fun `manual journals need FX, periods gate postings, reversals restore balances and exports are hashed`() {
        shouldThrow<ValidationException> {
            ledger.postManual(
                ManualJournal(
                    wacEntityId,
                    LocalDate.of(2026, 9, 1),
                    "EUR adjustment",
                    "EUR",
                    listOf(ManualLine("1400", debit = BigDecimal(100)), ManualLine("2200", credit = BigDecimal(100))),
                ),
            )
        }
        fx.put(LocalDate.of(2026, 8, 31), "EUR", "SEK", BigDecimal("11.20"))
        val entry = ledger.postManual(
            ManualJournal(
                wacEntityId,
                LocalDate.of(2026, 9, 1),
                "EUR adjustment",
                "EUR",
                listOf(ManualLine("1400", debit = BigDecimal(100)), ManualLine("2200", credit = BigDecimal(100))),
            ),
        )
        entry.lines.first().baseDebit shouldBe BigDecimal("1120.00")
        shouldThrow<ValidationException> {
            ledger.postManual(
                ManualJournal(
                    wacEntityId,
                    LocalDate.of(2026, 9, 2),
                    "unbalanced",
                    "SEK",
                    listOf(ManualLine("1400", debit = BigDecimal(5)), ManualLine("2200", credit = BigDecimal(4))),
                ),
            )
        }

        val balanceBefore = ledger.trialBalance(wacEntityId, YearMonth.of(2026, 9)).first { it.accountCode == "1400" }.balance
        val reversal = ledger.reverse(entry.id, "posted in error")
        reversal.status shouldBe EntryStatus.REVERSAL
        ledger.entry(entry.id)!!.status shouldBe EntryStatus.REVERSED
        ledger.trialBalance(wacEntityId, YearMonth.of(2026, 9)).first { it.accountCode == "1400" }.balance shouldBe balanceBefore - BigDecimal("1120.00")

        ledger.closePeriod(wacEntityId, YearMonth.of(2026, 9), hard = true)
        shouldThrow<ValidationException> {
            ledger.postManual(
                ManualJournal(
                    wacEntityId,
                    LocalDate.of(2026, 9, 20),
                    "late",
                    "SEK",
                    listOf(ManualLine("1400", debit = BigDecimal(1)), ManualLine("2200", credit = BigDecimal(1))),
                ),
            )
        }
        shouldThrow<ValidationException> { ledger.reopenPeriod(wacEntityId, YearMonth.of(2026, 9)) }
        // A hard close is final: it cannot be soft-closed (and then reopened) or closed again.
        shouldThrow<ValidationException> { ledger.closePeriod(wacEntityId, YearMonth.of(2026, 9), hard = false) }
        shouldThrow<ValidationException> { ledger.closePeriod(wacEntityId, YearMonth.of(2026, 9), hard = true) }
        shouldThrow<Exception> {
            TestDatabase.dsl.execute("UPDATE etrm_ledger.accounting_period SET status = 'OPEN' WHERE legal_entity_id = ? AND period = '2026-09'", wacEntityId)
        }
        // Open periods are not exported.
        shouldThrow<ValidationException> { ledger.exportGl(wacEntityId, YearMonth.of(2026, 10)) }

        val export = ledger.exportGl(wacEntityId, YearMonth.of(2026, 9))
        export.entryCount shouldBe 2
        // Each entry is exported once: nothing new, nothing to export.
        shouldThrow<ValidationException> { ledger.exportGl(wacEntityId, YearMonth.of(2026, 9)) }
        export.content shouldContain "140000"
        export.contentHash.length shouldBe 64
        ledger.verifyJournal(wacEntityId).shouldBeEmpty()
        // RFC 4180 with plain two-decimal amounts.
        val rows = Csv.parse(export.content)
        rows.first() shouldBe GlExportFormat.header
        rows.drop(1).forEach { row -> row[5] shouldMatch Regex("[0-9]+\\.[0-9]{2}") }
    }

    @Test
    fun `a soft-closed period can be reopened but not soft-closed twice`() {
        val period = YearMonth.of(2026, 5)
        ledger.closePeriod(entityId, period, hard = false)
        shouldThrow<ValidationException> { ledger.closePeriod(entityId, period, hard = false) }
        ledger.reopenPeriod(entityId, period).status shouldBe PeriodStatus.OPEN
        shouldThrow<ValidationException> { ledger.reopenPeriod(entityId, period) }
    }

    @Test
    fun `memo accounts are not exported`() {
        val memoEntity = referenceDataService.createLegalEntity(CreateLegalEntity("MEMO-CO", "Memo Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        ledger.postManual(
            ManualJournal(
                memoEntity,
                LocalDate.of(2026, 4, 2),
                "memo",
                "EUR",
                listOf(ManualLine("9000", debit = BigDecimal(5)), ManualLine("9009", credit = BigDecimal(5))),
            ),
        )
        ledger.postManual(
            ManualJournal(
                memoEntity,
                LocalDate.of(2026, 4, 3),
                "real",
                "EUR",
                listOf(ManualLine("1400", debit = BigDecimal(7)), ManualLine("2200", credit = BigDecimal(7))),
            ),
        )
        ledger.closePeriod(memoEntity, YearMonth.of(2026, 4), hard = false)
        val rows = Csv.parse(ledger.exportGl(memoEntity, YearMonth.of(2026, 4)).content).drop(1)
        rows.map { it[3] }.toSet() shouldBe setOf("1400", "2200")
    }
}
