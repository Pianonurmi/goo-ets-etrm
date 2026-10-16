package com.pianonurmi.etrm.reporting

import com.pianonurmi.etrm.allocation.api.GiveAwayLine
import com.pianonurmi.etrm.ledger.fixtures.NoHedges
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.EmirClassification
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.reporting.adapter.jooq.JooqReportingRepository
import com.pianonurmi.etrm.reporting.api.EmirAction
import com.pianonurmi.etrm.reporting.api.ReportExported
import com.pianonurmi.etrm.reporting.api.ReportFormat
import com.pianonurmi.etrm.reporting.application.EmirEvents
import com.pianonurmi.etrm.reporting.application.ReportCatalogue
import com.pianonurmi.etrm.reporting.application.ReportRetention
import com.pianonurmi.etrm.reporting.application.ReportRetentionProperties
import com.pianonurmi.etrm.reporting.application.ReportingService
import com.pianonurmi.etrm.trade.api.ClearingStatus
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.RegulatoryTerms
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

/** EMIR events through a trade's life, completeness, and logged report exports against the reporting schema. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportingServiceIT {
    /** Each reading is a second later, so events recorded one after another are ordered. */
    private val clock = object : Clock() {
        private var now = Instant.parse("2026-10-10T12:00:00Z")

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now.also { now = now.plusSeconds(1) }
    }
    private val entity = UUID.randomUUID()
    private val counterparty = UUID.randomUUID()
    private val sister = UUID.randomUUID()
    private val hedging = UUID.randomUUID()
    private val trading = UUID.randomUUID()
    private var roles = setOf(Role.MIDDLE_OFFICE)
    private val published = mutableListOf<Any>()
    private val trades = mutableMapOf<UUID, TradeView>()
    private lateinit var emir: EmirEvents
    private lateinit var reporting: ReportingService

    private fun trade(
        number: String,
        version: Int = 1,
        upi: String? = "QZ0000000001",
        cp: UUID = counterparty,
        book: UUID = hedging,
        reportable: Boolean = true,
        status: TradeStatus = TradeStatus.CONFIRMED,
        id: UUID = UUID.nameUUIDFromBytes(number.toByteArray()),
    ): TradeView {
        val schedule = mockk<DeliveryScheduleView> { every { deliveryDate } returns LocalDate.of(2026, 12, 14) }
        val leg = mockk<TradeLegView> {
            every { side } returns Side.BUY
            every { this@mockk.upi } returns upi
            every { isin } returns null
            every { option } returns null
            every { price } returns BigDecimal("70.00")
            every { priceCurrency } returns Currency.getInstance("EUR")
            every { quantity } returns BigDecimal(1000)
            every { unit } returns QuantityUnit.TCO2E
            every { notional } returns BigDecimal("70000.00")
            every { deliveries } returns listOf(schedule)
        }
        return mockk<TradeView> {
            every { this@mockk.id } returns id
            every { tradeNumber } returns number
            every { this@mockk.version } returns version
            every { legalEntityId } returns entity
            every { counterpartyId } returns cp
            every { bookId } returns book
            every { productFamily } returns ProductFamily.EMISSION_ALLOWANCE
            every { instrumentType } returns InstrumentType.FORWARD
            every { tradeDate } returns LocalDate.of(2026, 10, 1)
            every { this@mockk.status } returns status
            every { emirReportable } returns reportable
            every { notional } returns BigDecimal("70000.00")
            every { legs } returns listOf(leg)
            every { regulatory } returns RegulatoryTerms(
                uti = "5299000J2N45DDNE4Y28ETRM$number",
                executionTimestamp = Instant.parse("2026-10-01T09:30:00Z"),
                venueMic = "XOFF",
                clearingStatus = ClearingStatus.NOT_CLEARED,
            )
        }.also { trades[id] = it }
    }

    @BeforeEach
    fun setUp() {
        TestDatabase.migrate("reporting")
        roles = setOf(Role.MIDDLE_OFFICE)
        published.clear()
        trades.clear()
        val referenceData = mockk<ReferenceDataQuery> {
            val legalEntity = mockk<com.pianonurmi.etrm.referencedata.api.LegalEntityView> {
                every { lei } returns "5299000J2N45DDNE4Y28"
                every { emirClassification } returns EmirClassification.NFC_PLUS
            }
            every { legalEntity(entity) } returns legalEntity
            every { requireLegalEntity(entity) } returns legalEntity
            every { counterparty(counterparty) } returns mockk {
                every { lei } returns "529900T8BM49AURSDO55"
                every { counterpartyType } returns CounterpartyType.EXTERNAL
            }
            every { counterparty(sister) } returns mockk {
                every { lei } returns "529900HNOAA1KXQJUQ27"
                every { counterpartyType } returns CounterpartyType.INTERCOMPANY
            }
            every { book(hedging) } returns mockk { every { bookType } returns BookType.HEDGING }
            every { book(trading) } returns mockk { every { bookType } returns BookType.TRADING }
        }
        val tradeQuery = mockk<TradeQuery> {
            every { trade(any()) } answers { trades[firstArg()] }
            every { requireTrade(any()) } answers { trades.getValue(firstArg()) }
            every { trades(any()) } answers { trades.values.toList() }
        }
        val valuations = mockk<ValuationQuery>(relaxed = true)
        val repository = JooqReportingRepository(TestDatabase.dsl)
        val actors = ActorProvider { Actor("user1", "u", roles, allEntities = true) }
        emir = EmirEvents(repository, referenceData, tradeQuery, valuations, Savepoints.NONE, actors, clock)
        val catalogue = ReportCatalogue(
            referenceData,
            tradeQuery,
            valuations,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(relaxed = true),
            repository,
            clock,
            giveAway = mockk {
                every { giveAwayLines(entity, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)) } returns listOf(
                    GiveAwayLine(
                        planId = PLAN,
                        approvedAt = Instant.parse("2026-10-05T09:00:00Z"),
                        obligationId = UUID.randomUUID(),
                        tradeRef = "T-GIVE",
                        deliveryDate = LocalDate.of(2026, 12, 15),
                        saleBucketCode = "EU_RENEWABLE",
                        vintageYear = 2026,
                        mwh = 500,
                        deliveredValueEur = BigDecimal("1000.00"),
                        saleMarkEur = BigDecimal("0.50"),
                        giveAwayEur = BigDecimal("750.00"),
                    ),
                )
            },
            hedges = NoHedges,
        )
        reporting = ReportingService(catalogue, repository, emir, tradeQuery, actors, { published += it }, clock)
        every { valuations.results(RUN, null) } answers {
            trades.values.map { t ->
                mockk<ValuationResultView> {
                    every { tradeId } returns t.id
                    every { status } returns ResultStatus.OK
                    every { mtmBase } returns BigDecimal("1500.00")
                }
            }
        }
    }

    @Test
    fun `a confirmation is reported new, a re-confirmation modified, valuations daily, a cancellation terminated`() {
        val t = trade("T-1")
        emir.on(TradeConfirmed(t))
        emir.on(TradeConfirmed(t))
        emir.on(TradeConfirmed(trade("T-1", version = 2)))
        emir.on(
            ValuationRunSignedOff(
                mockk<ValuationRunView> {
                    every { id } returns RUN
                    every { legalEntityId } returns entity
                    every { valuationDate } returns LocalDate.of(2026, 10, 10)
                    every { baseCurrency } returns "EUR"
                },
                null,
            ),
        )
        emir.on(TradeCancelled(trade("T-1", version = 3, status = TradeStatus.CANCELLED), "client request"))
        emir.on(TradeConfirmed(trade("SPOT", reportable = false)))
        val events = reporting.emirEvents(entity, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))
        events.map { it.action } shouldBe listOf(EmirAction.NEWT, EmirAction.MODI, EmirAction.VALU, EmirAction.TERM)
        events.first().fields.let {
            it["COUNTERPARTY_SIDE"] shouldBe "BUYR"
            it["CONTRACT_TYPE"] shouldBe "FORW"
            it["NATURE_OF_REPORTING_COUNTERPARTY"] shouldBe "N"
            it["EXPIRATION_DATE"] shouldBe "2026-12-14"
        }
        events.single { it.action == EmirAction.VALU }.fields["VALUATION_AMOUNT"] shouldBe "1500.00"
        reporting.emirCompleteness(entity).single().let {
            it.latestAction shouldBe EmirAction.TERM
            it.complete shouldBe true
        }
    }

    @Test
    fun `completeness lists missing fields, and an error report corrects a reported trade once`() {
        val t = trade("T-2", upi = null)
        emir.on(TradeConfirmed(t))
        reporting.emirCompleteness(entity).single().missingFields shouldBe listOf("UPI")
        shouldThrow<ValidationException> { reporting.reportError(trade("NEVER").id, "not reported") }
        reporting.reportError(t.id, "booked twice").action shouldBe EmirAction.EROR
        shouldThrow<ValidationException> { reporting.reportError(t.id, "again") }
        emir.on(TradeConfirmed(trade("T-2", version = 2)))
        reporting.emirEvents(entity, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)).map { it.action } shouldBe listOf(EmirAction.NEWT, EmirAction.EROR)
    }

    /** After the retention period the content goes; the export's number, parameters and hash stay in the log. */
    @Test
    fun `export content is dropped after the retention period and the log keeps the hash`() {
        trade("H-1")
        val parameters = mapOf("legalEntityId" to entity.toString(), "year" to "2026")
        val old = reporting.produce("MIFID_ANCILLARY_ACTIVITY", parameters, ReportFormat.CSV)
        val repository = JooqReportingRepository(TestDatabase.dsl)
        val transactions = TransactionOperations.withoutTransaction()

        ReportRetention(repository, ReportRetentionProperties(), transactions, clock).expire() shouldBe 0
        val later = Clock.fixed(Instant.parse("2027-11-20T00:00:00Z"), ZoneOffset.UTC)
        val fresh = reporting.produce("MIFID_ANCILLARY_ACTIVITY", parameters, ReportFormat.JSON)
        ReportRetention(repository, ReportRetentionProperties(), transactions, later).expire() shouldBe 2
        ReportRetention(repository, ReportRetentionProperties(), transactions, later).expire() shouldBe 0

        val logged = reporting.exports(entity, "MIFID_ANCILLARY_ACTIVITY").single { it.id == old.export.id }
        logged.contentExpiredAt shouldBe later.instant()
        logged.contentHash shouldBe old.export.contentHash
        logged.seq shouldBe 1
        shouldThrow<ConflictException> { reporting.export(old.export.id) }.message!! shouldContain old.export.contentHash
        reporting.exports(entity, "MIFID_ANCILLARY_ACTIVITY").single { it.id == fresh.export.id }.contentExpiredAt shouldNotBe null

        // Only dropping the content is allowed; the rest of the row stays append-only.
        shouldThrow<DataAccessException> {
            TestDatabase.dsl.execute("UPDATE etrm_reporting.report_export SET row_count = 0 WHERE id = '${old.export.id}'")
        }
        shouldThrow<DataAccessException> { TestDatabase.dsl.execute("DELETE FROM etrm_reporting.report_export WHERE id = '${old.export.id}'") }
        shouldThrow<IllegalArgumentException> { ReportRetentionProperties(Duration.ofDays(7)) }
    }

    @Test
    fun `reports are logged as numbered exports with the hash of their content`() {
        trade("H-1")
        trade("I-1", cp = sister, book = trading, reportable = false)
        trade("X-1", status = TradeStatus.CANCELLED)
        val parameters = mapOf("legalEntityId" to entity.toString(), "year" to "2026", "ignored" to "x")
        val first = reporting.produce("mifid_ancillary_activity", parameters, ReportFormat.CSV)
        val rows = Csv.parse(first.content)
        rows.first() shouldBe listOf("category", "instrument", "trades", "notionalEur")
        rows.drop(1) shouldBe listOf(
            listOf("HEDGING", "DERIVATIVE", "1", "70000.00"),
            listOf("INTRAGROUP", "SPOT", "1", "70000.00"),
            listOf("TOTAL", "", "2", "140000.00"),
        )
        first.export.seq shouldBe 1
        first.export.parameters shouldBe mapOf("legalEntityId" to entity.toString(), "year" to "2026")
        first.export.contentHash shouldBe MessageDigest.getInstance("SHA-256").digest(first.content.toByteArray()).joinToString("") { "%02x".format(it) }
        reporting.produce("MIFID_ANCILLARY_ACTIVITY", parameters, ReportFormat.JSON).export.seq shouldBe 2
        published.filterIsInstance<ReportExported>() shouldHaveSize 2
        reporting.exports(entity, "MIFID_ANCILLARY_ACTIVITY") shouldHaveSize 2
        reporting.export(first.export.id)!!.content shouldBe first.content

        shouldThrow<ValidationException> { reporting.produce("MIFID_ANCILLARY_ACTIVITY", mapOf("year" to "2026"), ReportFormat.CSV) }
        roles = setOf(Role.TRADER)
        shouldThrow<ForbiddenException> { reporting.produce("MIFID_ANCILLARY_ACTIVITY", parameters, ReportFormat.CSV) }
    }

    @Test
    fun `the give-away report lists the sales of the plans approved in the period, last day included`() {
        roles = setOf(Role.AUDITOR)
        val parameters = mapOf("legalEntityId" to entity.toString(), "from" to "2026-10-01", "to" to "2026-10-31")
        val output = reporting.produce("GOO_GIVE_AWAY", parameters, ReportFormat.CSV)
        Csv.parse(output.content) shouldBe listOf(
            listOf("approvedAt", "planId", "tradeRef", "deliveryDate", "saleBucket", "vintage", "mwh", "deliveredValueEur", "saleMarkEur", "giveAwayEur"),
            listOf("2026-10-05T09:00:00Z", PLAN.toString(), "T-GIVE", "2026-12-15", "EU_RENEWABLE", "2026", "500", "1000.00", "0.50", "750.00"),
        )
        output.export.seq shouldBe 1
        roles = setOf(Role.TRADER)
        shouldThrow<ForbiddenException> { reporting.produce("GOO_GIVE_AWAY", parameters, ReportFormat.CSV) }
    }

    private companion object {
        val PLAN: UUID = UUID.randomUUID()
        val RUN: UUID = UUID.randomUUID()
    }
}
