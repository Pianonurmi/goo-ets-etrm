package com.pianonurmi.etrm

import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Amending a confirmed sale closes its delivery obligation; confirming the new version opens it again. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AmendedTradeObligationIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a confirmed sale can be amended and confirmed again`() {
        val s = ApiScenario(port).referenceData()
        val tradeId = s.confirmedHydroSale(300)
        s.asUser("backoffice").get("/api/v1/allocation/obligations?holderEntityId=${s.entityId}").then().statusCode(200)
            .body("find { it.tradeRef != null }.status", equalTo("OPEN"))

        val version: Int = s.asUser("trader-${s.tag}").get("/api/v1/trades/$tradeId").then().extract().path("version")
        s.asUser("trader-${s.tag}")
            .body("""{"expectedVersion":$version,"reason":"Broker ticket added","externalRef":"BRK-1"}""")
            .put("/api/v1/trades/$tradeId").then().statusCode(200).body("status", equalTo("PENDING_APPROVAL"))
        s.asUser("middle").post("/api/v1/trades/$tradeId/approve").then().statusCode(200)
        s.asUser("backoffice").body("""{"confirmationRef":"CONF-V2"}""").post("/api/v1/trades/$tradeId/confirm").then().statusCode(200)
            .body("status", equalTo("CONFIRMED"))

        s.asUser("backoffice").get("/api/v1/allocation/obligations?holderEntityId=${s.entityId}").then().statusCode(200)
            .body("findAll { it.tradeRef != null }.size()", equalTo(1))
            .body("find { it.tradeRef != null }.status", equalTo("OPEN"))
            .body("find { it.tradeRef != null }.quantityMwh", equalTo(300))
    }
}
