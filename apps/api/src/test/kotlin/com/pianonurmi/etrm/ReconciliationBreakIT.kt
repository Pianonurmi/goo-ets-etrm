package com.pianonurmi.etrm

import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** A reconciliation break through the inbox: a task per break, claim to investigate, acceptance with four eyes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReconciliationBreakIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a break raised by a statement is investigated and accepted by a second person through the inbox`() {
        val s = ApiScenario(port).referenceData()
        val lotId = s.receiveLot(1, 100)
        val issuance: String = s.asUser("backoffice").get("/api/v1/inventory/lots/$lotId").then().extract().path("issuanceRef")
        val csv = "issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end\n$issuance,1,60,${s.deviceGsrn},2026-03-01,2026-04-01\n"
        s.asUser("ops-a", "REGISTRY_OPERATOR").contentType("text/plain").body(csv)
            .post("/api/v1/registry/statements/csv?registryAccountId=${s.gooAccountId}&statementDate=2026-10-11").then().statusCode(201)
            .body("status", equalTo("BREAKS"))
        val breakId: String = s.asUser("ops-a").get("/api/v1/inventory/breaks?registryAccountId=${s.gooAccountId}").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].type", equalTo("MISSING_IN_REGISTRY")).body("[0].range.start", equalTo(61))
            .extract().path("[0].id")

        val taskId: String = s.asUser("ops-a", "REGISTRY_OPERATOR").get("/api/v1/workflow/tasks?subjectRef=$breakId&type=RECONCILIATION_BREAK").then()
            .body("items.size()", equalTo(1)).body("items[0].legalEntityId", equalTo(s.entityId)).extract().path("items[0].id")
        s.asUser("ops-a", "REGISTRY_OPERATOR").post("/api/v1/workflow/tasks/$taskId/claim").then().statusCode(200)
        s.asUser("ops-a").get("/api/v1/inventory/breaks/$breakId").then().body("status", equalTo("INVESTIGATING")).body("assignee", equalTo("ops-a"))
        // The work item closes with the break, not by a decision in the inbox.
        s.asUser("ops-a", "REGISTRY_OPERATOR").post("/api/v1/workflow/tasks/$taskId/acknowledge").then().statusCode(422)

        s.asUser("ops-a", "REGISTRY_OPERATOR").body("""{"note":"registry shows a pending correction on its side"}""")
            .post("/api/v1/registry/breaks/$breakId/accept").then().statusCode(200).body("status", equalTo("PENDING_ACCEPTANCE"))
        val acceptance: String = s.asUser("lead-b", "BACK_OFFICE").get("/api/v1/workflow/tasks?subjectRef=$breakId&type=BREAK_ACCEPTANCE").then()
            .body("items.size()", equalTo(1)).extract().path("items[0].id")
        s.asUser("ops-a", "REGISTRY_OPERATOR,BACK_OFFICE").post("/api/v1/workflow/tasks/$acceptance/approve").then().statusCode(403)
        s.asUser("lead-b", "BACK_OFFICE").body("""{"reason":"agreed with the registry"}""")
            .post("/api/v1/workflow/tasks/$acceptance/approve").then().statusCode(200)

        s.asUser("ops-a").get("/api/v1/inventory/breaks/$breakId").then()
            .body("status", equalTo("ACCEPTED")).body("resolvedBy", equalTo("lead-b")).body("acceptanceRequestedBy", equalTo("ops-a"))
        s.asUser("ops-a").get("/api/v1/workflow/tasks/$taskId").then().body("task.status", equalTo("COMPLETED"))
        s.asUser("ops-a").get("/api/v1/inventory/breaks/ageing").then().statusCode(200).body("upToOneDay", notNullValue())
    }
}
