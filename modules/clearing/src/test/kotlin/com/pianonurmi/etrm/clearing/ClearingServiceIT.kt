package com.pianonurmi.etrm.clearing

import com.pianonurmi.etrm.clearing.adapter.jooq.JooqClearingRepository
import com.pianonurmi.etrm.clearing.adapter.jooq.JooqClearingStatementRepository
import com.pianonurmi.etrm.clearing.adapter.jooq.JooqMarginCallRepository
import com.pianonurmi.etrm.clearing.api.ClearingBreakClosed
import com.pianonurmi.etrm.clearing.api.ClearingBreakOpened
import com.pianonurmi.etrm.clearing.api.ClearingBreakStatus
import com.pianonurmi.etrm.clearing.api.ClearingStatementImported
import com.pianonurmi.etrm.clearing.api.CreateClearingAccount
import com.pianonurmi.etrm.clearing.api.MarginCallClosed
import com.pianonurmi.etrm.clearing.api.MarginCallOpened
import com.pianonurmi.etrm.clearing.api.MarginCallStatus
import com.pianonurmi.etrm.clearing.api.MarginDifferenceKind
import com.pianonurmi.etrm.clearing.api.VariationMarginComputed
import com.pianonurmi.etrm.clearing.application.ClearingService
import com.pianonurmi.etrm.clearing.application.ClearingStatementService
import com.pianonurmi.etrm.clearing.application.MarginCallService
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.InstrumentView
import com.pianonurmi.etrm.marketdata.api.MarketDataQuery
import com.pianonurmi.etrm.marketdata.api.QuoteQuality
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
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
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import io.kotest.assertions.throwables.shouldThrow
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
    private lateinit var statements: ClearingStatementService
    private lateinit var calls: MarginCallService

    private fun future(number: String, side: Side, tonnes: Int, price: String, on: LocalDate, owner: UUID = entity): TradeView {
        val leg = mockk<TradeLegView> {
            every { futuresContractCode } returns "EUAZ26"
            every { this@mockk.side } returns side
            every { quantity } returns BigDecimal(tonnes)
            every { this@mockk.price } returns BigDecimal(price)
        }
        return mockk {
            every { id } returns UUID.nameUUIDFromBytes(number.toByteArray())
            every { legalEntityId } returns owner
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
        val trades = mockk<TradeQuery> {
            every { trades(any()) } answers { book.filter { it.legalEntityId == firstArg<TradeFilter>().legalEntityId } }
            every { trade(any()) } answers { book.lastOrNull { it.id == firstArg() } }
        }
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
            every { requireLegalEntity(any()) } returns mockk()
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
        val repository = JooqClearingRepository(TestDatabase.dsl)
        val actor = ActorProvider { Actor("back", "back", setOf(Role.BACK_OFFICE), allEntities = true) }
        val clock = Clock.fixed(Instant.parse("2026-10-07T18:00:00Z"), ZoneOffset.UTC)
        val publisher = ApplicationEventPublisher {
            published += it
            if (it is VariationMarginComputed) statements.on(it)
        }
        val statementRepository = JooqClearingStatementRepository(TestDatabase.dsl)
        calls = MarginCallService(repository, statementRepository, JooqMarginCallRepository(TestDatabase.dsl), actor, publisher, clock)
        statements = ClearingStatementService(repository, statementRepository, calls, actor, publisher, clock)
        clearing = ClearingService(
            repository,
            trades,
            futures,
            marketData,
            referenceData,
            actor,
            publisher,
            clock,
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
        settlements.remove(tuesday.plusDays(1))
        shouldThrow<ValidationException> { clearing.computeMargin(account.id, tuesday.plusDays(1)) }
        shouldThrow<ValidationException> { clearing.computeMargin(account.id, LocalDate.of(2026, 10, 10)) }
        // After the last trading day the contract no longer margins.
        clearing.computeMargin(account.id, LocalDate.of(2026, 12, 14)).variationMargin shouldBe BigDecimal("0.00")
        clearing.marginedTradeIds(entity) shouldBe setOf(UUID.nameUUIDFromBytes("F-1".toByteArray()), UUID.nameUUIDFromBytes("F-2".toByteArray()))

        // Delivery at the final settlement price: the settlement of the last trading day, once stored.
        val bought = UUID.nameUUIDFromBytes("F-1".toByteArray())
        clearing.finalSettlement(bought)!!.let {
            it.price shouldBe null
            shouldThrow<ValidationException> { it.requirePrice() }.message shouldBe "No final settlement price of EUAZ26 on 2026-12-11 yet"
        }
        settlement(LocalDate.of(2026, 12, 11), "74.20")
        clearing.finalSettlement(bought)!!.requirePrice() shouldBe BigDecimal("74.20")
        clearing.finalSettlement(UUID.randomUUID()) shouldBe null
    }

    @Test
    fun `a late settlement price re-runs the later days, so the move is not margined twice`() {
        val owner = UUID.randomUUID()
        val account = clearing.createAccount(CreateClearingAccount("ECC-LATE", owner, ccp, "Member Bank AG"))
        val first = LocalDate.of(2026, 11, 2)
        val second = first.plusDays(1)
        val third = first.plusDays(2)
        book += future("L-1", Side.BUY, 5000, "70.00", first, owner)

        settlement(first, "70.40")
        val firstRun = clearing.computeMargin(account.id, first)
        firstRun.variationMargin shouldBe BigDecimal("2000.00")

        // The second day's price is not in yet: the third day margins against the first day's (70.50 - 70.40 on 5 000 t).
        settlement(third, "70.50")
        clearing.computeMargin(account.id, third).variationMargin shouldBe BigDecimal("500.00")

        // The price arrives late and the second day is run by hand: 70.40 -> 71.00 earns 3 000 and the third day is run again.
        settlement(second, "71.00")
        clearing.computeMargin(account.id, second).variationMargin shouldBe BigDecimal("3000.00")
        val thirdRun = clearing.runs(account.id, first, third).first()
        thirdRun.businessDate shouldBe third
        thirdRun.version shouldBe 2
        thirdRun.variationMargin shouldBe BigDecimal("-2500.00")
        thirdRun.lines.single().previousSettlementPrice!!.compareTo(BigDecimal("71.00")) shouldBe 0
        published.filterIsInstance<VariationMarginComputed>().last().let {
            it.run.id shouldBe thirdRun.id
            it.previous shouldBe BigDecimal("500.00")
        }
        // Over the three days the account earned the move from 70.00 to 70.50 once, and the ledger posts only the differences.
        clearing.runs(account.id, first, third).fold(BigDecimal.ZERO) { sum, run -> sum + run.variationMargin } shouldBe BigDecimal("2500.00")

        // The same prices written with another scale are the same content: no new version, no event.
        val events = published.size
        settlement(first, "70.400")
        clearing.computeMargin(account.id, first).id shouldBe firstRun.id
        published.size shouldBe events
    }

    private fun statementFile(vararg rows: String) = "Date,Account,Record,Contract,Position,SettlementPrice,Amount,Currency\n" + rows.joinToString("") {
        "$it\n"
    }

    @Test
    @Suppress("LongMethod") // one account's statements through three business days
    fun `statements are versioned, reconciled against the margin, and a break closes when matched or accepted by a second person`() {
        val owner = UUID.randomUUID()
        val account = clearing.createAccount(CreateClearingAccount("ECC-STMT", owner, ccp, "Member Bank AG"))
        book += future("S-1", Side.BUY, 5000, "70.00", monday, owner)
        settlement(monday, "70.40")
        settlement(tuesday, "71.00")
        settlement(tuesday.plusDays(1), "70.50")
        clearing.computeMargin(account.id, monday)

        val first = statements.importStatements(
            account.id,
            "ecc-2026-10-05.csv",
            statementFile(
                "2026-10-05,ECC-STMT,VM,EUAZ26,5000,70.40,2000.00,EUR",
                "2026-10-05,ECC-STMT,FEE,EUAZ26,,,12.50,EUR",
                "2026-10-05,ECC-STMT,IM,,,,15000.00,EUR",
                "2026-10-05,ECC-STMT,CASH,,,,100000.00,EUR",
            ),
        )
        first.imported.single().version shouldBe 1
        first.breaks.shouldBeEmpty()
        published.filterIsInstance<ClearingStatementImported>().last().let {
            it.initialMarginChange shouldBe BigDecimal("15000.00")
            it.feesChange shouldBe BigDecimal("12.50")
        }
        // The same figures again change nothing; 70.4 is 70.40.
        val events = published.size
        statements.importStatements(
            account.id,
            null,
            statementFile(
                "2026-10-05,ECC-STMT,VM,EUAZ26,5000,70.4,2000.00,EUR",
                "2026-10-05,ECC-STMT,FEE,EUAZ26,,,12.50,EUR",
                "2026-10-05,ECC-STMT,IM,,,,15000.00,EUR",
                "2026-10-05,ECC-STMT,CASH,,,,100000.00,EUR",
            ),
        ).unchanged shouldBe 1
        published.size shouldBe events

        // Tuesday's statement arrives before the margin is computed: a break, which follows the run once it exists.
        book += future("S-2", Side.SELL, 2000, "71.20", tuesday, owner)
        val tuesdayFile = statementFile("2026-10-06,ECC-STMT,VM,EUAZ26,3000,71.00,3500.00,EUR", "2026-10-06,ECC-STMT,IM,,,,18000.00,EUR")
        val noRun = statements.importStatements(account.id, null, tuesdayFile).breaks.single()
        noRun.differences.single().kind shouldBe MarginDifferenceKind.NO_RUN
        published.filterIsInstance<ClearingBreakOpened>().last().clearingBreak.id shouldBe noRun.id
        published.filterIsInstance<ClearingStatementImported>().last().initialMarginChange shouldBe BigDecimal("3000.00")
        clearing.computeMargin(account.id, tuesday).variationMargin shouldBe BigDecimal("3400.00")
        val open = statements.clearingBreak(noRun.id)!!
        open.status shouldBe ClearingBreakStatus.OPEN
        open.differences.single().let {
            it.kind shouldBe MarginDifferenceKind.VARIATION_MARGIN
            it.ours shouldBe BigDecimal("3400.00")
            it.theirs shouldBe BigDecimal("3500.00")
        }
        shouldThrow<SegregationOfDutiesException> { statements.accept(open.id, "back", "Member charged a give-up fee") }
        shouldThrow<ValidationException> { statements.accept(open.id, "back2", " ") }
        statements.accept(open.id, "back2", "Member's figure confirmed by phone; trade price corrected tomorrow").status shouldBe ClearingBreakStatus.ACCEPTED
        statements.breaks(owner, ClearingBreakStatus.OPEN).shouldBeEmpty()

        // Wednesday: the member reports the wrong position; re-sent corrected, the break matches and closes itself.
        clearing.computeMargin(account.id, tuesday.plusDays(1)).variationMargin shouldBe BigDecimal("-1500.00")
        val wrong = statements.importStatements(account.id, null, statementFile("2026-10-07,ECC-STMT,VM,EUAZ26,2000,70.50,-1500.00,EUR"))
        wrong.breaks.single().differences.single().kind shouldBe MarginDifferenceKind.POSITION
        val corrected = statements.importStatements(account.id, null, statementFile("2026-10-07,ECC-STMT,VM,EUAZ26,3000,70.50,-1500.00,EUR"))
        corrected.imported.single().version shouldBe 2
        corrected.breaks.shouldBeEmpty()
        statements.clearingBreak(wrong.breaks.single().id)!!.status shouldBe ClearingBreakStatus.MATCHED
        published.filterIsInstance<ClearingBreakClosed>().last().clearingBreak.status shouldBe ClearingBreakStatus.MATCHED
        // Wednesday's files have no initial margin record (none required): the first released it, the second changes nothing.
        published.filterIsInstance<ClearingStatementImported>().last().initialMarginChange shouldBe BigDecimal("0.00")

        // Monday re-sent with another initial margin: an earlier day does not move the level.
        statements.importStatements(
            account.id,
            null,
            statementFile("2026-10-05,ECC-STMT,VM,EUAZ26,5000,70.40,2000.00,EUR", "2026-10-05,ECC-STMT,IM,,,,16000.00,EUR"),
        ).imported.single().version shouldBe 2
        published.filterIsInstance<ClearingStatementImported>().last().let {
            it.initialMarginChange shouldBe BigDecimal("0.00")
            it.feesChange shouldBe BigDecimal("-12.50")
        }
        statements.statements(account.id, monday, tuesday.plusDays(1)).map { it.businessDate } shouldBe listOf(tuesday.plusDays(1), tuesday, monday)
    }

    @Test
    fun `cash short of the initial margin is a margin call, replaced by the next statement and paid by a second person`() {
        val owner = UUID.randomUUID()
        val account = clearing.createAccount(CreateClearingAccount("ECC-CALL", owner, ccp, "Member Bank AG"))
        val day = { date: LocalDate, im: String, cash: String -> "$date,ECC-CALL,IM,,,,$im,EUR\n$date,ECC-CALL,CASH,,,,$cash,EUR" }

        // A two-day file: only its latest day decides on a call.
        statements.importStatements(account.id, null, statementFile(day(monday.minusDays(3), "20000.00", "1000.00"), day(monday, "20000.00", "15000.00")))
        val first = calls.marginCalls(owner, MarginCallStatus.OPEN).single()
        first.amount shouldBe BigDecimal("5000.00")
        first.businessDate shouldBe monday
        published.filterIsInstance<MarginCallOpened>().count { it.call.accountId == account.id } shouldBe 1

        // Tuesday's statement replaces the call: the shortfall already includes Monday's.
        statements.importStatements(account.id, null, statementFile(day(tuesday, "20000.00", "18000.00")))
        calls.marginCall(first.id)!!.status shouldBe MarginCallStatus.CANCELLED
        val second = calls.marginCalls(owner, MarginCallStatus.OPEN).single()
        second.amount shouldBe BigDecimal("2000.00")

        shouldThrow<SegregationOfDutiesException> { calls.decide(second.id, "back", pay = true, null) }
        shouldThrow<ValidationException> { calls.decide(second.id, "back2", pay = false, " ") }
        calls.decide(second.id, "back2", pay = true, null).status shouldBe MarginCallStatus.PAID
        published.filterIsInstance<MarginCallClosed>().last().call.status shouldBe MarginCallStatus.PAID
        calls.marginPosted(owner).single().let {
            it.initialMargin shouldBe BigDecimal("20000.00")
            it.cashBalance shouldBe BigDecimal("18000.00")
            it.openCalls shouldBe BigDecimal("0.00")
        }

        // Enough cash: no call.
        statements.importStatements(account.id, null, statementFile(day(tuesday.plusDays(1), "10000.00", "20000.00")))
        calls.marginCalls(owner, MarginCallStatus.OPEN).shouldBeEmpty()
    }
}
