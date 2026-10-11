package com.pianonurmi.etrm

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Code sets, buckets, party details and the audit trail change or open only for the roles that maintain them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MasterDataGuardsIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the front office cannot change master data or read the audit trail`() {
        val s = ApiScenario(port).referenceData()
        val code = "GUARD_${System.nanoTime() % 100_000_000}"

        fun trader() = s.asUser("guard-trader", "TRADER")

        fun middle() = s.asUser("guard-middle", "MIDDLE_OFFICE")

        trader().body("""{"code":"$code","name":"Guard label"}""").post("/api/v1/taxonomy/LABEL").then().statusCode(403)
        middle().body("""{"code":"$code","name":"Guard label"}""").post("/api/v1/taxonomy/LABEL").then().statusCode(201)
        trader().body("""{"name":"Renamed","expectedVersion":1}""").patch("/api/v1/taxonomy/LABEL/$code").then().statusCode(403)
        trader().body("""["FI"]""").put("/api/v1/taxonomy/REGION/NORDIC/countries").then().statusCode(403)

        trader().body("""{"code":"$code","name":"Guard bucket","criterion":{"type":"matchAll"}}""").post("/api/v1/products/buckets")
            .then().statusCode(403)
        trader().body("""{"name":"Renamed","expectedVersion":1}""").patch("/api/v1/products/buckets/NORDIC_HYDRO").then().statusCode(403)

        val address = """{"addressType":"BILLING","line1":"Street 1","postalCode":"00100","city":"Helsinki","country":"FI"}"""
        trader().body(address).put("/api/v1/reference/parties/COUNTERPARTY/${s.counterpartyId}/addresses").then().statusCode(403)
        s.asUser("guard-accountant", "ACCOUNTANT").body(address).put("/api/v1/reference/parties/COUNTERPARTY/${s.counterpartyId}/addresses")
            .then().statusCode(200)
        s.asUser("guard-scoped", "ACCOUNTANT").header("X-Entities", s.counterpartyId).body(address)
            .put("/api/v1/reference/parties/LEGAL_ENTITY/${s.entityId}/addresses").then().statusCode(403)

        trader().get("/api/v1/audit/events?limit=1").then().statusCode(403)
        s.asUser("guard-auditor", "AUDITOR").get("/api/v1/audit/events?limit=1").then().statusCode(200)
    }
}
