package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.adapter.jooq.JooqFxRateRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.ledger.application.SettlementPostings
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.settlement.api.AllocationKind
import com.pianonurmi.etrm.settlement.api.AllocationView
import com.pianonurmi.etrm.settlement.api.InvoiceIssued
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceLineType
import com.pianonurmi.etrm.settlement.api.InvoiceLineView
import com.pianonurmi.etrm.settlement.api.InvoiceSettled
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.InvoiceView
import com.pianonurmi.etrm.settlement.api.PaymentDirection
import com.pianonurmi.etrm.settlement.api.PaymentRecorded
import com.pianonurmi.etrm.settlement.api.PaymentSource
import com.pianonurmi.etrm.settlement.api.PaymentView
import com.pianonurmi.etrm.settlement.api.VatTreatment
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

/** Every settlement posting balances, and settled invoices leave nothing behind in the clearing accounts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementPostingsIT {
    private val actor = ActorProvider { Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var ledger: LedgerService
    private lateinit var postings: SettlementPostings
    private lateinit var referenceData: ReferenceDataService
    private val counterparty = UUID.randomUUID()

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
            JooqFxRateRepository(dsl),
            JooqGlExportRepository(dsl),
            referenceData,
            actor,
            publisher,
            clock,
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        postings = SettlementPostings(ledger, referenceData)
    }

    private fun entity(code: String) =
        referenceData.createLegalEntity(CreateLegalEntity(code, "$code Oy", "FI", validFrom = LocalDate.of(2026, 1, 1))).id

    private fun balances(entity: UUID, period: String = "2026-10"): Map<String, BigDecimal> =
        ledger.trialBalance(entity, YearMonth.parse(period)).associate { it.accountCode to (it.debit - it.credit) }

    private fun invoice(
        entity: UUID,
        kind: InvoiceKind,
        net: String,
        vat: String,
        selfAssessed: String = "0",
        currency: String = "EUR",
        date: LocalDate = LocalDate.of(2026, 10, 9),
        number: String = "${kind.series}-${Ids.newId()}",
    ): InvoiceView {
        val n = BigDecimal(net)
        val v = BigDecimal(vat)
        val treatment = if (v.signum() == 0) VatTreatment.REVERSE_CHARGE_CROSS_BORDER else VatTreatment.STANDARD
        return InvoiceView(
            id = Ids.newId(),
            legalEntityId = entity,
            counterpartyId = counterparty,
            kind = kind,
            status = InvoiceStatus.ISSUED,
            number = number,
            counterpartyInvoiceRef = null,
            currency = currency,
            invoiceDate = date,
            taxPointDate = date,
            dueDate = date.plusDays(14),
            netTotal = n,
            vatTotal = v,
            grossTotal = n + v,
            supplier = null,
            customer = null,
            bankAccount = null,
            notes = emptyList(),
            originalInvoiceId = null,
            documentId = null,
            lines = listOf(
                InvoiceLineView(1, InvoiceLineType.COMMODITY, null, "line", null, null, null, n, null, treatment, BigDecimal.ZERO, v, BigDecimal(selfAssessed)),
            ),
            createdBy = "billing",
            submittedBy = "billing",
            approvedBy = "controller",
            issuedAt = Instant.now(),
            version = 1,
        )
    }

    private fun payment(
        entity: UUID,
        direction: PaymentDirection,
        amount: String,
        currency: String = "EUR",
        valueDate: LocalDate = LocalDate.of(
            2026,
            10,
            20,
        ),
    ) =
        PaymentView(
            Ids.newId(),
            entity,
            counterparty,
            direction,
            BigDecimal(amount),
            currency,
            valueDate,
            "B-${Ids.newId()}",
            null,
            PaymentSource.MANUAL,
            BigDecimal.ZERO,
            "billing",
        )

    private fun settle(invoice: InvoiceView, amount: String, kind: AllocationKind, payment: PaymentView? = null) = postings.on(
        InvoiceSettled(
            invoice,
            AllocationView(
                Ids.newId(),
                invoice.id,
                kind,
                BigDecimal(amount),
                payment?.id,
                null,
                if (kind == AllocationKind.NETTING) Ids.newId() else null,
                "billing",
                Instant.now(),
            ),
            payment,
        ),
    )

    private fun sale(entity: UUID, proceeds: String) = ledger.post(
        PostingFacts(entity, "SaleDelivered", Ids.newId(), LocalDate.of(2026, 10, 5), "EUR", "TEST", "sale", "sale", mapOf("proceeds" to BigDecimal(proceeds))),
    )

    @Test
    fun `a sales invoice moves the accrued receivable to invoiced with output VAT, and the receipt clears it`() {
        val e = entity("SP-SALE")
        sale(e, "2000.00")
        val inv = invoice(e, InvoiceKind.SALES, "2000.00", "510.00")
        postings.on(InvoiceIssued(inv))
        balances(e).let {
            it["1500"]!!.signum() shouldBe 0
            it["1510"] shouldBe BigDecimal("2510.00")
            it["2500"] shouldBe BigDecimal("-510.00")
            it["3000"] shouldBe BigDecimal("-2000.00")
        }
        val pay = payment(e, PaymentDirection.INCOMING, "2510.00")
        postings.on(PaymentRecorded(pay))
        settle(inv, "2510.00", AllocationKind.PAYMENT, pay)
        balances(e).let {
            it["1510"]!!.signum() shouldBe 0
            it["2600"]!!.signum() shouldBe 0
            it["1900"] shouldBe BigDecimal("2510.00")
        }
        ledger.verifyJournal(e).isEmpty() shouldBe true
    }

    @Test
    fun `a reverse-charged purchase self-assesses VAT and a credit note reverses an invoice`() {
        val e = entity("SP-RC")
        ledger.post(
            PostingFacts(
                e,
                "LotReceived",
                Ids.newId(),
                LocalDate.of(2026, 10, 5),
                "EUR",
                "TEST",
                "lot",
                "lot",
                mapOf("cost" to BigDecimal("300.00")),
                mapOf("acquisitionSource" to "TRANSFER_IN"),
            ),
        )
        val purchase = invoice(e, InvoiceKind.PURCHASE, "300.00", "0", selfAssessed = "76.50")
        postings.on(InvoiceIssued(purchase))
        balances(e).let {
            it["2200"]!!.signum() shouldBe 0
            it["2210"] shouldBe BigDecimal("-300.00")
            it["1700"] shouldBe BigDecimal("76.50")
            it["2500"] shouldBe BigDecimal("-76.50")
        }
        val sale = invoice(e, InvoiceKind.SALES, "100.00", "25.50")
        postings.on(InvoiceIssued(sale))
        postings.on(InvoiceIssued(invoice(e, InvoiceKind.CREDIT_NOTE, "-100.00", "-25.50")))
        balances(e).let {
            it["1510"]!!.signum() shouldBe 0
            it["2500"] shouldBe BigDecimal("-76.50")
        }
    }

    @Test
    fun `netting clears receivables and payables through the clearing account`() {
        val e = entity("SP-NET")
        val receivable = invoice(e, InvoiceKind.SALES, "1000.00", "255.00")
        val payable = invoice(e, InvoiceKind.PURCHASE, "400.00", "102.00")
        postings.on(InvoiceIssued(receivable))
        postings.on(InvoiceIssued(payable))
        settle(receivable, "502.00", AllocationKind.NETTING)
        settle(payable, "502.00", AllocationKind.NETTING)
        val net = payment(e, PaymentDirection.INCOMING, "753.00")
        postings.on(PaymentRecorded(net))
        settle(receivable, "753.00", AllocationKind.PAYMENT, net)
        balances(e).let {
            it["1950"]!!.signum() shouldBe 0
            it["1510"]!!.signum() shouldBe 0
            it["2210"]!!.signum() shouldBe 0
            it["1900"] shouldBe BigDecimal("753.00")
        }
    }

    @Test
    fun `a receipt at a stronger rate than the invoice realises an FX gain`() {
        val e = entity("SP-FX")
        ledger.upsertFxRate(LocalDate.of(2026, 10, 1), "SEK", "EUR", BigDecimal("0.0880"))
        ledger.upsertFxRate(LocalDate.of(2026, 10, 19), "SEK", "EUR", BigDecimal("0.0900"))
        val inv = invoice(e, InvoiceKind.SALES, "10000.00", "0", currency = "SEK")
        postings.on(InvoiceIssued(inv))
        val pay = payment(e, PaymentDirection.INCOMING, "10000.00", currency = "SEK")
        postings.on(PaymentRecorded(pay))
        settle(inv, "10000.00", AllocationKind.PAYMENT, pay)
        val entries = ledger.entries(e, YearMonth.of(2026, 10), null, 100)
        entries.single { it.ruleCode == "FX_GAIN_RECEIVABLE" }.lines.single { it.accountCode == "3900" }.credit shouldBe BigDecimal("20.00")
        // In base currency the invoiced receivable is fully cleared.
        ledger.trialBalance(e, YearMonth.of(2026, 10)).single { it.accountCode == "1510" }.let { (it.debit - it.credit).signum() shouldBe 0 }
    }

    @Test
    fun `automatic postings for a closed period go to the next open period, and old charts get new accounts`() {
        val e = entity("SP-CLOSED")
        ledger.ensureChartOfAccounts(e)
        TestDatabase.dsl.execute("DELETE FROM etrm_ledger.gl_account WHERE legal_entity_id = ? AND code = '2600'", e)
        ledger.closePeriod(e, YearMonth.of(2026, 9), hard = true)
        val inv = invoice(e, InvoiceKind.SALES, "10.00", "2.55", date = LocalDate.of(2026, 9, 30))
        postings.on(InvoiceIssued(inv))
        val entry = ledger.entries(e, YearMonth.of(2026, 10), inv.number, 10).single()
        entry.postingDate shouldBe LocalDate.of(2026, 10, 1)
        entry.description shouldContain "period 2026-09 is closed"
        entry.lines.first().dimensions["originalDate"] shouldBe "2026-09-30"
        postings.on(PaymentRecorded(payment(e, PaymentDirection.INCOMING, "12.55")))
        ledger.accounts(e).any { it.code == "2600" } shouldBe true
    }
}
