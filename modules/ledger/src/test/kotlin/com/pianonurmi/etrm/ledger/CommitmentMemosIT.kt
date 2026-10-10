package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.adapter.jooq.JooqCommitmentMemoRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.application.CommitmentMemos
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeView
import io.kotest.matchers.shouldBe
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

/** Commitment memos follow what was posted: deliveries release their share, amendments and cancellations the rest. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommitmentMemosIT {
    private val actor = ActorProvider { Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC)
    private val fx = MapFxRates()
    private lateinit var ledger: LedgerService
    private lateinit var memos: CommitmentMemos
    private lateinit var referenceData: ReferenceDataService

    @BeforeAll
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
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        memos = CommitmentMemos(ledger, JooqCommitmentMemoRepository(dsl), clock)
    }

    private fun entity(code: String) = referenceData.createLegalEntity(CreateLegalEntity(code, "$code Oy", "FI", validFrom = LocalDate.of(2026, 1, 1))).id

    private fun memoBalance(entity: UUID, account: String): BigDecimal =
        ledger.trialBalance(entity, YearMonth.of(2026, 10)).firstOrNull { it.accountCode == account }?.balance ?: BigDecimal.ZERO

    private val scheduleId = UUID.randomUUID()

    private fun trade(entity: UUID, priceType: PriceType = PriceType.FIXED, version: Int = 1, delivered: String = "0"): TradeView {
        val schedule = mockk<DeliveryScheduleView> {
            every { id } returns scheduleId
            every { remainingQuantity } returns BigDecimal(300) - BigDecimal(delivered)
        }
        val leg = mockk<TradeLegView> {
            every { legNo } returns 1
            every { side } returns Side.SELL
            every { this@mockk.priceType } returns priceType
            every { price } returns BigDecimal("10.005")
            every { priceCurrency } returns Currency.getInstance("EUR")
            every { quantity } returns BigDecimal(300)
            every { bucketCode } returns null
            every { deliveries } returns listOf(schedule)
        }
        return mockk {
            every { id } returns tradeId
            every { tradeNumber } returns "T-MEMO-$entity"
            every { legalEntityId } returns entity
            every { this@mockk.version } returns version
            every { productFamily } returns ProductFamily.GUARANTEE_OF_ORIGIN
            every { tradeDate } returns LocalDate.of(2026, 10, 1)
            every { bookId } returns UUID.randomUUID()
            every { counterpartyId } returns UUID.randomUUID()
            every { legs } returns listOf(leg)
        }
    }

    private val tradeId = Ids.newId()

    private fun delivery(trade: TradeView, quantity: Int, remaining: Int) = DeliveryRecorded(
        trade,
        Ids.newId(),
        mockk<DeliveryObligationView> {
            every { this@mockk.scheduleId } returns this@CommitmentMemosIT.scheduleId
            every { tradeNumber } returns trade.tradeNumber
            every { counterpartyId } returns UUID.randomUUID()
            every { remainingQuantity } returns BigDecimal(remaining)
        },
        BigDecimal(quantity),
        LocalDate.of(2026, 10, 10),
        null,
    )

    @Test
    fun `deliveries release their share, the last one the remainder, and replays change nothing`() {
        val e = entity("MEMO-1")
        val t = trade(e)
        val confirmed = TradeConfirmed(t)
        memos.on(confirmed)
        memos.on(confirmed)
        memoBalance(e, "9000") shouldBe BigDecimal("3001.50") // 300 x 10.005, half up
        val first = delivery(t, 100, 200)
        memos.on(first)
        memos.on(first)
        memoBalance(e, "9000") shouldBe BigDecimal("2001.00") // 3001.50 x 100/300 = 1000.50
        memos.on(delivery(t, 200, 0))
        memoBalance(e, "9000").signum() shouldBe 0
        memoBalance(e, "9009").signum() shouldBe 0
    }

    @Test
    fun `an amendment reverses the open memo and the new confirmation posts again`() {
        val e = entity("MEMO-2")
        memos.on(TradeConfirmed(trade(e)))
        memos.on(TradeAmended(trade(e, version = 2), previousVersion = 1))
        memoBalance(e, "9000").signum() shouldBe 0
        memos.on(TradeConfirmed(trade(e, version = 2)))
        memoBalance(e, "9000") shouldBe BigDecimal("3001.50")
        memos.on(TradeCancelled(trade(e, version = 2), "test"))
        memoBalance(e, "9000").signum() shouldBe 0
        ledger.entries(e, YearMonth.of(2026, 10)).count { it.ruleCode == "TRADE_SALE_COMMITMENT_REVERSAL" } shouldBe 2
    }

    @Test
    fun `index-priced legs post no memo and their deliveries release nothing`() {
        val e = entity("MEMO-3")
        val t = trade(e, PriceType.INDEX)
        memos.on(TradeConfirmed(t))
        memos.on(delivery(t, 300, 0))
        ledger.entries(e, YearMonth.of(2026, 10)).size shouldBe 0
    }
}
