package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.clearing.api.MarginRunView
import com.pianonurmi.etrm.clearing.api.VariationMarginComputed
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.application.ClearingPostings
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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

/** Variation margin is a realised result against cash at the clearing member; a new version posts only the difference. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClearingPostingsIT {
    private val actor = ActorProvider { Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var ledger: LedgerService
    private lateinit var postings: ClearingPostings
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
            MapFxRates(),
            JooqGlExportRepository(dsl),
            referenceData,
            actor,
            publisher,
            clock,
            JooqGlInterfaceRepository(dsl),
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        postings = ClearingPostings(ledger)
    }

    private fun run(entity: UUID, date: LocalDate, version: Int, margin: String) = MarginRunView(
        id = Ids.newId(),
        accountId = UUID.nameUUIDFromBytes("ECC".toByteArray()),
        legalEntityId = entity,
        businessDate = date,
        version = version,
        currency = "EUR",
        variationMargin = BigDecimal(margin),
        lines = emptyList(),
        inputsHash = "h$version",
        computedBy = "system",
        computedAt = clock.instant(),
        supersededAt = null,
    )

    private fun balances(entity: UUID): Map<String, BigDecimal> =
        ledger.trialBalance(entity, YearMonth.of(2026, 10)).associate { it.accountCode to it.balance }

    @Test
    fun `daily margin moves cash at the clearing member and the realised result, and a corrected day posts the difference`() {
        val entity = referenceData.createLegalEntity(CreateLegalEntity("VM-POST", "VM Post Oy", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val monday = LocalDate.of(2026, 10, 5)
        postings.on(VariationMarginComputed(run(entity, monday, 1, "2000.00"), BigDecimal("0.00")))
        postings.on(VariationMarginComputed(run(entity, monday.plusDays(1), 1, "-3400.00"), BigDecimal("0.00")))
        balances(entity).let {
            it["1960"] shouldBe BigDecimal("-1400.00")
            it["3600"] shouldBe BigDecimal("1400.00")
        }
        // Tuesday corrected to -3 200: only +200 is posted.
        postings.on(VariationMarginComputed(run(entity, monday.plusDays(1), 2, "-3200.00"), BigDecimal("-3400.00")))
        balances(entity)["1960"] shouldBe BigDecimal("-1200.00")
        // An unchanged total posts nothing.
        val entries = ledger.entries(entity, YearMonth.of(2026, 10), null, 100, null, null).size
        postings.on(VariationMarginComputed(run(entity, monday.plusDays(1), 3, "-3200.00"), BigDecimal("-3200.00")))
        ledger.entries(entity, YearMonth.of(2026, 10), null, 100, null, null).size shouldBe entries
        ledger.verifyJournal(entity).shouldBeEmpty()
    }
}
