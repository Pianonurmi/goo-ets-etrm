package com.pianonurmi.etrm

import com.pianonurmi.etrm.document.api.DocumentQuery
import com.pianonurmi.etrm.integrity.ChainVerificationJob
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.web.SensitiveReadAudit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * Review finding S6 end to end: the database learns who acts (history rows carry the user), reads of sensitive data
 * and the nightly chain verification land in the audit trail, and the verification sets its gauge and stores a digest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IntegrityIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var job: ChainVerificationJob

    @Autowired
    private lateinit var documents: DocumentQuery

    @Autowired
    private lateinit var meters: MeterRegistry

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler(HttpStatusCode::isError) { _, _ -> }.build() }

    private fun RestClient.RequestHeadersSpec<*>.as_(user: String, roles: String) = header("X-Actor", user).header("X-Roles", roles)

    @Test
    fun `history rows name the user who changed the record`() {
        val code = "HIST-" + UUID.randomUUID().toString().take(6).uppercase()
        val created = client.post().uri("/api/v1/reference/legal-entities").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("code" to code, "name" to "History Co", "country" to "FI", "validFrom" to "2026-01-01"))
            .as_("maria", "MIDDLE_OFFICE")
            .retrieve().body(Map::class.java)!!
        val actor = TestDatabase.dsl.fetchValue(
            "SELECT actor FROM etrm_referencedata.reference_history WHERE entity_id = ?::uuid ORDER BY id DESC LIMIT 1",
            created["id"].toString(),
        )
        actor shouldBe "maria"
    }

    @Test
    fun `a read of the audit trail is itself recorded`() {
        client.get().uri("/api/v1/audit/events?limit=1").as_("olga", "AUDITOR").retrieve().toBodilessEntity().statusCode.value() shouldBe 200
        @Suppress("UNCHECKED_CAST")
        val reads = client.get().uri("/api/v1/audit/events?eventType=${SensitiveReadAudit.EVENT_TYPE}&actor=olga")
            .as_("olga", "AUDITOR").retrieve().body(List::class.java) as List<Map<String, Any?>>
        reads.shouldNotBeEmpty()
        reads.last()["aggregateType"] shouldBe "AuditTrail"
        (reads.last()["payload"] as Map<*, *>)["path"].toString() shouldContain "/api/v1/audit/events"
    }

    @Test
    fun `the nightly verification finds every chain intact, records it and stores a digest`() {
        val report = job.run()
        report.auditBroken.shouldBeEmpty()
        report.lotsBroken.shouldBeEmpty()
        report.holdingsBroken.shouldBeEmpty()
        report.journalBroken.values.flatten().shouldBeEmpty()
        report.auditHead.shouldNotBeNull()
        ChainVerificationJob.CHAINS.forEach { chain ->
            meters.get("etrm.integrity.chain.ok").tag("chain", chain).gauge().value() shouldBe 1.0
        }
        @Suppress("UNCHECKED_CAST")
        val recorded = client.get().uri("/api/v1/audit/events?eventType=${ChainVerificationJob.EVENT_TYPE}")
            .as_("olga", "AUDITOR").retrieve().body(List::class.java) as List<Map<String, Any?>>
        val documentId = UUID.fromString(recorded.first()["aggregateRef"].toString())
        documents.document(documentId)!!.kind shouldBe ChainVerificationJob.DIGEST_KIND
        documents.verify(documentId) shouldBe true
        client.get().uri("/api/v1/audit/verify").as_("olga", "AUDITOR").retrieve().body(Map::class.java)!!["brokenSeqs"] shouldBe emptyList<Any>()
    }

    @Test
    fun `an administrator declares an archive cut past the retention, and the chains still verify`() {
        fun declare(cut: String, user: String, roles: String) = client.post().uri("/api/v1/admin/integrity/archive-cuts")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("cut" to cut, "reason" to "retention ended"))
            .as_(user, roles).retrieve().toBodilessEntity().statusCode.value()

        declare("2015-01-01T00:00:00Z", "olga", "AUDITOR") shouldBe 403
        declare("2026-01-01T00:00:00Z", "root", "ADMIN") shouldBe 422 // younger than etrm.integrity.archive-min-age
        declare("2015-01-02T00:00:00Z", "root", "ADMIN") shouldBe 422 // not a month start
        declare("2015-01-01T00:00:00Z", "root", "ADMIN") shouldBe 200
        declare("2015-01-01T00:00:00Z", "root", "ADMIN") shouldBe 422 // cuts only move forward

        val report = job.run()
        report.archiveCut shouldBe java.time.Instant.parse("2015-01-01T00:00:00Z")
        report.auditBroken.shouldBeEmpty()
        report.lotsBroken.shouldBeEmpty()
    }
}
