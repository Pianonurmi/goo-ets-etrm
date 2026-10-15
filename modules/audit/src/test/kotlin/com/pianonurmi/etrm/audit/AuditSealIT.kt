package com.pianonurmi.etrm.audit

import com.pianonurmi.etrm.audit.adapter.jooq.JooqAuditRepository
import com.pianonurmi.etrm.audit.application.AuditService
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The audit trail's seal chain (review finding S6): committed rows are sealed in order, a row changed after sealing
 * breaks the chain, a seal cannot be changed, and rows left unsealed are counted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditSealIT {
    private val actor = ActorProvider { Actor("auditor", "Auditor", setOf(Role.AUDITOR)) }

    private fun service(clock: Clock = Clock.systemUTC()) = AuditService(JooqAuditRepository(TestDatabase.dsl), actor, clock)

    @BeforeEach
    fun reset() {
        TestDatabase.migrate("audit")
    }

    private fun tamper(sql: String) = TestDatabase.dataSource.connection.use { c ->
        c.autoCommit = false
        c.createStatement().use {
            it.execute("SET LOCAL session_replication_role = replica")
            it.execute(sql)
        }
        c.commit()
    }

    @Test
    fun `rows are sealed in a chain that verifies, and a change after sealing is found`() {
        val audit = service()
        val first = audit.record("FirstThing", "Thing", "1")
        val second = audit.record("SecondThing", "Thing", "2", mapOf("amount" to "10.00"))
        audit.seal() shouldBeGreaterThanOrEqual 2
        audit.seal() shouldBe 0
        audit.verifyTrail().let {
            it.ok shouldBe true
            it.head.shouldNotBeNull()
        }

        tamper("UPDATE etrm_audit.audit_event SET payload = '{\"amount\": \"99.00\"}' WHERE seq = ${second.seq}")
        audit.verifyTrail().brokenSeqs shouldContainExactly listOf(second.seq)

        tamper("DELETE FROM etrm_audit.audit_event WHERE seq = ${first.seq}")
        audit.verifyTrail().brokenSeqs shouldContainExactly listOf(first.seq, second.seq)
    }

    @Test
    fun `a seal cannot be changed or removed, and rows without a seal are counted`() {
        val audit = service()
        audit.record("Sealed", "Thing", "1")
        audit.seal()
        shouldThrow<DataAccessException> { TestDatabase.dsl.execute("UPDATE etrm_audit.audit_seal SET hash = 'x'") }
        shouldThrow<DataAccessException> { TestDatabase.dsl.execute("DELETE FROM etrm_audit.audit_seal") }

        audit.record("NotYetSealed", "Thing", "2")
        audit.verifyTrail().unsealed shouldBe 0 // younger than the sealing delay
        val later = service(Clock.fixed(Instant.now().plus(Duration.ofHours(1)), ZoneOffset.UTC))
        later.verifyTrail().let {
            it.unsealed shouldBe 1
            it.ok shouldBe false
        }
        audit.seal()
        later.verifyTrail().ok shouldBe true
    }

    @Test
    fun `the request's correlation id is kept when a record carries none`() {
        val id = TestDatabase.dataSource.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("SELECT set_config('etrm.correlation', 'trace-123', true)") }
            val seq = c.createStatement().use {
                it.executeQuery(
                    "INSERT INTO etrm_audit.audit_event (event_id, occurred_at, actor, event_type, payload) " +
                        "VALUES (gen_random_uuid(), now(), 'auditor', 'Correlated', '{}') RETURNING seq",
                ).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            c.commit()
            seq
        }
        TestDatabase.dsl.fetchValue("SELECT correlation_id FROM etrm_audit.audit_event WHERE seq = $id") shouldBe "trace-123"
        service().record("Uncorrelated", "Thing", "3").correlationId shouldNotBe "trace-123"
    }

    @Test
    fun `rows before an archive cut may be gone, rows after it may not`() {
        val march = service(Clock.fixed(Instant.parse("2026-03-10T12:00:00Z"), ZoneOffset.UTC))
        val old = march.record("OldThing", "Thing", "1")
        val audit = service()
        val recent = audit.record("RecentThing", "Thing", "2")
        audit.seal(catchUp = true) shouldBeGreaterThanOrEqual 2

        tamper("DELETE FROM etrm_audit.audit_event WHERE seq = ${old.seq}")
        audit.verifyTrail().brokenSeqs shouldContainExactly listOf(old.seq)

        shouldThrow<ValidationException> { audit.declareArchiveCut(Instant.parse("2026-04-02T00:00:00Z"), "not a month start") }
        audit.declareArchiveCut(Instant.parse("2026-04-01T00:00:00Z"), "retention of 2026-03 ended")
        audit.archiveCut() shouldBe Instant.parse("2026-04-01T00:00:00Z")
        audit.seal(catchUp = true)
        audit.verifyTrail().brokenSeqs.shouldBeEmpty()
        shouldThrow<ValidationException> { audit.declareArchiveCut(Instant.parse("2026-03-01T00:00:00Z"), "backwards") }
        shouldThrow<DataAccessException> { TestDatabase.dsl.execute("DELETE FROM etrm_audit.audit_archive_cut") }

        tamper("DELETE FROM etrm_audit.audit_event WHERE seq = ${recent.seq}")
        audit.verifyTrail().brokenSeqs shouldContainExactly listOf(recent.seq)
    }
}
