package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Review finding S7: a cancellation's beneficiary goes to the register, and the records that cannot forget (lot
 * journal, version history, audit trail) keep only the reference. An erasure, asked by an administrator and approved
 * by an auditor, removes the name from everything that still holds it, and the hash chains stay intact.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BeneficiaryErasureIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a beneficiary stays out of the chains and is erased with a second person's approval`() {
        val s = ApiScenario(port).referenceData()
        val name = "Erik Testersson ${s.tag}"
        val personId = "ID-${s.tag}"
        val lotId = s.receiveLot(1, 100)
        val cancelled = s.asUser("backoffice", "BACK_OFFICE").body(
            """{"quantity":40,"details":{"purpose":"ON_BEHALF_OF_THIRD_PARTY","beneficiaryName":"$name","beneficiaryId":"$personId",
               "consumptionCountry":"FI","consumptionPeriod":{"start":"2026-01-01","endExclusive":"2027-01-01"}}}""",
        ).post("/api/v1/inventory/lots/$lotId/cancel").then().statusCode(200)
            .body("cancellation.beneficiaryName", equalTo(name))
            .body("cancellation.beneficiaryRef", notNullValue())
            .extract()
        val cancelledLot: String = cancelled.path("id")
        val ref: String = cancelled.path("cancellation.beneficiaryRef")

        // Only the editable copies hold the name; the register entry is the one to erase.
        mentions(name) shouldBe mapOf("lot" to 1, "lot_event" to 0, "lot_version" to 0, "audit" to 0)
        referencesIn(ref) shouldBe true
        s.asUser("auditor-a", "AUDITOR").get("/api/v1/inventory/beneficiaries/$ref").then().statusCode(200)
            .body("name", equalTo(name)).body("identifier", equalTo(personId))

        // An administrator asks with a reason; neither the administrator nor anyone else may skip the inbox.
        s.asUser("backoffice", "BACK_OFFICE").body("""{"reason":"data subject request"}""").post("/api/v1/inventory/beneficiaries/$ref/erasure")
            .then().statusCode(403)
        s.asUser("admin-a", "ADMIN").body("""{"reason":"data subject request DS-1"}""").post("/api/v1/inventory/beneficiaries/$ref/erasure")
            .then().statusCode(200).body("erasureRequestedBy", equalTo("admin-a")).body("name", equalTo(name))
        val taskId: String = s.asUser("auditor-b", "AUDITOR").get("/api/v1/workflow/tasks?subjectRef=$ref&type=BENEFICIARY_ERASURE").then().statusCode(200)
            .extract().path("items[0].id")
        s.asUser("auditor-b", "AUDITOR").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200)

        s.asUser("auditor-a", "AUDITOR").get("/api/v1/inventory/beneficiaries/$ref").then().statusCode(200)
            .body("name", nullValue()).body("identifier", nullValue()).body("erasedBy", equalTo("auditor-b"))
        s.asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/lots/$cancelledLot").then().statusCode(200)
            .body("cancellation.beneficiaryName", nullValue()).body("cancellation.beneficiaryRef", equalTo(ref))
        mentions(name) shouldBe mapOf("lot" to 0, "lot_event" to 0, "lot_version" to 0, "audit" to 0)
        mentions(personId) shouldBe mapOf("lot" to 0, "lot_event" to 0, "lot_version" to 0, "audit" to 0)

        s.asUser("auditor-a", "AUDITOR").get("/api/v1/inventory/journal/verify").then().statusCode(200).body("ok", equalTo(true))
        s.asUser("auditor-a", "AUDITOR").get("/api/v1/audit/verify").then().statusCode(200).body("brokenSeqs.size()", equalTo(0))
    }

    private fun mentions(text: String): Map<String, Int> {
        val pattern = "%$text%"

        fun count(sql: String) = TestDatabase.dsl.fetchValue(sql, pattern) as Number
        return mapOf(
            "lot" to count("SELECT count(*) FROM etrm_inventory.certificate_lot WHERE cancellation::text LIKE ?").toInt(),
            "lot_event" to count("SELECT count(*) FROM etrm_inventory.lot_event WHERE payload::text LIKE ?").toInt(),
            "lot_version" to count("SELECT count(*) FROM etrm_inventory.certificate_lot_version WHERE data::text LIKE ?").toInt(),
            "audit" to count("SELECT count(*) FROM etrm_audit.audit_event WHERE payload::text LIKE ?").toInt(),
        )
    }

    private fun referencesIn(ref: String): Boolean =
        (TestDatabase.dsl.fetchValue("SELECT count(*) FROM etrm_inventory.lot_event WHERE payload::text LIKE ?", "%$ref%") as Number).toInt() > 0 &&
            (TestDatabase.dsl.fetchValue("SELECT count(*) FROM etrm_audit.audit_event WHERE payload::text LIKE ?", "%$ref%") as Number).toInt() > 0
}
