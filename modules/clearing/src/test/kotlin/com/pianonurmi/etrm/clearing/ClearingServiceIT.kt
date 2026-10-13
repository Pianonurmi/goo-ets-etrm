package com.pianonurmi.etrm.clearing

import com.pianonurmi.etrm.clearing.adapter.jooq.JooqClearingRepository
import com.pianonurmi.etrm.clearing.api.CreateClearingAccount
import com.pianonurmi.etrm.clearing.api.VariationMarginComputed
import com.pianonurmi.etrm.clearing.application.ClearingService
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.InstrumentView
import com.pianonurmi.etrm.marketdata.api.MarketDataQuery
import com.pianonurmi.etrm.marketdata.api.QuoteQuality
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.FuturesContractView
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.RegulatoryTerms
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import io.kotest.assertions.throwables.shouldThrow
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
import java.time.ZoneOffset
import java.util.UUID

/** Daily variation margin of a clearing account against stubbed trades, contracts and settlement prices. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClearingServiceIT {
    private val entity = UUID.randomUUID()
    private val ccp = UUID.randomUUID()
    private val broker = UUID.randomUUID()
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)
    private val published = mutableListOf<Any>()
    private val book = mutableListOf<TradeView>()
    private val settlements = mutableMapOf<LocalDate, BigDecimal>()
    private lateinit var clearing: ClearingService

    private fun future(number: String, side: Side, tonnes: Int, price: String, on: LocalDate): TradeView {
        val leg = mockk<TradeLegView> {
            every { futuresContractCode } returns "EUAZ26"
            every { this@mockk.side } returns side
            every { quantity } returns BigDecimal(tonnes)
            every { this@mockk.price } returns BigDecimal(price)
        }
        return mockk {
            every { id } returns UUID.nameUUIDFromBytes(number.toByteArray())
            every { tradeDate } returns on
            every { status } returns TradeStatus.CONFIRMED
            every { counterpartyId } returns ccp
            every { regulatory } returns RegulatoryTerms(ccpCounterpartyId = ccp)
            every { legs } returns listOf(leg)
        }
    }

    private fun settlement(date: LocalDate, price: String) {
        settlements[date] = BigDecimal(price)
    }

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("clearing")
        val trades = mockk<TradeQuery> { every { trades(any()) } answers { book.toList() } }
        val futures = mockk<FuturesContractQuery> {
            every { futuresContract("EUAZ26") } returns mockk<FuturesContractView> {
                every { lastTradingDay } returns LocalDate.of(2026, 12, 11)
                every { currency } returns "EUR"
            }
        }
        val instrument = mockk<InstrumentView> {
            every { code } returns "EUA_FUT_Z26"
            every { futuresContractCode } returns "EUAZ26"
        }
        val marketData = mockk<MarketDataQuery> {
            every { instruments(InstrumentKind.EUA_FUTURE, false) } returns listOf(instrument)
            every { latestQuote("EUA_FUT_Z26", QuoteType.SETTLEMENT, any(), any()) } answers {
                val on = thirdArg<LocalDate>()
                settlements.filterKeys { !it.isAfter(on) }.maxByOrNull { it.key }?.let { (date, price) ->
                    QuoteView(
                        UUID.randomUUID(),
                        "EUA_FUT_Z26",
                        date,
                        QuoteType.SETTLEMENT,
                        price,
                        "EUR",
                        "EEX",
                        null,
                        QuoteQuality.OK,
                        null,
                        "x",
                        Instant.EPOCH,
                        null,
                        null,
                    )
                }
            }
        }
        val referenceData = mockk<ReferenceDataQuery> {
            every { requireLegalEntity(entity) } returns mockk()
            every { requireCounterparty(ccp) } returns mockk {
                every { id } returns ccp
                every { code } returns "CP-CLEARING"
                every { counterpartyType } returns CounterpartyType.CLEARING_HOUSE
            }
            every { requireCounterparty(broker) } returns mockk {
                every { id } returns broker
                every { code } returns "CP-BROKER"
                every { counterpartyType } returns CounterpartyType.BROKER
            }
        }
        clearing = ClearingService(
            JooqClearingRepository(TestDatabase.dsl),
            trades,
            futures,
            marketData,
            referenceData,
            ActorProvider { Actor("back", "back", setOf(Role.BACK_OFFICE), allEntities = true) },
            ApplicationEventPublisher { published += it },
            Clock.fixed(Instant.parse("2026-10-07T18:00:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    @Suppress("LongMethod") // one account through four business days
    fun `variation margin follows the settlement price, a changed trade gives a new version, and the contract stops at its last trading day`() {
        shouldThrow<ValidationException> { clearing.createAccount(CreateClearingAccount("ECC-X", entity, broker, "Member Bank")) }
        val account = clearing.createAccount(CreateClearingAccount("ecc-demo", entity, ccp, "Member Bank AG"))
        account.code shouldBe "ECC-DEMO"
        shouldThrow<ConflictException> { clearing.createAccount(CreateClearingAccount("ECC-OTHER", entity, ccp, "Member Bank AG")) }

        // Monday: bought 5 lots at 70.00, settled at 70.40.
        book += future("F-1", Side.BUY, 5000, "70.00", monday)
        settlement(monday, "70.40")
        val first = clearing.computeMargin(account.id, monday)
        first.variationMargin shouldBe BigDecimal("2000.00")
        published.filterIsInstance<VariationMarginComputed>().last().previous shouldBe BigDecimal("0.00")

        // Tuesday: settled at 71.00 (+0.60 on 5 000 t), and 2 000 t sold at 71.20 (+0.20 each).
        val sale = future("F-2", Side.SELL, 2000, "71.20", tuesday)
        book += sale
        settlement(tuesday, "71.00")
        val second = clearing.computeMargin(account.id, tuesday)
        second.variationMargin shouldBe BigDecimal("3400.00")
        second.lines.single().positionAfter shouldBe BigDecimal(3000)
        val events = published.size
        clearing.computeMargin(account.id, tuesday).id shouldBe second.id
        published.size shouldBe events

        // The sale is amended to 71.10: a second version, and the ledger is told what it replaces.
        book.remove(sale)
        book += future("F-2", Side.SELL, 2000, "71.10", tuesday)
        val corrected = clearing.computeMargin(account.id, tuesday)
        corrected.version shouldBe 2
        corrected.variationMargin shouldBe BigDecimal("3200.00")
        published.filterIsInstance<VariationMarginComputed>().last().previous shouldBe BigDecimal("3400.00")
        clearing.runs(account.id, monday, tuesday).map { it.id } shouldBe listOf(corrected.id, first.id)

        // No settlement price on Wednesday yet; Saturday is not a business day.
        shouldThrow<ValidationException> { clearing.computeMargin(account.id, tuesday.plusDays(1)) }
        shouldThrow<ValidationException> { clearing.computeMargin(account.id, LocalDate.of(2026, 10, 10)) }
        // After the last trading day the contract no longer margins.
        clearing.computeMargin(account.id, LocalDate.of(2026, 12, 14)).variationMargin shouldBe BigDecimal("0.00")
        clearing.marginedTradeIds(entity) shouldBe setOf(UUID.nameUUIDFromBytes("F-1".toByteArray()), UUID.nameUUIDFromBytes("F-2".toByteArray()))
    }
}
