package com.pianonurmi.etrm

import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** A what-if is exactly what proposing would give, and leaves no plan or reservation behind. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AllocationWhatIfIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `what-if equals the following proposal and writes nothing`() {
        val s = ApiScenario(port).referenceData()
        s.receiveLot(1, 1000)
        s.confirmedHydroSale(600)
        val body = """{"holderEntityId":"${s.entityId}","idempotencyKey":"ignored"}"""

        val whatIf = s.asUser("planner", "MIDDLE_OFFICE").body(body).post("/api/v1/allocation/plans/what-if").then().statusCode(200)
            .body("objective.allocatedMwh", equalTo(600)).extract().jsonPath()
        s.asUser("planner", "MIDDLE_OFFICE").get("/api/v1/allocation/plans?holderEntityId=${s.entityId}").then().statusCode(200)
            .body("size()", equalTo(0))

        val planId = s.proposePlan()
        val plan = s.asUser("planner", "MIDDLE_OFFICE").get("/api/v1/allocation/plans/$planId").then().extract().jsonPath()
        plan.getList<Int>("lines.quantityMwh") shouldBe whatIf.getList<Int>("lines.quantityMwh")
        plan.getList<String>("lines.lotId") shouldBe whatIf.getList<String>("lines.lotId")
        plan.getInt("coverage.totalCoveredMwh") shouldBe whatIf.getInt("coverage.totalCoveredMwh")

        s.asUser("auditor", "AUDITOR").body(body).post("/api/v1/allocation/plans/what-if").then().statusCode(403)
        s.asUser("outsider", "MIDDLE_OFFICE").header("X-Entities", ApiScenario(port).referenceData().entityId)
            .body(body).post("/api/v1/allocation/plans/what-if").then().statusCode(403)
    }
}
