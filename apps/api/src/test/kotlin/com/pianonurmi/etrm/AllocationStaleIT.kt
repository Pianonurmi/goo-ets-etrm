package com.pianonurmi.etrm

import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Approving a plan whose lot disappeared after the proposal must mark the plan STALE and commit that state.
 * Runs with real Spring transaction proxies: a failed reservation inside the approve transaction must not
 * poison it (regression for the rollback-only bug fixed with savepoints).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AllocationStaleIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `approving a plan whose lot was withdrawn marks it stale and keeps the state`() {
        val scenario = ApiScenario(port).referenceData()
        val lotId = scenario.receiveLot(1, 1000)
        scenario.confirmedHydroSale(600)
        val planId = scenario.proposePlan()

        scenario.asUser("backoffice").body("""{"reason":"registry correction"}""").post("/api/v1/inventory/lots/$lotId/withdraw")
            .then().statusCode(200).body("status", equalTo("WITHDRAWN"))

        scenario.asUser("approver").post("/api/v1/allocation/plans/$planId/approve").then().statusCode(200)
            .body("status", equalTo("STALE"))
        scenario.asUser("approver").get("/api/v1/allocation/plans/$planId").then().statusCode(200)
            .body("status", equalTo("STALE"))
    }
}
