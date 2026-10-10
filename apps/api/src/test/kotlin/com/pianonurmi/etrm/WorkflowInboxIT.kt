package com.pianonurmi.etrm

import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/** The approval inbox through the REST API with the real handlers registered by trade and allocation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkflowInboxIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a decision vetoed by the owning module rolls back completely, a valid one goes through`() {
        val s = ApiScenario(port).referenceData()
        // Captured by an assistant on behalf of the trader: the workflow's four-eyes check (requester = trader) passes
        // for the assistant, but the trade module refuses an approval by the person who captured the trade.
        val tradeId = s.capturedHydroSale(500, trader = "trader-x", capturedBy = "assistant-x")
        val taskId: String = s.asUser("middle-x").get("/api/v1/workflow/tasks?subjectRef=$tradeId").then().statusCode(200)
            .body("items.size()", equalTo(1)).extract().path("items[0].id")

        s.asUser("assistant-x").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(403)
        s.asUser("middle-x").get("/api/v1/workflow/tasks/$taskId").then().statusCode(200)
            .body("task.status", equalTo("OPEN"))
            .body("history.eventType", equalTo(listOf("OPENED")))
        s.asUser("middle-x").get("/api/v1/trades/$tradeId").then().body("status", equalTo("PENDING_APPROVAL"))

        s.asUser("middle-x").post("/api/v1/workflow/tasks/$taskId/claim").then().statusCode(200).body("assignee", equalTo("middle-x"))
        s.asUser("middle-x").body("""{"body":"Limits checked"}""").post("/api/v1/workflow/tasks/$taskId/comments").then().statusCode(201)
        s.asUser("middle-x").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200).body("status", equalTo("APPROVED"))
        s.asUser("middle-x").get("/api/v1/trades/$tradeId").then().body("status", equalTo("APPROVED"))
        s.asUser("middle-x").get("/api/v1/workflow/tasks/$taskId").then()
            .body("history.eventType", equalTo(listOf("OPENED", "CLAIMED", "COMMENTED", "DECIDED")))
            .body("comments[0].body", equalTo("Limits checked"))
    }

    @Test
    fun `task types are registered by their modules and entity scope is enforced`() {
        val s = ApiScenario(port).referenceData()
        s.asUser("viewer").get("/api/v1/workflow/task-types").then().statusCode(200)
            .body("taskType", hasItems("TRADE_APPROVAL", "ALLOCATION_APPROVAL"))
        val tradeId = s.capturedHydroSale(100)
        val taskId: String = s.asUser("middle-y").get("/api/v1/workflow/tasks?subjectRef=$tradeId").then()
            .extract().path("items[0].id")
        s.asUser("outsider", "MIDDLE_OFFICE").header("X-Entities", UUID.randomUUID().toString())
            .get("/api/v1/workflow/tasks/$taskId").then().statusCode(403)
        s.asUser("outsider", "MIDDLE_OFFICE").header("X-Entities", UUID.randomUUID().toString())
            .get("/api/v1/workflow/tasks?subjectRef=$tradeId").then().statusCode(200).body("items.size()", equalTo(0))
        s.asUser("insider", "MIDDLE_OFFICE").header("X-Entities", s.entityId)
            .get("/api/v1/workflow/tasks?subjectRef=$tradeId&actionable=true").then().statusCode(200).body("items.size()", equalTo(1))
    }
}
