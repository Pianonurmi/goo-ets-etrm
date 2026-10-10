package com.pianonurmi.etrm

import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Users scoped to one legal entity see only that entity's trades and lots, and cannot name another entity. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EntityScopeIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `lists, details and entity parameters respect the user's legal entities`() {
        val mine = ApiScenario(port).referenceData()
        val theirs = ApiScenario(port).referenceData()
        val myTrade = mine.capturedHydroSale(100)
        val theirTrade = theirs.capturedHydroSale(100)
        val myLot = mine.receiveLot(1, 50)
        val theirLot = theirs.receiveLot(1, 50)

        fun scoped() = mine.asUser("scoped-trader", "TRADER,BACK_OFFICE").header("X-Entities", mine.entityId)

        scoped().get("/api/v1/trades?limit=500").then().statusCode(200)
            .body("id", hasItem(myTrade)).body("id", not(hasItem(theirTrade))).body("legalEntityId", everyItem(equalTo(mine.entityId)))
        scoped().get("/api/v1/trades/$myTrade").then().statusCode(200)
        scoped().get("/api/v1/trades/$theirTrade").then().statusCode(403)
        scoped().get("/api/v1/trades/$theirTrade/versions").then().statusCode(403)
        scoped().get("/api/v1/trades?legalEntityId=${theirs.entityId}").then().statusCode(403)

        scoped().get("/api/v1/inventory/lots").then().statusCode(200)
            .body("id", hasItem(myLot)).body("id", not(hasItem(theirLot)))
        scoped().get("/api/v1/inventory/lots/$theirLot").then().statusCode(403)
        scoped().get("/api/v1/inventory/lots/$theirLot/history").then().statusCode(403)
        scoped().get("/api/v1/inventory/expiry-ladder?holderEntityId=${theirs.entityId}").then().statusCode(403)
            .body("type", equalTo("urn:etrm:problem:forbidden"))
        scoped().get("/api/v1/inventory/expiry-ladder?holderEntityId=${mine.entityId}").then().statusCode(200)

        // Manual lot operations need back office (or registry operator) rights on the holder.
        mine.asUser("a-trader", "TRADER").body("{\"reason\":\"test\"}").post("/api/v1/inventory/lots/$myLot/withdraw").then().statusCode(403)
        scoped().body("{\"reason\":\"test\"}").post("/api/v1/inventory/lots/$theirLot/withdraw").then().statusCode(403)
        scoped().body("{\"reason\":\"registry correction\"}").post("/api/v1/inventory/lots/$myLot/withdraw").then().statusCode(200)

        // Manual trade operations need middle or back office.
        mine.asUser("a-trader", "TRADER").post("/api/v1/trades/$myTrade/settle").then().statusCode(403)
        mine.asUser("a-trader", "TRADER").post("/api/v1/trades/$myTrade/confirmations").then().statusCode(403)

        // Without X-Entities the dev/test actor sees every entity.
        mine.asUser("everyone").get("/api/v1/trades/$theirTrade").then().statusCode(200)
    }
}
