package com.pianonurmi.etrm

import io.restassured.path.json.JsonPath
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Our confirmation is issued on approval; a differing counterparty confirmation is overridden through the inbox. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConfirmationMatchingIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a mismatching counterparty confirmation is overridden by a second person and confirms the trade`() {
        val s = ApiScenario(port).referenceData()
        val tradeId = s.capturedHydroSale(500, "2.10")
        val task: String = s.asUser("middle").get("/api/v1/workflow/tasks?subjectRef=$tradeId").then().extract().path("items[0].id")
        s.asUser("middle").post("/api/v1/workflow/tasks/$task/approve").then().statusCode(200)

        val ours = s.asUser("backoffice").get("/api/v1/trades/$tradeId/confirmations").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].direction", equalTo("OURS")).body("[0].status", equalTo("ISSUED"))
            .extract().jsonPath()
        val documentId = ours.getString("[0].documentId")
        s.asUser("backoffice").get("/api/v1/documents/$documentId/content").then().statusCode(200).contentType(startsWith("application/pdf"))

        // The counterparty confirms a different price; their terms are ours otherwise.
        val terms = JsonPath.from(ours.prettify()).getMap<String, Any?>("[0].terms").toMutableMap()

        @Suppress("UNCHECKED_CAST")
        val leg = (terms["legs"] as List<Map<String, Any?>>).single().toMutableMap()
        leg["price"] = 2.20
        leg["amount"] = 1100.00
        terms["legs"] = listOf(leg)
        val body = mapOf("terms" to terms, "reference" to "THEIR-CONF-1", "source" to "MANUAL")
        val theirs: String = s.asUser("ops-a").body(body).post("/api/v1/confirmations/counterparty").then().statusCode(201)
            .body("status", equalTo("MISMATCHED")).extract().path("id")

        val breakTask: String = s.asUser("ops-a").get("/api/v1/workflow/tasks?subjectRef=$theirs&type=CONFIRMATION_BREAK").then()
            .body("items.size()", equalTo(1)).extract().path("items[0].id")
        s.asUser("ops-a").post("/api/v1/workflow/tasks/$breakTask/approve").then().statusCode(403)
        s.asUser("lead-b").body("""{"reason":"price confirmed by phone"}""").post("/api/v1/workflow/tasks/$breakTask/approve").then().statusCode(200)

        s.asUser("lead-b").get("/api/v1/confirmations/$theirs").then().body("status", equalTo("OVERRIDDEN")).body("decidedBy", equalTo("lead-b"))
        s.asUser("lead-b").get("/api/v1/trades/$tradeId").then().body("status", equalTo("CONFIRMED")).body("confirmationRef", equalTo("THEIR-CONF-1"))
    }
}
