package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.OptionPostings
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.api.OptionExpired
import com.pianonurmi.etrm.trade.api.OptionExpiryView
import com.pianonurmi.etrm.trade.api.OptionOutcome
import com.pianonurmi.etrm.trade.api.OptionTerms
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeVersionView
import com.pianonurmi.etrm.trade.api.TradeView
import io.kotest.matchers.collections.shouldBeEmpty
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

/** Option premiums go through the clearing member's cash when confirmed and are released to the realised result at expiry. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OptionPostingsIT {
    private val actor = ActorProvider { Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var ledger: LedgerService
    private lateinit var referenceData: ReferenceDataService
    private val versions = mutableMapOf<UUID, MutableList<TradeVersionView>>()
    private val trades = mockk<TradeQuery> { every { versions(any()) } answers { versions[firstArg()].orEmpty() } }
    private lateinit var postings: OptionPostings

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
            MapFxRates(),
            JooqGlExportRepository(dsl),
            referenceData,
            actor,
            publisher,
            clock,
            JooqGlInterfaceRepository(dsl),
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        postings = OptionPostings(ledger, trades)
    }

    private fun leg(no: Int, side: Side, type: OptionType, premium: String) = mockk<TradeLegView> {
        every { legNo } returns no
        every { this@mockk.side } returns side
        every { quantity } returns BigDecimal(10_000)
        every { price } returns BigDecimal(premium)
        every { priceCurrency } returns Currency.getInstance("EUR")
        every { option } returns OptionTerms(type, BigDecimal("75"), LocalDate.of(2026, 10, 28), "EUAZ26")
    }

    /** Version [version] of the trade; a confirmed one is kept in its version history like the trade module does. */
    private fun version(id: UUID, entity: UUID, version: Int, status: TradeStatus, legs: List<TradeLegView>): TradeView {
        val trade = mockk<TradeView> {
            every { this@mockk.id } returns id
            every { this@mockk.version } returns version
            every { legalEntityId } returns entity
            every { tradeNumber } returns "T-OPT-$id".take(14)
            every { tradeDate } returns LocalDate.of(2026, 10, 1)
            every { bookId } returns UUID.nameUUIDFromBytes("book".toByteArray())
            every { counterpartyId } returns UUID.nameUUIDFromBytes("ecc".toByteArray())
            every { confirmedAt } returns if (status == TradeStatus.CONFIRMED) clock.instant() else null
            every { this@mockk.legs } returns legs
        }
        versions.getOrPut(id) { mutableListOf() } += TradeVersionView(version, clock.instant(), null, "mo", null, status, trade)
        return trade
    }

    private fun balances(entity: UUID): Map<String, BigDecimal> =
        ledger.trialBalance(entity, YearMonth.of(2026, 10)).associate { it.accountCode to it.balance }

    @Test
    fun `a collar's premiums are paid and received at confirmation, corrected on reconfirmation and released at expiry`() {
        val entity = referenceData.createLegalEntity(CreateLegalEntity("OPT-POST", "Opt Post Oy", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val id = UUID.randomUUID()
        val call = leg(1, Side.BUY, OptionType.CALL, "4.20")
        val put = leg(2, Side.SELL, OptionType.PUT, "2.10")
        version(id, entity, 1, TradeStatus.PENDING_APPROVAL, listOf(call, put))
        postings.on(TradeConfirmed(version(id, entity, 2, TradeStatus.CONFIRMED, listOf(call, put))))
        balances(entity).let {
            it["1810"] shouldBe BigDecimal("42000.00")
            it["2810"] shouldBe BigDecimal("-21000.00")
            it["1960"] shouldBe BigDecimal("-21000.00")
        }

        // Amended to a premium of 4.50 and confirmed again: only the 3 000 more is paid.
        val repriced = leg(1, Side.BUY, OptionType.CALL, "4.50")
        version(id, entity, 3, TradeStatus.PENDING_APPROVAL, listOf(repriced, put))
        val confirmed = version(id, entity, 4, TradeStatus.CONFIRMED, listOf(repriced, put))
        postings.on(TradeConfirmed(confirmed))
        balances(entity)["1810"] shouldBe BigDecimal("45000.00")
        balances(entity)["1960"] shouldBe BigDecimal("-24000.00")

        // At expiry each premium is released to the realised result.
        val expiry = LocalDate.of(2026, 10, 28)
        postings.on(OptionExpired(confirmed, 1, OptionExpiryView(OptionOutcome.EXERCISED, BigDecimal("80"), expiry), null))
        postings.on(OptionExpired(confirmed, 2, OptionExpiryView(OptionOutcome.ABANDONED, BigDecimal("80"), expiry), null))
        balances(entity).let {
            it["1810"] shouldBe BigDecimal("0.00")
            it["2810"] shouldBe BigDecimal("0.00")
            it["3600"] shouldBe BigDecimal("24000.00")
        }
        ledger.verifyJournal(entity).shouldBeEmpty()
    }

    @Test
    fun `cancelling a confirmed option reverses its premium`() {
        val entity = referenceData.createLegalEntity(CreateLegalEntity("OPT-CXL", "Opt Cxl Oy", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val id = UUID.randomUUID()
        val call = leg(1, Side.BUY, OptionType.CALL, "3.00")
        postings.on(TradeConfirmed(version(id, entity, 1, TradeStatus.CONFIRMED, listOf(call))))
        balances(entity)["1810"] shouldBe BigDecimal("30000.00")
        postings.on(TradeCancelled(version(id, entity, 2, TradeStatus.CANCELLED, listOf(call)), "booked twice"))
        balances(entity)["1810"] shouldBe BigDecimal("0.00")
        balances(entity)["1960"] shouldBe BigDecimal("0.00")
    }
}
