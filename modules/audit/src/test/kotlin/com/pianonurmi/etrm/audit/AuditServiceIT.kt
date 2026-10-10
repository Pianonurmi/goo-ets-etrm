package com.pianonurmi.etrm.audit

import com.pianonurmi.etrm.audit.adapter.jooq.JooqAuditRepository
import com.pianonurmi.etrm.audit.api.AuditFilter
import com.pianonurmi.etrm.audit.application.AuditService
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditServiceIT {
    data class SomethingHappened(val thing: Thing, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

    data class Thing(val id: UUID, val name: String)

    private lateinit var audit: AuditService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("audit")
        audit = AuditService(JooqAuditRepository(TestDatabase.dsl), ActorProvider { Actor("auditor", "Auditor", setOf(Role.AUDITOR)) }, Clock.systemUTC())
    }

    @Test
    fun `domain events are recorded once with actor and aggregate reference, and cannot be altered`() {
        val thing = Thing(UUID.randomUUID(), "lot 42")
        val event = SomethingHappened(thing)
        audit.on(event)
        audit.on(event) // duplicate delivery is ignored
        val recorded = audit.events(AuditFilter(eventType = "SomethingHappened", aggregateRef = thing.id.toString()))
        recorded shouldHaveSize 1
        recorded.single().let {
            it.actor shouldBe "auditor"
            it.aggregateType shouldBe "thing"
            (it.payload["thing"] as Map<*, *>)["name"] shouldBe "lot 42"
        }
        audit.record("GL_EXPORTED", "LEGAL_ENTITY", "LE-1", mapOf("period" to "2026-09")).eventType shouldBe "GL_EXPORTED"
        audit.events(AuditFilter(actor = "auditor")).size shouldBe 2
        shouldThrow<DataAccessException> { TestDatabase.dsl.execute("DELETE FROM etrm_audit.audit_event") }
    }
}
