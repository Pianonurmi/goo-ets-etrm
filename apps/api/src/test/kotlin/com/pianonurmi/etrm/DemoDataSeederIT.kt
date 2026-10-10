package com.pianonurmi.etrm

import com.pianonurmi.etrm.demo.DemoDataSeeder
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** The demo profile seeds a usable world once, on its own database (its curves would clash with MonthEndIT's). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "demo")
class DemoDataSeederIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = SiblingDatabase.register(registry, "demo")
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var seeder: DemoDataSeeder

    private fun asUser(user: String, roles: String) =
        RestAssured.given().baseUri("http://localhost").port(port).header("X-Actor", user).header("X-Roles", roles)

    @Test
    fun `seeds entities, inventory, trades, approvals, accounting, risk and ETS once`() {
        val entities = asUser("admin", "ADMIN").get("/api/v1/reference/legal-entities").then().statusCode(200)
            .extract().jsonPath().getList<String>("code")
        entities shouldContainAll listOf("DEMO-FI", "DEMO-SE")
        val finland = asUser("admin", "ADMIN").get("/api/v1/reference/legal-entities").then().extract().jsonPath()
            .getString("find { it.code == 'DEMO-FI' }.id")

        asUser("trader", "TRADER").get("/api/v1/trades?legalEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(4))
            .body("findAll { it.status == 'CONFIRMED' }.size()", equalTo(2))
            .body("findAll { it.status == 'PENDING_APPROVAL' }.size()", equalTo(2))
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/lots?holderEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(12))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/workflow/tasks?type=TRADE_APPROVAL&actionable=true").then().statusCode(200)
            .body("items.size()", greaterThanOrEqualTo(2))
        asUser("risk2", "RISK").get("/api/v1/workflow/tasks?type=CURVE_SIGN_OFF&actionable=true").then().statusCode(200)
            .body("items.size()", equalTo(1))
        asUser("accountant", "ACCOUNTANT").get("/api/v1/ledger/period-end/runs?legalEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(1))
        asUser("risk", "RISK").get("/api/v1/valuation/runs?legalEntityId=$finland&from=2000-01-01&to=2100-01-01").then().statusCode(200)
            .body("findAll { it.status == 'OFFICIAL' }.size()", greaterThan(1))
        asUser("risk", "RISK").get("/api/v1/risk/limits?legalEntityId=$finland&activeOnly=false").then().statusCode(200)
            .body("findAll { it.status == 'ACTIVE' }.size()", equalTo(4))
            .body("findAll { it.status == 'PENDING_APPROVAL' }.size()", equalTo(1))
        asUser("risk2", "RISK").get("/api/v1/workflow/tasks?type=LIMIT_CHANGE&actionable=true").then().statusCode(200)
            .body("items.size()", equalTo(1))
        asUser("risk", "RISK").get("/api/v1/risk/exposures?legalEntityId=$finland").then().statusCode(200)
            .body("size()", greaterThan(0))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/ets/installations?legalEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(1))

        seeder.seed() shouldBe false
        asUser("trader", "TRADER").get("/api/v1/trades?legalEntityId=$finland").then().body("size()", equalTo(4))
    }
}
