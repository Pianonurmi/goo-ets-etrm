package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** The blotter search: one query per page, keyset cursor, free-text match and entity scope. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TradeSearchIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `pages newest first, matches text and stays in scope`() {
        val s = ApiScenario(port).referenceData()
        val other = ApiScenario(port).referenceData()
        val ids = listOf(100L, 200L, 300L).map { s.capturedHydroSale(it) }
        val otherId = other.capturedHydroSale(50)
        val numbers = ids.map { s.asUser("trader").get("/api/v1/trades/$it").then().extract().path<String>("tradeNumber") }

        val first = s.asUser("trader").get("/api/v1/trades/search?legalEntityId=${s.entityId}&limit=2").then().statusCode(200)
            .body("items.size()", equalTo(2)).extract().jsonPath()
        val cursor = first.getString("nextCursor")
        val second = s.asUser("trader").get("/api/v1/trades/search?legalEntityId=${s.entityId}&limit=2&cursor=$cursor").then().statusCode(200)
            .body("items.size()", equalTo(1)).body("nextCursor", nullValue()).extract().jsonPath()
        (first.getList<String>("items.tradeNumber") + second.getList<String>("items.tradeNumber")) shouldContainExactly numbers.reversed()
        first.getString("items[0].side") shouldBe "SELL"
        first.getFloat("items[0].notional") shouldBe 585.0f // 300 MWh at 1.95
        first.getInt("items[0].legCount") shouldBe 1

        s.asUser("trader").get("/api/v1/trades/search?q=${numbers[1].lowercase()}").then().statusCode(200)
            .body("items.size()", equalTo(1)).body("items[0].id", equalTo(ids[1]))
        s.asUser("scoped", "TRADER").header("X-Entities", s.entityId).get("/api/v1/trades/search?limit=500").then().statusCode(200)
            .body("items.findAll { it.id == '$otherId' }.size()", equalTo(0))
        s.asUser("trader").get("/api/v1/trades/search?cursor=garbage").then().statusCode(422)
    }
}
