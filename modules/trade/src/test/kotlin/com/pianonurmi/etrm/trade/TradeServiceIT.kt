package com.pianonurmi.etrm.trade

import com.pianonurmi.etrm.marketdata.api.FxRateView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.MarketDataQuery
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.platform.errors.ForbiddenException
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
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqFuturesContractRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqProductBucketRepository
import com.pianonurmi.etrm.product.api.BucketMatchKind
import com.pianonurmi.etrm.product.api.CreateFuturesContract
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.FuturesContractService
import com.pianonurmi.etrm.product.application.ProductBucketSeeder
import com.pianonurmi.etrm.product.application.ProductBucketService
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import com.pianonurmi.etrm.product.application.TaxonomySnapshots
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CreateBook
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ObligationsOpenAt
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.adapter.jooq.JooqTradeRepository
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.CaptureDelivery
import com.pianonurmi.etrm.trade.api.CaptureLeg
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ClearingStatus
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.FindingSeverity
import com.pianonurmi.etrm.trade.api.OptionExpired
import com.pianonurmi.etrm.trade.api.OptionOutcome
import com.pianonurmi.etrm.trade.api.OptionTerms
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.RegulatoryTerms
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.api.UtiSource
import com.pianonurmi.etrm.trade.application.OptionExpiryService
import com.pianonurmi.etrm.trade.application.TradeService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeServiceIT {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private var currentUser = "trader1"
    private var override: Actor? = null
    private lateinit var tradeService: (BigDecimal, FxRates) -> TradeService
    private lateinit var checkedService: (TradeApprovalCheck) -> TradeService
    private lateinit var futures: FuturesContractService
    private lateinit var referenceDataService: ReferenceDataService
    private val actor = ActorProvider { override ?: Actor(currentUser, currentUser, setOf(Role.TRADER, Role.MIDDLE_OFFICE), allEntities = true) }

    private lateinit var trades: TradeService
    private lateinit var entityA: UUID
    private lateinit var entityB: UUID
    private lateinit var bookA: UUID
    private lateinit var bookB: UUID
    private lateinit var external: UUID
    private lateinit var icoB: UUID
    private lateinit var accountA: UUID
    private lateinit var externalAccount: UUID
    private lateinit var otherCounterparty: UUID
    private lateinit var entityEarly: UUID
    private lateinit var bookEarly: UUID
    private lateinit var accountEarly: UUID

    @BeforeAll
    @Suppress("LongMethod") // hand-wired services and reference data
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "trade")
        val dsl = TestDatabase.dsl
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        referenceDataService = referenceData
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        val buckets = ProductBucketService(JooqProductBucketRepository(dsl), criteria)
        ProductBucketSeeder(buckets, buckets).seed()
        val catalogue = ProductCatalogueService(JooqAllowanceProductRepository(dsl))
        futures = FuturesContractService(JooqFuturesContractRepository(dsl), catalogue, actor)
        tradeService = { threshold, fx ->
            TradeService(
                JooqTradeRepository(dsl),
                referenceData,
                criteria,
                buckets,
                catalogue,
                actor,
                publisher,
                Clock.systemUTC(),
                threshold,
                fx,
                "EUR",
                futures,
                Providers.none(),
            )
        }
        trades = tradeService(BigDecimal.ZERO, mockk<FxRates>(relaxed = true))
        checkedService = { check ->
            val identity =
                mockk<FxRates> {
                    every { rate(any(), any(), any()) } answers { FxRateView(firstArg(), secondArg(), thirdArg(), BigDecimal.ONE, "1", firstArg()) }
                }
            TradeService(
                JooqTradeRepository(dsl),
                referenceData,
                criteria,
                buckets,
                catalogue,
                actor,
                publisher,
                Clock.systemUTC(),
                BigDecimal("1000000000"),
                identity,
                "EUR",
                futures,
                StaticListableBeanFactory().apply { addBean("check", check) }.getBeanProvider(TradeApprovalCheck::class.java),
            )
        }

        entityA = referenceData.createLegalEntity(CreateLegalEntity("ENTITY-A", "Entity A Oy", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        entityB = referenceData.createLegalEntity(CreateLegalEntity("ENTITY-B", "Entity B AB", "SE", validFrom = LocalDate.of(2026, 1, 1))).id
        bookA = referenceData.createBook(CreateBook(entityA, "GOO-A", "GoO trading A", BookType.TRADING)).id
        bookB = referenceData.createBook(CreateBook(entityB, "GOO-B", "GoO trading B", BookType.TRADING)).id
        external = referenceData.createCounterparty(CreateCounterparty("EXT-CP", "External Energy", "DE")).id
        icoB = referenceData.createCounterparty(
            CreateCounterparty("ICO-B", "Entity B (intercompany)", "SE", counterpartyType = CounterpartyType.INTERCOMPANY, linkedLegalEntityId = entityB),
        ).id
        referenceData.createCounterparty(
            CreateCounterparty("ICO-A", "Entity A (intercompany)", "FI", counterpartyType = CounterpartyType.INTERCOMPANY, linkedLegalEntityId = entityA),
        )
        accountA = referenceData.createRegistryAccount(
            CreateRegistryAccount("FINGRID", "FI-A-1", "A holding", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entityA),
        ).id
        otherCounterparty = referenceData.createCounterparty(CreateCounterparty("OTHER-CP", "Other Energy", "DE")).id
        entityEarly = referenceData.createLegalEntity(
            CreateLegalEntity("ENTITY-E", "Early Delivery Oy", "FI", validFrom = LocalDate.of(2026, 1, 1), obligationsOpenAt = ObligationsOpenAt.APPROVED),
        ).id
        bookEarly = referenceData.createBook(CreateBook(entityEarly, "GOO-E", "GoO trading E", BookType.TRADING)).id
        accountEarly = referenceData.createRegistryAccount(
            CreateRegistryAccount("FINGRID", "FI-E-1", "E holding", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entityEarly),
        ).id
        // Counterparty accounts normally arrive through a four-eyes change request; inserted directly here.
        val hknr = referenceData.requireRegistry("HKNR")
        externalAccount = Ids.newId()
        JooqReferenceDataRepository(dsl).insertRegistryAccount(
            RegistryAccountView(
                externalAccount,
                hknr.id,
                "HKNR",
                "DE-4711",
                "External holding",
                RegistryAccountType.HOLDING,
                HolderType.COUNTERPARTY,
                external,
                canImport = true,
                canExport = false,
                trusted = false,
                active = true,
                version = 1,
            ),
        )
    }

    @Test
    fun `counterparty accounts are linked as records and must belong to the counterparty`() {
        val byRef = trades.capture(gooSale().let { it.copy(legs = it.legs.map { leg -> leg.copy(counterpartyAccountRef = "hknr:DE-4711") }) })
        byRef.legs.single().let {
            it.counterpartyAccountId shouldBe externalAccount
            it.counterpartyAccountRef shouldBe "HKNR:DE-4711"
        }
        val byId = trades.capture(
            gooSale().let {
                it.copy(
                    legs = it.legs.map { leg ->
                        leg.copy(counterpartyAccountRef = null, counterpartyAccountId = externalAccount)
                    },
                )
            },
        )
        byId.legs.single().counterpartyAccountRef shouldBe "HKNR:DE-4711"
        shouldThrow<ValidationException> {
            trades.capture(
                gooSale(counterparty = otherCounterparty).let { it.copy(legs = it.legs.map { leg -> leg.copy(counterpartyAccountId = externalAccount) }) },
            )
        }
        // Free text that matches no registered account stays text.
        trades.capture(gooSale()).legs.single().let {
            it.counterpartyAccountId shouldBe null
            it.counterpartyAccountRef shouldBe "DE-HKNR-4711"
        }
    }

    private fun gooSale(quantity: Long = 5_000, counterparty: UUID = external, mirrorBook: UUID? = null) = CaptureTrade(
        legalEntityId = entityA,
        bookId = bookA,
        counterpartyId = counterparty,
        productFamily = ProductFamily.GUARANTEE_OF_ORIGIN,
        instrumentType = InstrumentType.FORWARD,
        tradeDate = LocalDate.of(2026, 3, 2),
        legs = listOf(
            CaptureLeg(
                side = Side.SELL,
                criterion = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"), Criterion.productionYear(2026)),
                quantity = BigDecimal(quantity),
                unit = QuantityUnit.MWH,
                price = BigDecimal("1.85"),
                deliveryAccountId = accountA,
                counterpartyAccountRef = "DE-HKNR-4711",
                deliveries = listOf(
                    CaptureDelivery(LocalDateRange.ofQuarter(2026, 3), LocalDate.of(2026, 9, 30), BigDecimal(quantity / 2)),
                    CaptureDelivery(LocalDateRange.ofQuarter(2026, 4), LocalDate.of(2026, 12, 31), BigDecimal(quantity - quantity / 2)),
                ),
            ),
        ),
        mirrorBookId = mirrorBook,
    )

    @Test
    fun `capture, four-eyes approval, confirmation, deliveries and settlement`() {
        currentUser = "trader1"
        val trade = trades.capture(gooSale())
        trade.tradeNumber shouldStartWith "T-2026-"
        trade.status shouldBe TradeStatus.PENDING_APPROVAL
        trade.legs.single().criteriaSummary shouldBe "device in region NORDIC AND energy source within HYDRO AND vintage 2026"
        // Nordic hydro of 2026 is provably within the Nordic hydro bucket.
        trade.legs.single().bucketCode shouldBe "NORDIC_HYDRO"
        trade.legs.single().bucketMatch shouldBe BucketMatchKind.IMPLIED
        trade.notional shouldBe BigDecimal("9250.00")

        shouldThrow<SegregationOfDutiesException> { trades.approve(trade.id) }
        currentUser = "middle-office"
        val approved = trades.approve(trade.id)
        approved.status shouldBe TradeStatus.APPROVED
        approved.approvedBy shouldBe "middle-office"

        val confirmed = trades.confirm(trade.id, ConfirmTrade("CONF-123"))
        confirmed.status shouldBe TradeStatus.CONFIRMED
        published.filterIsInstance<TradeConfirmed>().single { it.trade.id == trade.id }.trade.status shouldBe TradeStatus.CONFIRMED
        published.filterIsInstance<DeliveryObligationOpened>().filter { it.obligation.tradeId == trade.id } shouldHaveSize 2
        trades.openObligations(entityA, Side.SELL, ProductFamily.GUARANTEE_OF_ORIGIN).filter { it.tradeId == trade.id }.map {
            it.remainingQuantity
        } shouldBe listOf(BigDecimal("2500.000"), BigDecimal("2500.000"))

        val q3 = confirmed.legs.single().deliveries.first()
        val partial = trades.recordDelivery(trade.id, RecordDelivery(q3.id, BigDecimal(1_000), LocalDate.of(2026, 9, 10), "TX-1", "registry:TX-1"))
        partial.status shouldBe TradeStatus.PARTIALLY_DELIVERED
        val firstDelivery = published.filterIsInstance<DeliveryRecorded>().single { it.trade.id == trade.id }
        // A second report of the same delivery (same idempotency key) changes nothing and publishes nothing.
        trades.recordDelivery(trade.id, RecordDelivery(q3.id, BigDecimal(1_000), LocalDate.of(2026, 9, 10), "TX-1", "registry:TX-1"))
            .legs.single().deliveries.first().deliveredQuantity.compareTo(BigDecimal(1_000)) shouldBe 0
        published.filterIsInstance<DeliveryRecorded>().filter { it.trade.id == trade.id }.map { it.deliveryId } shouldBe listOf(firstDelivery.deliveryId)
        partial.legs.single().deliveries.first().status shouldBe DeliveryStatus.PARTIALLY_DELIVERED
        shouldThrow<ValidationException> { trades.recordDelivery(trade.id, RecordDelivery(q3.id, BigDecimal(2_000), LocalDate.of(2026, 9, 11))) }
        trades.recordDelivery(trade.id, RecordDelivery(q3.id, BigDecimal(1_500), LocalDate.of(2026, 9, 11), "TX-2"))
        published.filterIsInstance<DeliveryObligationClosed>().any { it.obligation.scheduleId == q3.id } shouldBe true
        val q4 = confirmed.legs.single().deliveries.last()
        val delivered = trades.recordDelivery(trade.id, RecordDelivery(q4.id, BigDecimal(2_500), LocalDate.of(2026, 12, 20), "TX-3"))
        delivered.status shouldBe TradeStatus.DELIVERED
        trades.openObligations(entityA).none { it.tradeId == trade.id } shouldBe true
        trades.markSettled(trade.id).status shouldBe TradeStatus.SETTLED
        shouldThrow<IllegalStateTransitionException> { trades.cancel(trade.id, "too late") }

        val versions = trades.versions(trade.id)
        versions.map { it.version } shouldBe (1..versions.size).toList()
        versions.dropLast(1).all { it.supersededAt != null } shouldBe true
        versions.last().supersededAt shouldBe null
        versions.first().trade.status shouldBe TradeStatus.DRAFT
    }

    @Test
    fun `amendment of a confirmed trade re-opens approval and replaces obligations`() {
        currentUser = "trader1"
        val trade = trades.capture(gooSale(quantity = 1_000))
        currentUser = "approver"
        trades.approve(trade.id)
        trades.confirm(trade.id, ConfirmTrade("CONF-2"))
        val before = published.size
        val amended = trades.amend(
            trade.id,
            AmendTrade(expectedVersion = 4, reason = "quantity corrected", legs = gooSale(quantity = 2_000).legs),
        )
        amended.status shouldBe TradeStatus.PENDING_APPROVAL
        amended.legs.single().quantity shouldBe BigDecimal(2_000)
        published.drop(before).filterIsInstance<DeliveryObligationClosed>() shouldHaveSize 2
        trades.openObligations(entityA).none { it.tradeId == trade.id } shouldBe true
        shouldThrow<com.pianonurmi.etrm.platform.errors.StaleVersionException> { trades.amend(trade.id, AmendTrade(expectedVersion = 4, reason = "stale")) }
        // The amended trade is approved and confirmed again on its new terms.
        amended.confirmedAt shouldBe null
        currentUser = "approver"
        trades.approve(trade.id)
        trades.confirm(trade.id, ConfirmTrade("CONF-2B")).let {
            it.status shouldBe TradeStatus.CONFIRMED
            it.confirmationRef shouldBe "CONF-2B"
        }
    }

    @Test
    fun `a pre-trade check can require approval below the threshold, and then only risk may approve`() {
        val check = TradeApprovalCheck { trade ->
            if (trade.notional > BigDecimal(10_000)) {
                listOf(ApprovalFinding("CREDIT_LIMIT", FindingSeverity.REQUIRES_RISK_APPROVAL, "over the limit"))
            } else {
                listOf(ApprovalFinding("NO_CREDIT_LIMIT", FindingSeverity.WARN, "no limit"))
            }
        }
        val checked = checkedService(check)
        currentUser = "trader1"
        checked.capture(gooSale(quantity = 100)).let {
            it.status shouldBe TradeStatus.APPROVED
            it.approvalFindings.single().severity shouldBe FindingSeverity.WARN
        }
        val large = checked.capture(gooSale(quantity = 50_000))
        large.status shouldBe TradeStatus.PENDING_APPROVAL
        large.requiresRiskApproval shouldBe true
        checked.trade(large.id)!!.approvalFindings.single().code shouldBe "CREDIT_LIMIT"
        override = Actor("middle1", "m", setOf(Role.MIDDLE_OFFICE), allEntities = true)
        shouldThrow<ForbiddenException> { checked.approve(large.id) }
        override = Actor("risk1", "r", setOf(Role.RISK), allEntities = true)
        checked.approve(large.id).status shouldBe TradeStatus.APPROVED
        override = null
    }

    @Test
    fun `the approval threshold applies to the notional converted at the trade date's rate`() {
        val fx = mockk<FxRates> {
            every {
                rate(LocalDate.of(2026, 3, 2), "SEK", "EUR")
            } returns FxRateView(LocalDate.of(2026, 3, 2), "SEK", "EUR", BigDecimal("0.09"), "SEK/EUR", LocalDate.of(2026, 3, 2))
            every { rate(any(), "USD", "EUR") } returns null
        }
        val thresholded = tradeService(BigDecimal(100_000), fx)

        fun sale(quantity: Long, price: String, currency: String) = gooSale(quantity).let { c ->
            c.copy(legs = c.legs.map { it.copy(price = BigDecimal(price), priceCurrency = currency) })
        }
        currentUser = "trader1"
        // 50 000 MWh at SEK 20 = SEK 1 000 000 = EUR 90 000: below the threshold.
        thresholded.capture(sale(50_000, "20", "SEK")).status shouldBe TradeStatus.APPROVED
        // SEK 1 500 000 = EUR 135 000: four eyes.
        thresholded.capture(sale(50_000, "30", "SEK")).status shouldBe TradeStatus.PENDING_APPROVAL
        // A currency without a rate cannot be shown to be below the threshold.
        thresholded.capture(sale(10, "1", "USD")).status shouldBe TradeStatus.PENDING_APPROVAL
    }

    private fun euaTrade(
        entity: UUID,
        book: UUID,
        counterparty: UUID,
        instrument: InstrumentType,
        delivery: LocalDate,
        quantity: Long = 5_000,
        contract: String? = null,
        regulatory: RegulatoryTerms? = null,
    ) = CaptureTrade(
        legalEntityId = entity,
        bookId = book,
        counterpartyId = counterparty,
        productFamily = ProductFamily.EMISSION_ALLOWANCE,
        instrumentType = instrument,
        tradeDate = LocalDate.of(2026, 10, 9),
        legs = listOf(
            CaptureLeg(
                side = Side.BUY,
                allowanceProductCode = "EUA",
                quantity = BigDecimal(quantity),
                unit = QuantityUnit.TCO2E,
                price = BigDecimal("72.50"),
                deliveries = listOf(CaptureDelivery(LocalDateRange(delivery, delivery.plusDays(1)), delivery, BigDecimal(quantity))),
                futuresContractCode = contract,
            ),
        ),
        regulatory = regulatory,
    )

    @Test
    fun `allowance derivatives get a UTI from the entity's LEI that survives amendments, spot trades are not reportable`() {
        currentUser = "trader1"
        val lei = "743700AAAAAAAAAAAA24"
        val entity = referenceDataService.createLegalEntity(CreateLegalEntity("REG-CO", "Reg Co", "FI", lei = lei, validFrom = LocalDate.of(2026, 1, 1))).id
        val book = referenceDataService.createBook(CreateBook(entity, "EUA-T", "EUA trading", BookType.TRADING)).id
        val forward = trades.capture(euaTrade(entity, book, external, InstrumentType.FORWARD, LocalDate.of(2026, 12, 15)))
        forward.emirReportable shouldBe true
        forward.regulatory.uti!! shouldStartWith lei
        forward.regulatory.utiSource shouldBe UtiSource.OWN
        val amended = trades.amend(forward.id, AmendTrade(expectedVersion = forward.version, reason = "venue", regulatory = RegulatoryTerms(venueMic = "XOFF")))
        amended.regulatory.uti shouldBe forward.regulatory.uti
        amended.regulatory.venueMic shouldBe "XOFF"
        shouldThrow<ValidationException> {
            trades.amend(forward.id, AmendTrade(expectedVersion = amended.version, reason = "uti", regulatory = RegulatoryTerms(uti = lei + "OTHER")))
        }
        shouldThrow<ValidationException> {
            trades.capture(
                euaTrade(entity, book, external, InstrumentType.FORWARD, LocalDate.of(2026, 12, 15), regulatory = RegulatoryTerms(venueMic = "nope")),
            )
        }
        // Delivery two working days after a Friday trade is spot.
        trades.capture(euaTrade(entity, book, external, InstrumentType.FORWARD, LocalDate.of(2026, 10, 13))).let {
            it.emirReportable shouldBe false
            it.regulatory.uti shouldBe null
        }
        // The counterparty generates the UTI: none of ours.
        trades.capture(
            euaTrade(
                entity,
                book,
                external,
                InstrumentType.FORWARD,
                LocalDate.of(2027, 3, 1),
                regulatory = RegulatoryTerms(utiSource = UtiSource.COUNTERPARTY),
            ),
        ).regulatory.uti shouldBe null
    }

    @Test
    fun `futures deliver once in whole lots on the contract's venue and are cleared`() {
        currentUser = "trader1"
        val entity = referenceDataService.createLegalEntity(CreateLegalEntity("FUT-CO", "Fut Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val book = referenceDataService.createBook(CreateBook(entity, "EUA-F", "EUA futures", BookType.HEDGING)).id
        val ccp = referenceDataService.createCounterparty(
            CreateCounterparty("ECC", "European Commodity Clearing", "DE", counterpartyType = CounterpartyType.CLEARING_HOUSE),
        ).id
        override = Actor("mo", "MO", setOf(Role.MIDDLE_OFFICE), allEntities = true)
        futures.create(
            CreateFuturesContract(
                "TST_EUA_DEC26",
                "EUA",
                "XEEE",
                YearMonth.of(2026, 12),
                LocalDate.of(2026, 12, 21),
                LocalDate.of(2026, 12, 21),
                BigDecimal(1000),
            ),
        )
        override = null
        val dec = LocalDate.of(2026, 12, 21)
        trades.capture(euaTrade(entity, book, ccp, InstrumentType.FUTURE, dec, contract = "TST_EUA_DEC26")).let {
            it.regulatory.venueMic shouldBe "XEEE"
            it.regulatory.clearingStatus shouldBe ClearingStatus.CLEARED
            it.regulatory.ccpCounterpartyId shouldBe ccp
            it.legs.single().futuresContractCode shouldBe "TST_EUA_DEC26"
        }
        shouldThrow<ValidationException> { trades.capture(euaTrade(entity, book, external, InstrumentType.FUTURE, dec, contract = "TST_EUA_DEC26")) }
        shouldThrow<ValidationException> {
            trades.capture(euaTrade(entity, book, ccp, InstrumentType.FUTURE, dec, quantity = 1_500, contract = "TST_EUA_DEC26"))
        }
        shouldThrow<ValidationException> { trades.capture(euaTrade(entity, book, ccp, InstrumentType.FUTURE, dec.minusDays(1), contract = "TST_EUA_DEC26")) }
        shouldThrow<ValidationException> { trades.capture(euaTrade(entity, book, ccp, InstrumentType.FUTURE, dec)) }
        shouldThrow<ValidationException> {
            trades.capture(
                euaTrade(entity, book, ccp, InstrumentType.FUTURE, dec, contract = "TST_EUA_DEC26").let { c ->
                    c.copy(legs = c.legs.map { it.copy(isin = "DE000A1234567") })
                },
            )
        }

        // An option on the future (ADR 0047): no deliveries, cleared like its underlying, expiring by its last trading day.
        val option = { terms: OptionTerms?, quantity: Long, instrument: InstrumentType ->
            euaTrade(entity, book, ccp, instrument, dec, quantity = quantity).let { c ->
                c.copy(legs = c.legs.map { it.copy(deliveries = emptyList(), price = BigDecimal("4.20"), option = terms) })
            }
        }
        val call = OptionTerms(OptionType.CALL, BigDecimal("80"), LocalDate.of(2026, 12, 16), "tst_eua_dec26")
        trades.capture(option(call, 5_000, InstrumentType.OPTION)).let {
            it.regulatory.clearingStatus shouldBe ClearingStatus.CLEARED
            it.regulatory.ccpCounterpartyId shouldBe ccp
            it.legs.single().option!!.underlyingContractCode shouldBe "TST_EUA_DEC26"
            it.legs.single().deliveries shouldBe emptyList()
            it.legs.single().futuresContractCode shouldBe null
        }
        shouldThrow<ValidationException> { trades.capture(option(null, 5_000, InstrumentType.OPTION)) }.message!! shouldContain "option terms"
        shouldThrow<ValidationException> { trades.capture(option(call, 5_000, InstrumentType.FORWARD)) }.message!! shouldContain "Only options"
        shouldThrow<ValidationException> { trades.capture(option(call, 1_500, InstrumentType.OPTION)) }.message!! shouldContain "whole lots"
        shouldThrow<ValidationException> {
            trades.capture(option(call.copy(expiryDate = LocalDate.of(2026, 12, 22)), 5_000, InstrumentType.OPTION))
        }.message!! shouldContain "last trading day"
        shouldThrow<ValidationException> { trades.capture(option(call.copy(strike = BigDecimal.ZERO), 5_000, InstrumentType.OPTION)) }
        shouldThrow<ValidationException> {
            trades.capture(euaTrade(entity, book, ccp, InstrumentType.OPTION, dec).let { c -> c.copy(legs = c.legs.map { it.copy(option = call) }) })
        }.message!! shouldContain "no delivery schedule"
        shouldThrow<ValidationException> {
            trades.capture(option(call, 5_000, InstrumentType.OPTION).copy(counterpartyId = external))
        }.message!! shouldContain "exchange or clearing house"
    }

    @Test
    fun `options at expiry are exercised into the future at the strike when in the money and abandoned otherwise`() {
        currentUser = "trader1"
        val entity = referenceDataService.createLegalEntity(CreateLegalEntity("OPT-CO", "Opt Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val book = referenceDataService.createBook(CreateBook(entity, "EUA-O", "EUA options", BookType.HEDGING)).id
        val ccp = referenceDataService.createCounterparty(
            CreateCounterparty("ECC-OPT", "European Commodity Clearing", "DE", counterpartyType = CounterpartyType.CLEARING_HOUSE),
        ).id
        override = Actor("mo", "MO", setOf(Role.MIDDLE_OFFICE), allEntities = true)
        val dec27 = LocalDate.of(2027, 12, 20)
        futures.create(CreateFuturesContract("TST_EUA_DEC27", "EUA", "XEEE", YearMonth.of(2027, 12), dec27, dec27, BigDecimal(1000)))
        override = null
        val expiry = LocalDate.of(2027, 12, 15)
        val settlement = mutableMapOf<LocalDate, BigDecimal>()
        val marketData = mockk<MarketDataQuery> {
            every { instruments(InstrumentKind.EUA_FUTURE, any()) } returns listOf(
                mockk {
                    every { code } returns "TST_DEC27"
                    every { futuresContractCode } returns "TST_EUA_DEC27"
                },
            )
            every { latestQuote("TST_DEC27", QuoteType.SETTLEMENT, any(), any()) } answers {
                val date = thirdArg<LocalDate>()
                settlement[date]?.let { price ->
                    mockk {
                        every { quoteDate } returns date
                        every { value } returns price
                    }
                }
            }
        }
        val expiries = OptionExpiryService(JooqTradeRepository(TestDatabase.dsl), trades, marketData, futures)

        fun confirmed(side: Side, terms: OptionTerms): TradeView {
            currentUser = "trader1"
            val captured = trades.capture(
                euaTrade(entity, book, ccp, InstrumentType.OPTION, dec27).let { c ->
                    c.copy(legs = c.legs.map { it.copy(side = side, deliveries = emptyList(), price = BigDecimal("4.20"), option = terms) })
                },
            )
            currentUser = "middle-office"
            trades.approve(captured.id)
            return trades.confirm(captured.id, ConfirmTrade("EEX-${captured.tradeNumber}"))
        }
        val boughtCall = confirmed(Side.BUY, OptionTerms(OptionType.CALL, BigDecimal("80"), expiry, "TST_EUA_DEC27"))
        val soldPut = confirmed(Side.SELL, OptionTerms(OptionType.PUT, BigDecimal("70"), expiry, "TST_EUA_DEC27"))
        expiries.due(expiry.minusDays(1)).filter { it == boughtCall.id || it == soldPut.id } shouldBe emptyList()

        // Without the settlement price of the expiry date nothing is decided.
        expiries.expire(boughtCall.id, expiry)!!.status shouldBe TradeStatus.CONFIRMED
        settlement[expiry] = BigDecimal("85.40")
        expiries.due(expiry).filter { it == boughtCall.id || it == soldPut.id }.toSet() shouldBe setOf(boughtCall.id, soldPut.id)

        // The call is in the money: the system buys the future at the strike, confirmed and cleared, and closes the option.
        val exercised = expiries.expire(boughtCall.id, expiry)!!
        exercised.status shouldBe TradeStatus.CLOSED
        val outcome = exercised.legs.single().expiry!!
        outcome.outcome shouldBe OptionOutcome.EXERCISED
        outcome.settlementPrice shouldBe BigDecimal("85.40")
        val future = trades.requireTrade(outcome.futureTradeId!!)
        future.instrumentType shouldBe InstrumentType.FUTURE
        future.status shouldBe TradeStatus.CONFIRMED
        future.tradeDate shouldBe expiry
        future.externalRef shouldBe "exercise:${boughtCall.tradeNumber}"
        future.regulatory.ccpCounterpartyId shouldBe ccp
        future.legs.single().let {
            it.side shouldBe Side.BUY
            it.price.compareTo(BigDecimal("80")) shouldBe 0
            it.quantity.compareTo(BigDecimal(5_000)) shouldBe 0
            it.futuresContractCode shouldBe "TST_EUA_DEC27"
            it.deliveries.single().deliveryDate shouldBe dec27
        }
        published.filterIsInstance<OptionExpired>().single { it.trade.id == boughtCall.id }.future!!.id shouldBe future.id

        // The sold put is out of the money: abandoned, no future.
        expiries.expire(soldPut.id, expiry)!!.legs.single().expiry!!.let {
            it.outcome shouldBe OptionOutcome.ABANDONED
            it.futureTradeId shouldBe null
        }
        expiries.expire(soldPut.id, expiry) shouldBe null
        expiries.due(expiry).filter { it == boughtCall.id || it == soldPut.id } shouldBe emptyList()
        // A decided option can be neither amended nor cancelled.
        currentUser = "trader1"
        shouldThrow<IllegalStateTransitionException> { trades.cancel(boughtCall.id, "too late") }
    }

    @Test
    fun `trade commands need trading roles and access to the entity`() {
        currentUser = "trader1"
        val trade = trades.capture(gooSale(quantity = 100))
        val outsider = Actor("outsider", "Outsider", setOf(Role.TRADER, Role.MIDDLE_OFFICE), legalEntityScope = setOf(UUID.randomUUID()))
        val accountant = Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true)
        override = outsider
        shouldThrow<ForbiddenException> { trades.capture(gooSale(quantity = 100)) }
        shouldThrow<ForbiddenException> { trades.cancel(trade.id, "not mine") }
        override = accountant
        shouldThrow<ForbiddenException> { trades.approve(trade.id) }
        override = null
    }

    @Test
    fun `intercompany trades are mirrored into the counterparty entity and confirm together`() {
        currentUser = "trader1"
        val trade = trades.capture(gooSale(quantity = 300, counterparty = icoB, mirrorBook = bookB))
        val stored = trades.requireTrade(trade.id)
        stored.intercompanyLinkId shouldNotBe null
        val mirror = trades.requireTrade(stored.intercompanyLinkId!!)
        mirror.legalEntityId shouldBe entityB
        mirror.bookId shouldBe bookB
        mirror.legs.single().side shouldBe Side.BUY
        // The mirror's counterparty account is entity A's own registry account, as a record.
        mirror.legs.single().counterpartyAccountId shouldBe accountA
        mirror.legs.single().counterpartyAccountRef shouldBe "FINGRID:FI-A-1"
        mirror.intercompanyLinkId shouldBe trade.id
        currentUser = "approver"
        trades.approve(trade.id)
        trades.approve(mirror.id)
        trades.confirm(trade.id, ConfirmTrade("ICO-CONF"))
        trades.requireTrade(mirror.id).status shouldBe TradeStatus.CONFIRMED
        trades.openObligations(entityB, Side.BUY).map { it.tradeNumber }.distinct() shouldBe listOf(mirror.tradeNumber)
        shouldThrow<ValidationException> { trades.capture(gooSale(counterparty = icoB)) }
    }

    @Test
    fun `amending an intercompany trade amends its mirror, and cancelling it cancels both`() {
        currentUser = "trader1"
        val trade = trades.capture(gooSale(quantity = 300, counterparty = icoB, mirrorBook = bookB))
        val stored = trades.requireTrade(trade.id)
        val mirrorId = stored.intercompanyLinkId!!
        val mirrorBefore = trades.requireTrade(mirrorId)

        val repriced = gooSale(quantity = 400, counterparty = icoB, mirrorBook = bookB).legs.map { it.copy(price = BigDecimal("2.10")) }
        trades.amend(trade.id, AmendTrade(expectedVersion = stored.version, reason = "re-priced", legs = repriced, externalRef = "BROKER-77"))

        val mirror = trades.requireTrade(mirrorId)
        mirror.version shouldBe mirrorBefore.version + 1
        mirror.status shouldBe TradeStatus.PENDING_APPROVAL
        mirror.externalRef shouldBe "BROKER-77"
        mirror.legs.single().let {
            it.side shouldBe Side.BUY
            it.quantity.compareTo(BigDecimal(400)) shouldBe 0
            it.price.compareTo(BigDecimal("2.10")) shouldBe 0
            it.deliveries.sumOf { d -> d.quantity }.compareTo(BigDecimal(400)) shouldBe 0
            // Its own accounts stay: entity A's account is still the counterparty account of the mirror.
            it.counterpartyAccountId shouldBe accountA
        }
        mirror.regulatory.uti shouldBe trades.requireTrade(trade.id).regulatory.uti
        published.filterIsInstance<TradeAmended>().filter { it.trade.id == mirrorId } shouldHaveSize 1

        // Amending the mirror turns the sides round the other way and reaches the original.
        val fromMirror = repriced.map {
            it.copy(side = Side.BUY, price = BigDecimal("2.05"), deliveryAccountId = null, counterpartyAccountId = accountA, counterpartyAccountRef = null)
        }
        trades.amend(mirrorId, AmendTrade(expectedVersion = mirror.version, reason = "back", legs = fromMirror))
        trades.requireTrade(trade.id).legs.single().let {
            it.side shouldBe Side.SELL
            it.price.compareTo(BigDecimal("2.05")) shouldBe 0
            it.deliveryAccountId shouldBe accountA
        }

        trades.cancel(trade.id, "no deal")
        trades.requireTrade(trade.id).status shouldBe TradeStatus.CANCELLED
        trades.requireTrade(mirrorId).status shouldBe TradeStatus.CANCELLED
        published.filterIsInstance<TradeCancelled>().map { it.trade.id } shouldContainAll listOf(trade.id, mirrorId)
    }

    @Test
    fun `a change the mirror of an intercompany trade cannot take is refused for both`() {
        currentUser = "trader1"
        val trade = trades.capture(gooSale(quantity = 300, counterparty = icoB, mirrorBook = bookB))
        val mirrorId = trades.requireTrade(trade.id).intercompanyLinkId!!
        currentUser = "approver"
        trades.approve(trade.id)
        trades.approve(mirrorId)
        trades.confirm(trade.id, ConfirmTrade("ICO-CONF-2"))
        val mirror = trades.requireTrade(mirrorId)
        mirror.status shouldBe TradeStatus.CONFIRMED
        val first = mirror.legs.single().deliveries.first()
        trades.recordDelivery(mirrorId, RecordDelivery(first.id, BigDecimal(50), LocalDate.of(2026, 9, 1), "TX-IC-1"))

        // The mirror has a delivery recorded, so it cannot be amended, and neither can the original alone.
        val original = trades.requireTrade(trade.id)
        currentUser = "trader1"
        shouldThrow<ValidationException> {
            val repriced = gooSale(300, icoB, bookB).legs.map { it.copy(price = BigDecimal("2.10")) }
            trades.amend(trade.id, AmendTrade(expectedVersion = original.version, reason = "re-price", legs = repriced))
        }.message shouldContain mirror.tradeNumber
        trades.requireTrade(trade.id).let {
            it.version shouldBe original.version
            it.status shouldBe TradeStatus.CONFIRMED
        }

        // Fully delivered, the mirror cannot be cancelled, so the original is not cancelled on its own.
        currentUser = "approver"
        trades.recordDelivery(mirrorId, RecordDelivery(first.id, BigDecimal(100), LocalDate.of(2026, 9, 2), "TX-IC-2"))
        trades.recordDelivery(mirrorId, RecordDelivery(mirror.legs.single().deliveries.last().id, BigDecimal(150), LocalDate.of(2026, 9, 3), "TX-IC-3"))
        trades.requireTrade(mirrorId).status shouldBe TradeStatus.DELIVERED
        currentUser = "trader1"
        shouldThrow<ValidationException> { trades.cancel(trade.id, "too late") }.message shouldContain mirror.tradeNumber
        trades.requireTrade(trade.id).status shouldBe TradeStatus.CONFIRMED
    }

    @Test
    fun `an index leg may carry a negative spread, a fixed price may not, and a delivery lies inside its window`() {
        currentUser = "trader1"
        val sale = gooSale()
        val leg = sale.legs.single()
        val spread = leg.copy(priceType = PriceType.INDEX, priceIndex = "GOO_NORDIC_HYDRO", price = BigDecimal("-0.15"))
        val indexed = trades.capture(sale.copy(legs = listOf(spread)))
        indexed.legs.single().price.compareTo(BigDecimal("-0.15")) shouldBe 0
        shouldThrow<ValidationException> { trades.capture(sale.copy(legs = listOf(leg.copy(price = BigDecimal("-0.15"))))) }.message shouldContain
            "must not be negative"

        // Windows are closed-open: a delivery date on the exclusive end is outside the window.
        val first = leg.deliveries.first()
        val last = leg.deliveries.last()
        val onTheEnd = first.copy(deliveryDate = first.deliveryWindow.endExclusive)
        shouldThrow<ValidationException> {
            trades.capture(sale.copy(legs = listOf(leg.copy(deliveries = listOf(onTheEnd, last)))))
        }.message shouldContain "must lie within"
        val lastDay = first.copy(deliveryDate = first.deliveryWindow.endExclusive.minusDays(1))
        trades.capture(sale.copy(legs = listOf(leg.copy(deliveries = listOf(lastDay, last)))))
    }

    @Test
    fun `where obligations open at approval, deliveries may precede the confirmation`() {
        currentUser = "trader1"
        val trade = trades.capture(
            gooSale(quantity = 100).copy(legalEntityId = entityEarly, bookId = bookEarly).let {
                it.copy(legs = it.legs.map { leg -> leg.copy(deliveryAccountId = accountEarly) })
            },
        )
        trade.obligationsOpenAt shouldBe ObligationsOpenAt.APPROVED
        trade.obligationsOpen shouldBe false
        trades.openObligations(entityEarly).none { it.tradeId == trade.id } shouldBe true
        currentUser = "middle-office"
        val approved = trades.approve(trade.id)
        approved.obligationsOpen shouldBe true
        published.filterIsInstance<DeliveryObligationOpened>().filter { it.obligation.tradeId == trade.id } shouldHaveSize 2
        trades.openObligations(entityEarly).filter { it.tradeId == trade.id } shouldHaveSize 2

        val first = approved.legs.single().deliveries.first()
        trades.recordDelivery(trade.id, RecordDelivery(first.id, BigDecimal(50), LocalDate.of(2026, 9, 10), "TX-E1")).status shouldBe
            TradeStatus.PARTIALLY_DELIVERED
        val confirmed = trades.confirm(trade.id, ConfirmTrade("CONF-E"))
        confirmed.status shouldBe TradeStatus.PARTIALLY_DELIVERED
        confirmed.confirmationRef shouldBe "CONF-E"
        // Obligations were opened at approval and are not opened again.
        published.filterIsInstance<DeliveryObligationOpened>().filter { it.obligation.tradeId == trade.id } shouldHaveSize 2
        shouldThrow<IllegalStateTransitionException> { trades.confirm(trade.id, ConfirmTrade("CONF-E2")) }

        // The default policy still waits for the confirmation.
        currentUser = "trader1"
        val usual = trades.capture(gooSale(quantity = 10))
        currentUser = "middle-office"
        val usualApproved = trades.approve(usual.id)
        shouldThrow<IllegalStateTransitionException> {
            trades.recordDelivery(usual.id, RecordDelivery(usualApproved.legs.single().deliveries.first().id, BigDecimal(5), LocalDate.of(2026, 9, 10)))
        }
    }

    @Test
    fun `validation rejects inconsistent legs`() {
        currentUser = "trader1"
        shouldThrow<ValidationException> {
            trades.capture(gooSale().let { it.copy(legs = listOf(it.legs.single().copy(deliveries = it.legs.single().deliveries.take(1)))) })
        }
        shouldThrow<ValidationException> { trades.capture(gooSale().let { it.copy(legs = listOf(it.legs.single().copy(criterion = null))) }) }
        shouldThrow<ValidationException> {
            trades.capture(
                gooSale().copy(
                    productFamily = ProductFamily.EMISSION_ALLOWANCE,
                    legs = listOf(gooSale().legs.single().copy(allowanceProductCode = "EUA", unit = QuantityUnit.MWH)),
                ),
            )
        }
    }
}
