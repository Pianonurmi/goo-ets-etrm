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
        /** Four from the desk, four delivered for settlement and one whose counterparty confirmation does not match. */
        private const val TRADES = 9

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
    fun `seeds entities, inventory, trades, approvals, accounting, risk, ETS and operations once`() {
        val entities = asUser("admin", "ADMIN").get("/api/v1/reference/legal-entities").then().statusCode(200)
            .extract().jsonPath().getList<String>("code")
        entities shouldContainAll listOf("DEMO-FI", "DEMO-SE")
        val finland = asUser("admin", "ADMIN").get("/api/v1/reference/legal-entities").then().extract().jsonPath()
            .getString("find { it.code == 'DEMO-FI' }.id")

        asUser("trader", "TRADER").get("/api/v1/trades?legalEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(TRADES))
            .body("findAll { it.status == 'CONFIRMED' }.size()", equalTo(2))
            .body("findAll { it.status == 'PENDING_APPROVAL' }.size()", equalTo(2))
        // Twelve issued; the pending transfer splits one into the part in transfer and the rest.
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/lots?holderEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(14)).body("findAll { it.status == 'IN_TRANSFER' }.size()", equalTo(1))
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

        operations(finland)

        // The run log is public progress: the UI waits on it while the data is being created.
        RestAssured.given().baseUri("http://localhost").port(port).get("/api/v1/ui-config").then().statusCode(200)
            .body("demoSeed.state", equalTo("COMPLETED"))
            .body("demoSeed.completedSteps", equalTo(seeder.current()!!.stepCount))
            .body("demoSeed.stepCount", greaterThan(5))

        seeder.seed() shouldBe false
        asUser("trader", "TRADER").get("/api/v1/trades?legalEntityId=$finland").then().body("size()", equalTo(TRADES))
    }

    private fun operations(finland: String) {
        asUser("accountant", "ACCOUNTANT").get("/api/v1/settlement/invoices?legalEntityId=$finland").then().statusCode(200)
            .body("findAll { it.status == 'ISSUED' }.size()", equalTo(3))
            .body("findAll { it.paymentStatus == 'PAID' }.size()", equalTo(1))
            .body("findAll { it.kind == 'PURCHASE' }.size()", equalTo(1))
            .body("findAll { it.status == 'PENDING_APPROVAL' }.size()", equalTo(1))
        asUser("controller", "ACCOUNTANT").get("/api/v1/workflow/tasks?type=INVOICE_APPROVAL&actionable=true").then()
            .body("items.size()", equalTo(1))
        asUser("controller", "ACCOUNTANT").get("/api/v1/settlement/netting?legalEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].status", equalTo("DRAFT"))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/registry/instructions/search?holderEntityId=$finland&status=PENDING_APPROVAL").then()
            .statusCode(200).body("items.size()", equalTo(1))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/workflow/tasks?type=INSTRUCTION_APPROVAL&actionable=true").then()
            .body("items.size()", equalTo(1))
        val account = asUser("admin", "ADMIN").get("/api/v1/reference/registry-accounts?holderType=LEGAL_ENTITY&holderId=$finland").then().extract().jsonPath()
            .getString("find { it.accountNumber == 'SIM-DEMO-FI' }.id")
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/statements?registryAccountId=$account").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].status", equalTo("BREAKS"))
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/breaks?registryAccountId=$account").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].type", equalTo("MISSING_IN_REGISTRY"))
        asUser("middle2", "MIDDLE_OFFICE").get("/api/v1/confirmations?legalEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].status", equalTo("MISMATCHED"))
        asUser("middle2", "MIDDLE_OFFICE").get("/api/v1/workflow/tasks?type=CONFIRMATION_BREAK&actionable=true").then()
            .body("items.size()", equalTo(1))
    }
}
