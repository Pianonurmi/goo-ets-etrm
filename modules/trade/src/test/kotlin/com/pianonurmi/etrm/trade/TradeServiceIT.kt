package com.pianonurmi.etrm.trade

import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
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
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CreateBook
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.adapter.jooq.JooqTradeRepository
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.CaptureDelivery
import com.pianonurmi.etrm.trade.api.CaptureLeg
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.application.TradeService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeServiceIT {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private var currentUser = "trader1"
    private val actor = ActorProvider { Actor(currentUser, currentUser, setOf(Role.TRADER, Role.MIDDLE_OFFICE)) }

    private lateinit var trades: TradeService
    private lateinit var entityA: UUID
    private lateinit var entityB: UUID
    private lateinit var bookA: UUID
    private lateinit var bookB: UUID
    private lateinit var external: UUID
    private lateinit var icoB: UUID
    private lateinit var accountA: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "trade")
        val dsl = TestDatabase.dsl
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        val buckets = ProductBucketService(JooqProductBucketRepository(dsl), criteria)
        ProductBucketSeeder(buckets, buckets).seed()
        val catalogue = ProductCatalogueService(JooqAllowanceProductRepository(dsl))
        trades = TradeService(JooqTradeRepository(dsl), referenceData, criteria, buckets, catalogue, actor, publisher, Clock.systemUTC(), BigDecimal.ZERO)

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
        trade.legs.single().bucketCode shouldBe null
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
        trades.openObligations(entityA, Side.SELL, ProductFamily.GUARANTEE_OF_ORIGIN).map {
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
