package com.pianonurmi.etrm

import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** A credit limit through REST: four-eyes approval, a trade over it needs risk's approval, the breach and its end. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CreditLimitBreachIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val RISK = "RISK"
    }

    @LocalServerPort
    private var port: Int = 0

    private fun task(s: ApiScenario, type: String, subject: String): String = s.asUser("risk-b", RISK)
        .get("/api/v1/workflow/tasks?subjectRef=$subject&type=$type").then().statusCode(200)
        .body("items.size()", equalTo(1)).extract().path("items[0].id")

    private fun approvedLimit(s: ApiScenario, amount: Int, replaces: String? = null): String {
        val limitId: String = s.asUser("risk-a", RISK).body(
            """{"legalEntityId":"${s.entityId}","type":"CREDIT_EXPOSURE","scope":{"counterpartyId":"${s.counterpartyId}"},"amount":$amount,
               "currency":"EUR","reason":"credit review"${replaces?.let { ",\"replaces\":\"$it\"" } ?: ""}}""",
        ).post("/api/v1/risk/limits").then().statusCode(201).body("status", equalTo("PENDING_APPROVAL")).extract().path("id")
        val taskId = task(s, "LIMIT_CHANGE", limitId)
        s.asUser("risk-a", RISK).post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(403)
        s.asUser("risk-b", RISK).post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200)
        s.asUser("risk-a", RISK).get("/api/v1/risk/limits/$limitId").then().statusCode(200).body("status", equalTo("ACTIVE"))
        return limitId
    }

    @Test
    fun `a trade over the credit limit needs risk's approval, and the breach is tracked until the limit is raised`() {
        val s = ApiScenario(port).referenceData()
        val limitId = approvedLimit(s, 100)

        // 1 000 MWh at 1.95 adds a potential future exposure of at least 195 EUR: over the limit of 100.
        val tradeId = s.capturedHydroSale(1000)
        s.asUser("trader").get("/api/v1/trades/$tradeId").then().statusCode(200)
            .body("status", equalTo("PENDING_APPROVAL")).body("approvalFindings[0].code", equalTo("CREDIT_LIMIT"))
            .body("approvalFindings[0].severity", equalTo("REQUIRES_RISK_APPROVAL"))
        val approval = task(s, "TRADE_APPROVAL", tradeId)
        s.asUser("middle", "MIDDLE_OFFICE").post("/api/v1/workflow/tasks/$approval/approve").then().statusCode(403)
        s.asUser("risk-b", RISK).post("/api/v1/workflow/tasks/$approval/approve").then().statusCode(200)

        // Approval re-measures the entity: the trade's PFE breaches the limit and a breach task opens.
        s.asUser("risk-a", RISK).get("/api/v1/risk/limits/$limitId/utilisation").then().statusCode(200).body("status", equalTo("BREACH"))
        val breachId: String = s.asUser("risk-a", RISK).get("/api/v1/risk/breaches?legalEntityId=${s.entityId}").then().statusCode(200)
            .body("size()", equalTo(1)).extract().path("[0].id")
        task(s, "LIMIT_BREACH", breachId)
        s.asUser("risk-a", RISK).get("/api/v1/risk/exposures?legalEntityId=${s.entityId}").then().statusCode(200)
            .body("find { it.counterpartyId == '${s.counterpartyId}' }.pfe", org.hamcrest.Matchers.notNullValue())

        approvedLimit(s, 1_000_000, replaces = limitId)
        s.asUser("risk-a", RISK).get("/api/v1/risk/breaches?legalEntityId=${s.entityId}").then().statusCode(200).body("size()", equalTo(0))
        s.asUser("risk-b", RISK).get("/api/v1/workflow/tasks?subjectRef=$breachId&type=LIMIT_BREACH&status=COMPLETED").then()
            .body("items.size()", equalTo(1))
        s.asUser("risk-a", RISK).body("""{"legalEntityId":"${s.entityId}","type":"CREDIT_EXPOSURE","scope":{},"amount":1,"currency":"EUR","reason":"x"}""")
            .post("/api/v1/risk/limits").then().statusCode(422)
        s.asUser("trader", "TRADER").body("""{"legalEntityId":"${s.entityId}","type":"TENOR_MAX_MONTHS","scope":{},"amount":12,"reason":"x"}""")
            .post("/api/v1/risk/limits").then().statusCode(403)
    }
}
