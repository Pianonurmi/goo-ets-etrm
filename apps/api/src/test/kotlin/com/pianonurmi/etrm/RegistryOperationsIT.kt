package com.pianonurmi.etrm

import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Registry operations through REST: four-eyes manual instructions and registry configuration changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegistryOperationsIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a manual transfer waits for a second person, then executes`() {
        val s = ApiScenario(port).referenceData()
        val lotId = s.receiveLot(1, 200)
        val instructionId: String = s.asUser("operator-a").body(
            """{"lotId":"$lotId","quantityMwh":50,"toRegistryCode":"SIMULATOR","toAccountRef":"SIM-OTHER","idempotencyKey":"manual-${s.entityId}"}""",
        ).post("/api/v1/registry/instructions/transfers").then().statusCode(201)
            .body("status", equalTo("PENDING_APPROVAL")).body("channel", equalTo("SIMULATOR")).extract().path("id")
        val taskId: String = s.asUser("operator-b").get("/api/v1/workflow/tasks?subjectRef=$instructionId&type=INSTRUCTION_APPROVAL").then()
            .body("items.size()", equalTo(1)).extract().path("items[0].id")
        s.asUser("operator-a").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(403)
        s.asUser("operator-b").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200).body("status", equalTo("APPROVED"))
        s.asUser("operator-b").get("/api/v1/registry/instructions/$instructionId").then()
            .body("status", equalTo("CONFIRMED")).body("approvedBy", equalTo("operator-b"))
    }

    @Test
    fun `registry integration mode changes only after approval and formats are listed`() {
        val s = ApiScenario(port)
        val version: Int = s.asUser("ops").get("/api/v1/reference/registries").then().extract().path("find { it.code == 'CERTIQ' }.version")
        val requestId: String = s.asUser("ops", "REGISTRY_OPERATOR").body(
            """{"integration":"MANUAL","reason":"file drop unavailable","expectedVersion":$version}""",
        ).post("/api/v1/reference/registries/CERTIQ/change-requests").then().statusCode(201).body("status", equalTo("PENDING")).extract().path("id")
        s.asUser("ops").get("/api/v1/reference/registries").then().body("find { it.code == 'CERTIQ' }.integration", equalTo("FILE"))
        val taskId: String = s.asUser("risk").get("/api/v1/workflow/tasks?subjectRef=$requestId").then()
            .body("items[0].type", equalTo("REFERENCE_DATA_CHANGE")).extract().path("items[0].id")
        s.asUser("ops", "REGISTRY_OPERATOR,MIDDLE_OFFICE").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(403)
        s.asUser("risk", "RISK").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200)
        s.asUser("ops").get("/api/v1/reference/registries").then().body("find { it.code == 'CERTIQ' }.integration", equalTo("MANUAL"))
        s.asUser("ops").get("/api/v1/reference/change-requests/$requestId").then().body("status", equalTo("APPLIED"))

        s.asUser("ops").get("/api/v1/registry/statement-formats").then().statusCode(200)
            .body("kind", hasItems("GOO_HOLDINGS", "GOO_TRANSACTIONS"))
    }
}
