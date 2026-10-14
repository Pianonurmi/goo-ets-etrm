package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodEndRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.api.GlBalanceLine
import com.pianonurmi.etrm.ledger.api.GlExportKind
import com.pianonurmi.etrm.ledger.api.GlExportStatus
import com.pianonurmi.etrm.ledger.api.HardCloseGate
import com.pianonurmi.etrm.ledger.api.ManualJournal
import com.pianonurmi.etrm.ledger.api.ManualLine
import com.pianonurmi.etrm.ledger.api.PeriodEndRunView
import com.pianonurmi.etrm.ledger.api.PeriodStatus
import com.pianonurmi.etrm.ledger.api.PostingSide
import com.pianonurmi.etrm.ledger.api.RuleChangeStatus
import com.pianonurmi.etrm.ledger.api.RuleLine
import com.pianonurmi.etrm.ledger.api.UpsertPostingRule
import com.pianonurmi.etrm.ledger.application.GlInterfaceService
import com.pianonurmi.etrm.ledger.application.HardCloseGates
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exactly-once GL exports, formats, acknowledgement, reconciliation, hard-close gates and posting rule changes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlInterfaceIT {
    private var user = "accountant1"
    private val actor = ActorProvider { Actor(user, user, setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-10-10T10:00:00Z"), ZoneOffset.UTC)
    private val september = YearMonth.of(2026, 9)
    private val counter = AtomicInteger()
    private lateinit var ledger: LedgerService
    private lateinit var gl: GlInterfaceService
    private lateinit var referenceData: ReferenceDataService
    private lateinit var periodEnd: JooqPeriodEndRepository

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "ledger")
        val dsl = TestDatabase.dsl
        val publisher = ApplicationEventPublisher { }
        referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), TaxonomyService(JooqTaxonomyRepository(dsl), publisher), publisher)
        periodEnd = JooqPeriodEndRepository(dsl)
        val repository = JooqGlInterfaceRepository(dsl)
        val gates = HardCloseGates(JooqJournalRepository(dsl), repository, periodEnd, HardCloseGate.entries)
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
            repository,
            gates,
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        gl = GlInterfaceService(repository, gates, ledger, actor, publisher, clock)
    }

    private fun entity(): UUID {
        val code = "GL-${counter.incrementAndGet()}"
        return referenceData.createLegalEntity(CreateLegalEntity(code, code, "FI", validFrom = LocalDate.of(2026, 1, 1))).id
    }

    private fun post(entity: UUID, day: Int, amount: String, debit: String = "1400", credit: String = "2200") = ledger.postManual(
        ManualJournal(
            entity,
            september.atDay(day),
            "manual $amount",
            "EUR",
            listOf(ManualLine(debit, debit = BigDecimal(amount)), ManualLine(credit, credit = BigDecimal(amount))),
        ),
    )

    private fun periodEndRun(entity: UUID, at: Instant = clock.instant(), seq: Int = 1) = periodEnd.insertRun(
        PeriodEndRunView(UUID.randomUUID(), entity, september.toString(), seq, null, emptyList(), emptyList(), emptyList(), "accountant1", at),
    )

    @Test
    fun `each entry is exported once, adjustments go into a delta, and a rejected export's entries into the next`() {
        val e = entity()
        post(e, 10, "100")
        post(e, 11, "50")
        ledger.closePeriod(e, september, hard = false)
        val first = ledger.exportGl(e, september)
        first.kind shouldBe GlExportKind.PERIOD
        first.entryCount shouldBe 2
        shouldThrow<ValidationException> { ledger.exportGl(e, september) }.message!! shouldContain "has been exported"

        post(e, 30, "7")
        val delta = ledger.exportGl(e, september)
        delta.kind shouldBe GlExportKind.DELTA
        delta.entryCount shouldBe 1
        gl.reject(delta.id, "unknown cost centre").status shouldBe GlExportStatus.REJECTED
        shouldThrow<ValidationException> { gl.acknowledge(delta.id, "GL-1") }
        ledger.exportGl(e, september).entryCount shouldBe 1
        gl.exports(e, september).map { it.status } shouldBe listOf(GlExportStatus.GENERATED, GlExportStatus.REJECTED, GlExportStatus.GENERATED)
    }

    @Test
    fun `the SAP flat layout writes one line per journal line with the side as S or H`() {
        val e = entity()
        post(e, 15, "123.45")
        ledger.closePeriod(e, september, hard = false)
        val rows = Csv.parse(ledger.exportGl(e, september, "SAP_FLAT").content, ';')
        rows.first() shouldBe listOf("BUKRS", "BLDAT", "BUDAT", "MONAT", "XBLNR", "BUZEI", "HKONT", "SHKZG", "WRBTR", "WAERS", "DMBTR", "HWAER", "SGTXT")
        rows.drop(1).map { listOf(it[0], it[2], it[3], it[6], it[7], it[8]) } shouldBe listOf(
            listOf(referenceData.requireLegalEntity(e).code, "20260915", "09", "140000", "S", "123.45"),
            listOf(referenceData.requireLegalEntity(e).code, "20260915", "09", "220000", "H", "123.45"),
        )
        shouldThrow<com.pianonurmi.etrm.platform.errors.NotFoundException> { ledger.exportGl(e, september, "NO_SUCH") }
    }

    @Test
    fun `reconciliation compares the sub-ledger, what was exported and the general ledger`() {
        val e = entity()
        post(e, 10, "100")
        ledger.closePeriod(e, september, hard = false)
        ledger.exportGl(e, september)
        post(e, 20, "5")
        gl.reconciliation(e, september).associateBy { it.glAccount }.getValue("140000").let {
            it.subLedger shouldBe BigDecimal("105.00")
            it.exported shouldBe BigDecimal("100.00")
            it.notExported shouldBe BigDecimal("5.00")
            it.generalLedger shouldBe null
        }
        ledger.exportGl(e, september)
        val rows = gl.importBalances(
            e,
            september,
            listOf(GlBalanceLine("140000", BigDecimal("105.00"), "EUR"), GlBalanceLine("220000", BigDecimal("-100.00"), "EUR")),
        )
        rows.associateBy { it.glAccount }.let {
            it.getValue("140000").reconciled shouldBe true
            it.getValue("220000").glDifference shouldBe BigDecimal("-5.00")
        }
    }

    @Test
    fun `a hard close needs everything exported, the last export acknowledged and the period end run`() {
        val e = entity()
        post(e, 10, "100")
        ledger.closePeriod(e, september, hard = false)
        shouldThrow<ValidationException> { ledger.closePeriod(e, september, hard = true) }.message!!.let {
            it shouldContain "1 journal entries are not exported"
            it shouldContain "the period end has not been run"
        }
        val export = ledger.exportGl(e, september)
        gl.markDelivered(export.id).status shouldBe GlExportStatus.DELIVERED
        gl.hardCloseBlockers(e, september) shouldBe listOf("export ${export.exportSeq} is DELIVERED, not acknowledged", "the period end has not been run")
        gl.acknowledge(export.id, "FI-DOC-4711").acknowledgementRef shouldBe "FI-DOC-4711"
        // A run older than the last posting did not measure it (review finding F11).
        periodEndRun(e, clock.instant().minusSeconds(60))
        gl.hardCloseBlockers(e, september) shouldBe listOf("entries were posted after the last period end run; run it again")
        periodEndRun(e, seq = 2)
        ledger.closePeriod(e, september, hard = true).status shouldBe PeriodStatus.CLOSED
    }

    @Test
    fun `posting rules change only with a second accountant's approval and keep their history`() {
        val change = gl.requestRuleChange(
            UpsertPostingRule(
                ruleCode = "test_bank_fee",
                eventType = "BankFee",
                lines = listOf(RuleLine(PostingSide.DEBIT, "4900", "fee"), RuleLine(PostingSide.CREDIT, "1900", "fee")),
            ),
        )
        change.ruleCode shouldBe "TEST_BANK_FEE"
        ledger.rules(null).none { it.ruleCode == "TEST_BANK_FEE" } shouldBe true
        shouldThrow<SegregationOfDutiesException> { gl.applyRuleChange(change.id, "accountant1") }
        gl.applyRuleChange(change.id, "accountant2")
        ledger.rules(null).single { it.ruleCode == "TEST_BANK_FEE" }.version shouldBe 1
        gl.ruleHistory("test_bank_fee").single().changeId shouldBe change.id
        gl.ruleChanges(RuleChangeStatus.APPLIED).map { it.id } shouldBe listOf(change.id)
        shouldThrow<ValidationException> { gl.applyRuleChange(change.id, "accountant3") }
    }

    @Test
    @OptIn(io.kotest.common.ExperimentalKotest::class)
    fun `the export's base amounts add up to the trial balance of the exported accounts`() {
        runBlocking {
            checkAll(PropTestConfig(iterations = 15), Arb.list(Arb.int(1..1_000_000), 1..6)) { amounts ->
                val e = entity()
                amounts.forEachIndexed { i, cents ->
                    val amount = BigDecimal(cents).movePointLeft(2).toPlainString()
                    if (i % 2 == 0) post(e, 1 + i, amount) else post(e, 1 + i, amount, debit = "1500", credit = "3000")
                }
                ledger.closePeriod(e, september, hard = false)
                val rows = Csv.parse(ledger.exportGl(e, september).content).drop(1)
                val trial = ledger.trialBalance(e, september)
                rows.sumOf { BigDecimal(it[8]) } shouldBe trial.sumOf { it.debit }
                rows.sumOf { BigDecimal(it[9]) } shouldBe trial.sumOf { it.credit }
                rows.first()[1] shouldStartWith "2026-09"
            }
        }
    }
}
