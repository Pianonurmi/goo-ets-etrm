package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqSeedRepository
import com.pianonurmi.etrm.ledger.api.EntryStatus
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.ManualJournal
import com.pianonurmi.etrm.ledger.api.ManualLine
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.PostingRuleSeeder
import com.pianonurmi.etrm.ledger.domain.ChainedEntry
import com.pianonurmi.etrm.ledger.domain.JournalChain
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The journal's protection in the database (review findings D2, D4, D8): the only update an entry may undergo is
 * POSTED to REVERSED by its reversal, the chain covers the header, an entry is reversed once even under concurrency,
 * nothing is written into a hard-closed period, and every committed entry balances.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JournalIntegrityIT {
    private val actor = ActorProvider { Actor("accountant", "Accountant", setOf(Role.ACCOUNTANT), allEntities = true) }
    private val clock = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var ledger: LedgerService
    private lateinit var entityId: UUID
    private val transactions by lazy { TransactionTemplate(DataSourceTransactionManager(TestDatabase.dataSource)) }

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "ledger")
        val dsl = TestDatabase.dsl
        val publisher = ApplicationEventPublisher { }
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), TaxonomyService(JooqTaxonomyRepository(dsl), publisher), publisher)
        val unposted = LedgerWiring.unposted(dsl, publisher)
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
            gates = LedgerWiring.gates(dsl),
            unposted = unposted,
        )
        PostingRuleSeeder(JooqPostingRuleRepository(dsl), JooqSeedRepository(dsl)).seed()
        entityId = referenceData.createLegalEntity(CreateLegalEntity("INTEGRITY-CO", "Integrity Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        ledger.ensureChartOfAccounts(entityId)
    }

    private fun post(
        description: String,
        date: LocalDate = LocalDate.of(
            2026,
            9,
            1,
        ),
        amount: BigDecimal = BigDecimal(100),
    ): JournalEntryView = ledger.postManual(
        ManualJournal(
            entityId,
            date,
            description,
            "EUR",
            listOf(ManualLine("1400", debit = amount), ManualLine("2200", credit = amount)),
        ),
    )

    private fun <T> connection(block: (Connection) -> T): T = TestDatabase.dataSource.connection.use(block)

    private fun Connection.exec(sql: String, vararg args: Any?) = prepareStatement(sql).use { s ->
        args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
        s.execute()
    }

    @Test
    fun `an entry cannot be edited, deleted or marked reversed without its reversal`() {
        val entry = post("protected")
        connection { c ->
            shouldThrow<SQLException> { c.exec("UPDATE etrm_ledger.journal_entry SET description = 'edited' WHERE id = ?", entry.id) }
                .message shouldContain "post a reversal"
            shouldThrow<SQLException> { c.exec("UPDATE etrm_ledger.journal_entry SET status = 'REVERSED', reversed_by = ? WHERE id = ?", entry.id, entry.id) }
            shouldThrow<SQLException> { c.exec("UPDATE etrm_ledger.journal_entry SET status = 'REVERSAL' WHERE id = ?", entry.id) }
            shouldThrow<SQLException> { c.exec("DELETE FROM etrm_ledger.journal_entry WHERE id = ?", entry.id) }
            shouldThrow<SQLException> { c.exec("UPDATE etrm_ledger.journal_line SET debit = debit + 1 WHERE entry_id = ?", entry.id) }
            shouldThrow<SQLException> { c.exec("DELETE FROM etrm_ledger.journal_line WHERE entry_id = ?", entry.id) }
        }
        ledger.reverse(entry.id, "allowed").status shouldBe EntryStatus.REVERSAL
        ledger.entry(entry.id)!!.status shouldBe EntryStatus.REVERSED
        connection { c ->
            shouldThrow<SQLException> { c.exec("UPDATE etrm_ledger.journal_entry SET status = 'POSTED', reversed_by = NULL WHERE id = ?", entry.id) }
        }
        ledger.verifyJournal(entityId).shouldBeEmpty()
    }

    @Test
    fun `the chain covers the header, so a change made with the triggers off is found`() {
        val entry = post("tamper target")
        connection { c ->
            c.autoCommit = false
            c.exec("SET LOCAL session_replication_role = replica")
            c.exec("UPDATE etrm_ledger.journal_entry SET description = 'tampered' WHERE id = ?", entry.id)
            c.commit()
        }
        ledger.verifyJournal(entityId) shouldContainExactly listOf(entry.id)
        connection { c ->
            c.autoCommit = false
            c.exec("SET LOCAL session_replication_role = replica")
            c.exec("UPDATE etrm_ledger.journal_entry SET description = 'tamper target' WHERE id = ?", entry.id)
            c.commit()
        }
        ledger.verifyJournal(entityId).shouldBeEmpty()
    }

    @Test
    fun `two concurrent reversals of one entry - one succeeds, the other is refused`() {
        val entry = post("reversed twice")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            pool.submit<Result<JournalEntryView>> {
                start.await()
                runCatching { transactions.execute { ledger.reverse(entry.id, "race $it") } }
            }
        }
        start.countDown()
        val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        outcomes.count { it.isSuccess } shouldBe 1
        outcomes.single { it.isFailure }.exceptionOrNull()!!::class shouldBe ValidationException::class
        ledger.entries(entityId, sourceRef = null, limit = 500).count { it.reversalOf == entry.id } shouldBe 1
        ledger.verifyJournal(entityId).shouldBeEmpty()
    }

    @Test
    fun `nothing is inserted into a hard-closed period`() {
        val template = post("template", LocalDate.of(2026, 3, 10))
        ledger.closePeriod(entityId, YearMonth.of(2026, 3), hard = false)
        ledger.closePeriod(entityId, YearMonth.of(2026, 3), hard = true)
        connection { c ->
            shouldThrow<SQLException> {
                c.exec(
                    """
                    INSERT INTO etrm_ledger.journal_entry (id, entry_number, legal_entity_id, period, posting_date, event_type, description,
                        currency, status, created_by, hash)
                    SELECT ?, nextval('etrm_ledger.journal_entry_seq'), legal_entity_id, period, posting_date, event_type, 'late', currency,
                        'POSTED', created_by, 'x'
                    FROM etrm_ledger.journal_entry WHERE id = ?
                    """.trimIndent(),
                    UUID.randomUUID(),
                    template.id,
                )
            }.message shouldContain "is closed"
        }
    }

    @Test
    fun `an entry whose lines do not balance does not commit`() {
        val template = post("balance template")
        connection { c ->
            c.autoCommit = false
            val id = UUID.randomUUID()
            c.exec(
                """
                INSERT INTO etrm_ledger.journal_entry (id, entry_number, legal_entity_id, period, posting_date, event_type, description,
                    currency, status, created_by, hash)
                SELECT ?, nextval('etrm_ledger.journal_entry_seq'), legal_entity_id, period, posting_date, event_type, 'raw', currency,
                    'POSTED', created_by, 'x'
                FROM etrm_ledger.journal_entry WHERE id = ?
                """.trimIndent(),
                id,
                template.id,
            )
            c.exec(
                """
                INSERT INTO etrm_ledger.journal_line (id, entry_id, line_no, account_id, debit, credit, currency)
                SELECT ?, ?, 1, account_id, 10, 0, currency FROM etrm_ledger.journal_line WHERE entry_id = ? AND line_no = 1
                """.trimIndent(),
                UUID.randomUUID(),
                id,
                template.id,
            )
            shouldThrow<SQLException> { c.commit() }.message shouldContain "unbalanced"
        }
    }

    @Test
    fun `manual amounts have at most two decimals, so what is stored is what was hashed`() {
        shouldThrow<ValidationException> { post("three decimals", amount = BigDecimal("10.125")) }.message shouldContain "two decimals"
        post("trailing zeros", amount = BigDecimal("10.1200")).lines.first().debit shouldBe BigDecimal("10.12")
        ledger.verifyJournal(entityId).shouldBeEmpty()
    }

    @Test
    fun `entries hashed with version 1 still verify`() {
        val entry = post("version one")
        val v1 = entry.copy(hash = JournalChain.hash(entry.prevHash, entry, JournalChain.V1))
        JournalChain.verify(listOf(ChainedEntry(v1.copy(prevHash = null, hash = JournalChain.hash(null, v1, JournalChain.V1)), JournalChain.V1)))
            .shouldBeEmpty()
        JournalChain.verify(
            listOf(ChainedEntry(v1.copy(prevHash = null, hash = JournalChain.hash(null, v1, JournalChain.V1)), JournalChain.V2)),
        ) shouldContainExactly
            listOf(entry.id)
    }
}
