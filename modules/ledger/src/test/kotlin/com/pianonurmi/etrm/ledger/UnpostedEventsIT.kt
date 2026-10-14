package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.allocation.api.AllocationLineConfirmed
import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CertificateRange
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
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqUnpostedEventRepository
import com.pianonurmi.etrm.ledger.api.LedgerAttentionNeeded
import com.pianonurmi.etrm.ledger.application.LedgerEventListeners
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PostingReplay
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.ledger.application.UnpostedEvents
import com.pianonurmi.etrm.ledger.application.UnpostedReplay
import com.pianonurmi.etrm.ledger.domain.UnpostedKind
import com.pianonurmi.etrm.ledger.domain.UnpostedStatus
import com.pianonurmi.etrm.marketdata.api.FixingView
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID
import java.util.stream.Stream

/**
 * Review findings F4 and F5: a posting listener that fails, or a rule that applies but lacks an amount, no longer
 * fails the business transaction or vanishes. It is recorded, raises an inbox task, and posts on a later retry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnpostedEventsIT {
    private val actor = ActorProvider { Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC)
    private val fx = MapFxRates()
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private val fixingsByDate = mutableMapOf<LocalDate, BigDecimal>()
    private val fixings = IndexFixings { index, date -> fixingsByDate[date]?.let { FixingView(index, date, it, "EUR", emptyList()) } }
    private val repository by lazy { JooqUnpostedEventRepository(TestDatabase.dsl) }
    private val workflow = mockk<WorkflowCommand>(relaxed = true)
    private lateinit var ledger: LedgerService
    private lateinit var unposted: UnpostedEvents
    private lateinit var referenceData: ReferenceDataService
    private lateinit var entityId: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "ledger")
        val dsl = TestDatabase.dsl
        val quiet = ApplicationEventPublisher { }
        referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), TaxonomyService(JooqTaxonomyRepository(dsl), quiet), quiet)
        unposted = UnpostedEvents(repository, Savepoints.NONE, publisher)
        ledger = LedgerService(
            JooqGlAccountRepository(dsl),
            JooqPostingRuleRepository(dsl),
            JooqJournalRepository(dsl),
            JooqPeriodRepository(dsl),
            fx,
            JooqGlExportRepository(dsl),
            referenceData,
            actor,
            quiet,
            clock,
            JooqGlInterfaceRepository(dsl),
            unposted = unposted,
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        entityId = referenceData.createLegalEntity(CreateLegalEntity("UNPOSTED-CO", "Unposted Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
    }

    private fun lot(quantity: Long, costBasis: String, currency: String): LotView {
        val id = UUID.randomUUID()
        return LotView(
            id,
            entityId,
            UUID.randomUUID(),
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
            LocalDateRange.ofMonth(YearMonth.of(2026, 6)),
            LocalDate.of(2026, 7, 10),
            LocalDate.of(2027, 7, 1),
            false,
            false,
            null,
            emptySet(),
            LotStatus.HELD,
            id,
            null,
            AcquisitionSource.TRANSFER_IN,
            null,
            LocalDate.of(2026, 7, 10),
            BigDecimal(costBasis),
            currency,
            null,
            null,
            emptyMap(),
            null,
            1,
        )
    }

    private fun listeners(lots: LotQuery = mockk(relaxed = true), trades: TradeQuery = mockk(relaxed = true)) =
        LedgerEventListeners(ledger, JooqCostLayerRepository(TestDatabase.dsl), referenceData, lots, trades, clock, unposted, fixings)

    private fun replay(vararg listeners: PostingReplay): UnpostedReplay {
        val provider = mockk<ObjectProvider<PostingReplay>> { every { orderedStream() } answers { Stream.of(*listeners) } }
        return UnpostedReplay(repository, unposted, provider, workflow, DataSourceTransactionManager(TestDatabase.dataSource), mockk(relaxed = true))
    }

    @Test
    fun `a listener that fails is recorded with its event instead of failing the transaction, and posts on retry`() {
        val received = LotReceived(lot(100, "2.00", "USD")) // no USD rate yet
        val subject = listeners()
        subject.on(received) // does not throw
        ledger.entries(entityId, sourceRef = received.lot.id.toString()).shouldBeEmpty()
        val record = repository.open(entityId, 50).single { it.eventId == received.eventId }
        record.kind shouldBe UnpostedKind.FAILED
        record.reason shouldContain "USD"
        record.replayable shouldBe true
        published.filterIsInstance<LedgerAttentionNeeded>().single { it.unposted.id == record.id }

        val replays = replay(subject)
        replays.replayAll() shouldBe 0 // still no rate: the record stays open, one attempt more
        repository.find(record.id)!!.attempts shouldBe 2

        fx.put(LocalDate.of(2026, 7, 10), "USD", "EUR", BigDecimal("0.9"))
        replays.replayAll() shouldBe 1
        repository.find(record.id)!!.status shouldBe UnpostedStatus.POSTED
        ledger.entries(entityId, sourceRef = received.lot.id.toString()) shouldHaveSize 1
        verify { workflow.closeFor(UnpostedReplay.LEDGER_ATTENTION, UnpostedReplay.SUBJECT, record.id, any(), any()) }
        ledger.verifyJournal(entityId).shouldBeEmpty()
    }

    @Test
    fun `revenue that cannot be priced yet is recorded, and posts at the fixing once it is published`() {
        val deliveryDate = LocalDate.of(2026, 9, 12)
        val sold = lot(100, "0.50", "EUR")
        val scheduleId = UUID.randomUUID()
        val delivery = mockk<DeliveryObligationView> {
            every { priceType } returns PriceType.INDEX
            every { priceIndex } returns "GO_NORDIC"
            every { price } returns BigDecimal("0.10")
            every { priceCurrency } returns Currency.getInstance("EUR")
            every { this@mockk.deliveryDate } returns deliveryDate
            every { tradeNumber } returns "T-LATE"
        }
        val subject = listeners(mockk { every { lot(sold.id) } returns sold }, mockk { every { obligation(scheduleId) } returns delivery })
        val line = mockk<AllocationLineView>(relaxed = true) {
            every { id } returns UUID.randomUUID()
            every { reservedLotId } returns sold.id
            every { quantityMwh } returns 100L
        }
        val obligation = mockk<ObligationView>(relaxed = true) {
            every { kind } returns ObligationKind.SALE
            every { holderEntityId } returns entityId
            every { sourceRef } returns scheduleId
            every { tradeRef } returns "T-LATE"
            every { counterpartyId } returns null
        }
        val event = AllocationLineConfirmed(mockk(relaxed = true), line, obligation)
        subject.on(event)
        ledger.entries(entityId, sourceRef = line.id.toString()).map { it.ruleCode } shouldBe listOf("GOO_SALE_DELIVERY_COGS")
        val record = repository.open(entityId, 50).single { it.eventId == event.eventId }
        record.kind shouldBe UnpostedKind.MISSING_AMOUNT
        record.ruleCode shouldBe "GOO_SALE_DELIVERY_REVENUE"
        record.reason shouldContain "proceeds"
    }
}
