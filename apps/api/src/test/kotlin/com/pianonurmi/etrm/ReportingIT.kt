package com.pianonurmi.etrm

import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Reports through REST: the catalogue, role checks, and every export numbered, hashed and audited. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReportingIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `reports are produced for permitted roles only, numbered per entity and audited`() {
        val s = ApiScenario(port).referenceData()
        s.receiveLot(1, 500)
        s.asUser("auditor", "AUDITOR").get("/api/v1/reports").then().statusCode(200)
            .body("code", hasItems("MTM_BY_TRADE", "CREDIT_EXPOSURE", "EMIR_TRADE_ACTIVITY", "MIFID_ANCILLARY_ACTIVITY", "GL_TRIAL_BALANCE"))

        val url = "/api/v1/reports/GOO_EXPIRY?legalEntityId=${s.entityId}&months=24&format=csv"
        s.asUser("trader", "TRADER").get(url).then().statusCode(403)
        val first = s.asUser("ops", "BACK_OFFICE").get(url).then().statusCode(200)
            .header("X-Report-Seq", "1").contentType("text/csv;charset=UTF-8").extract()
        first.body().asString().lines().first() shouldBe "month,mwh"
        first.body().asString().contains("500") shouldBe true
        val exportId = first.header("X-Report-Export")
        s.asUser("ops", "BACK_OFFICE").get(url).then().statusCode(200).header("X-Report-Seq", "2")

        s.asUser("auditor", "AUDITOR").get("/api/v1/reports/exports?legalEntityId=${s.entityId}&reportCode=GOO_EXPIRY").then().statusCode(200)
            .body("size()", equalTo(2)).body("[1].contentHash", equalTo(first.header("X-Content-Sha256")))
        s.asUser("auditor", "AUDITOR").get("/api/v1/reports/exports/$exportId").then().statusCode(200).body(equalTo(first.body().asString()))
        s.asUser("auditor", "AUDITOR").get("/api/v1/audit/events?eventType=ReportExported&actor=ops&aggregateRef=$exportId").then().statusCode(200)
        s.asUser("auditor", "AUDITOR").get("/api/v1/audit/events?eventType=ReportExported&actor=ops").then().statusCode(200)
            .body("payload.reportCode", hasItems("GOO_EXPIRY"))
        s.asUser("ops", "MIDDLE_OFFICE").get("/api/v1/reports/emir/completeness?legalEntityId=${s.entityId}").then().statusCode(200).body("size()", equalTo(0))
        s.asUser(
            "ops",
            "MIDDLE_OFFICE",
        ).get("/api/v1/reports/emir/field-rules").then().statusCode(200).body("find { it.fieldName == 'UTI' }.verified", equalTo(false))
    }
}
