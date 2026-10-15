package com.pianonurmi.etrm

import com.pianonurmi.etrm.demo.DemoDataSeeder
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock

/** The demo profile seeds six months of business once, on its own database (its curves would clash with MonthEndIT's). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "demo")
class DemoDataSeederIT {
    companion object {
        private const val SEED_BUDGET_SECONDS = 120

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = SiblingDatabase.register(registry, "demo")
    }

    @LocalServerPort
    private var port: Int = 0

    /** The application's clock (ADR 0050): dates sent to it are its dates. */
    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var seeder: DemoDataSeeder

    @Autowired
    private lateinit var jdbc: JdbcClient

    private fun asUser(user: String, roles: String) =
        RestAssured.given().baseUri("http://localhost").port(port).header("X-Actor", user).header("X-Roles", roles)

    @Test
    fun `seeds six months of business in every area, once`() {
        val admin = { asUser("admin", "ADMIN") }
        val entities = admin().get("/api/v1/reference/legal-entities").then().statusCode(200).extract().jsonPath()
        entities.getList<String>("code") shouldContainAll listOf("DEMO-FI", "DEMO-SE")
        val finland = entities.getString("find { it.code == 'DEMO-FI' }.id")
        val sweden = entities.getString("find { it.code == 'DEMO-SE' }.id")
        entities.getString("find { it.code == 'DEMO-SE' }.baseCurrency") shouldBe "SEK"
        admin().get("/api/v1/reference/counterparties").then().body("size()", greaterThanOrEqualTo(11))

        trading(finland, sweden)
        inventory(finland, sweden)
        marketAndValuation(finland, sweden)
        ledger(finland)
        allocationAndRegistry(finland, sweden)
        availableToSell(finland)
        operations(finland, sweden)
        compliance(finland, sweden)
        clearing(finland)
        inboxes()
        home(finland)

        // The run log is public progress: the UI waits on it while the data is being created.
        RestAssured.given().baseUri("http://localhost").port(port).get("/api/v1/ui-config").then().statusCode(200)
            .body("demoSeed.state", equalTo("COMPLETED"))
            .body("demoSeed.completedSteps", equalTo(seeder.current()!!.stepCount))
            .body("demoSeed.stepCount", greaterThan(20))
        val run = seeder.current()!!
        // The plan's budget: the whole seed within two minutes on CI hardware.
        val log = jdbc.sql("select extract(epoch from finished_at - started_at)::int from etrm_demo.seed_run").query(Int::class.java).single()
        check(log < SEED_BUDGET_SECONDS) { "Seeding took $log s, over the $SEED_BUDGET_SECONDS s budget (${run.stepCount} steps)" }

        seeder.seed() shouldBe false
    }

    private fun trading(finland: String, sweden: String) {
        val trades = asUser("trader", "TRADER").get("/api/v1/trades?legalEntityId=$finland").then().statusCode(200).extract().jsonPath()
        trades.getList<Any>("").size shouldBeGreaterThanOrEqual 30
        trades.getList<String>("status") shouldContainAll listOf("CONFIRMED", "PENDING_APPROVAL", "CANCELLED", "DELIVERED")
        trades.getList<String>("findAll { it.instrumentType == 'FUTURE' }.id").size shouldBe 3
        trades.getList<String>("findAll { it.productFamily == 'EMISSION_ALLOWANCE' }.id").size shouldBeGreaterThanOrEqual 7
        trades.getList<Int>("findAll { it.version > 1 }.version").size shouldBeGreaterThanOrEqual 1
        // The intercompany sale has its mirror in the Swedish entity.
        asUser("trader", "TRADER").get("/api/v1/trades?legalEntityId=$sweden").then().statusCode(200)
            .body("size()", greaterThanOrEqualTo(6))
            .body("findAll { it.intercompanyLinkId != null }.size()", equalTo(1))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/confirmations?legalEntityId=$finland&status=MATCHED,DISPUTED,MISMATCHED").then().statusCode(200)
            .body("findAll { it.status == 'MATCHED' }.size()", equalTo(2))
            .body("findAll { it.status == 'DISPUTED' }.size()", equalTo(1))
            .body("findAll { it.status == 'MISMATCHED' }.size()", equalTo(1))
    }

    private fun inventory(finland: String, sweden: String) {
        val lots = asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/lots?holderEntityId=$finland").then().statusCode(200)
            .extract().jsonPath().getList<String>("status")
        lots.size shouldBeGreaterThanOrEqual 34
        // CANCELLED: the own-consumption obligation was allocated and cancelled in the registry.
        lots shouldContainAll listOf("HELD", "RESERVED", "SPLIT", "WITHDRAWN", "EXPIRED", "IN_TRANSFER", "TRANSFERRED_OUT", "CANCELLED")
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/lots?holderEntityId=$sweden").then().statusCode(200)
            .body("findAll { it.status == 'CANCELLED' }.size()", greaterThanOrEqualTo(1))
    }

    private fun marketAndValuation(finland: String, sweden: String) {
        val risk = { asUser("risk", "RISK") }
        risk().get("/api/v1/market-data/curves").then().body("size()", greaterThanOrEqualTo(5))
        risk().get("/api/v1/market-data/quotes/suspect").then().statusCode(200).body("size()", greaterThanOrEqualTo(1))
        risk().get("/api/v1/market-data/indices").then().body("code", hasItem("GOO_NORDIC_HYDRO_IDX"))
        asUser("risk2", "RISK").get("/api/v1/workflow/tasks?type=CURVE_SIGN_OFF&actionable=true").then().statusCode(200)
            .body("items.size()", equalTo(1))
        risk().get("/api/v1/valuation/runs?legalEntityId=$finland&from=2000-01-01&to=2100-01-01").then().statusCode(200)
            .body("findAll { it.status == 'OFFICIAL' }.size()", greaterThanOrEqualTo(9))
        risk().get("/api/v1/valuation/runs?legalEntityId=$sweden&from=2000-01-01&to=2100-01-01").then().statusCode(200)
            .body("findAll { it.status == 'OFFICIAL' }.size()", greaterThanOrEqualTo(7))
            .body("findAll { it.status == 'PENDING_SIGN_OFF' }.size()", equalTo(1))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/positions/snapshots?legalEntityId=$finland&kind=GOO&from=2000-01-01&to=2100-01-01").then()
            .statusCode(200).body("size()", greaterThanOrEqualTo(6))
    }

    private fun ledger(finland: String) {
        val accountant = { asUser("accountant", "ACCOUNTANT") }
        val periods = accountant().get("/api/v1/ledger/periods?legalEntityId=$finland").then().statusCode(200).extract().jsonPath()
        periods.getList<String>("findAll { it.status == 'CLOSED' }.period").size shouldBe 4
        periods.getList<String>("findAll { it.status == 'SOFT_CLOSED' }.period").size shouldBe 2
        accountant().get("/api/v1/ledger/period-end/runs?legalEntityId=$finland").then().statusCode(200).body("size()", greaterThanOrEqualTo(6))
        accountant().get("/api/v1/ledger/exports?legalEntityId=$finland").then().statusCode(200)
            .body("findAll { it.status == 'ACKNOWLEDGED' }.size()", equalTo(5))
            .body("findAll { it.status == 'DELIVERED' }.size()", equalTo(1))
        accountant().get("/api/v1/ledger/entries?legalEntityId=$finland&source=MANUAL").then().statusCode(200)
        asUser("accountant", "ACCOUNTANT").get("/api/v1/workflow/tasks?type=ACCOUNTING_POLICY_CHANGE&actionable=true").then()
            .body("items.size()", equalTo(1))
        asUser("accountant", "ACCOUNTANT").get("/api/v1/workflow/tasks?type=POSTING_RULE_CHANGE&actionable=true").then()
            .body("items.size()", equalTo(1))
    }

    private fun allocationAndRegistry(finland: String, sweden: String) {
        val backoffice = { asUser("backoffice", "BACK_OFFICE") }
        backoffice().get("/api/v1/allocation/plans?holderEntityId=$finland&activeOnly=false").then().statusCode(200)
            .body("findAll { it.status == 'CONFIRMED' }.size()", equalTo(2))
        val pending = backoffice().get("/api/v1/allocation/plans?holderEntityId=$sweden").then().statusCode(200)
            .body("findAll { it.status == 'PROPOSED' }.size()", equalTo(1))
            .extract().jsonPath()
        // The generic EU renewable sale is delivered with Nordic hydro, which is worth more than the generic mark.
        pending.getString("find { it.status == 'PROPOSED' }.value.giveAwayEur").toBigDecimal() shouldBeGreaterThan java.math.BigDecimal.ZERO
        pending.getString("find { it.status == 'PROPOSED' }.value.currency") shouldBe "EUR"
        pending.getString("find { it.status == 'PROPOSED' }.pricing.status") shouldBe "OPTIMAL"
        val instructions = backoffice().get("/api/v1/registry/instructions/search?holderEntityId=$finland&limit=200").then().statusCode(200)
            .extract().jsonPath()
        instructions.getList<String>("items.status") shouldContainAll listOf("CONFIRMED", "FAILED", "PENDING_APPROVAL")
        instructions.getList<String>("items.findAll { it.type == 'SURRENDER' }.status") shouldContainAll listOf("CONFIRMED")
    }

    private fun availableToSell(finland: String) {
        val trader = { asUser("trader", "TRADER").contentType("application/json") }
        val table = trader().get("/api/v1/allocation/headroom?holderEntityId=$finland").then().statusCode(200).extract().jsonPath()
        table.getList<String>("deliveryDates").size shouldBe 6
        table.getList<String>("rows.bucketCode") shouldContainAll listOf("NORDIC_HYDRO", "ANY_GOO")
        val nextMonthEnd = java.time.YearMonth.now(clock).plusMonths(1).atEndOfMonth()
        val hydro = """{"type":"all","of":[{"type":"countryInRegion","region":"NORDIC"},{"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"HYDRO"}]}"""
        val check = trader()
            .body(
                """{"holderEntityId":"$finland","demands":[
                   {"criterion":$hydro,"quantityMwh":1,"deliveryDate":"$nextMonthEnd","ref":"small"},
                   {"criterion":$hydro,"quantityMwh":100000000,"deliveryDate":"$nextMonthEnd","ref":"huge"}]}""",
            )
            .post("/api/v1/allocation/headroom/check").then().statusCode(200).extract().jsonPath()
        check.getBoolean("demands.find { it.ref == 'small' }.fits") shouldBe true
        check.getBoolean("demands.find { it.ref == 'huge' }.fits") shouldBe false
        check.getList<String>("demands.find { it.ref == 'huge' }.binding.bucketCode").size shouldBeGreaterThanOrEqual 1
        check.getBoolean("fits") shouldBe false

        val hierarchy = trader().get("/api/v1/products/buckets/hierarchy").then().statusCode(200).extract().jsonPath()
        hierarchy.getList<String>("nodes.find { it.code == 'NORDIC_HYDRO' }.parents") shouldContainAll listOf("EU_HYDRO", "NORDIC_RENEWABLE")
        val portfolio = trader().get("/api/v1/allocation/portfolio?holderEntityId=$finland").then().statusCode(200).extract().jsonPath()
        portfolio.getString("nodes[0].bucketCode") shouldBe "ANY_GOO"
        // The root holds everything its children hold.
        (
            portfolio.getLong(
                "nodes.find { it.bucketCode == 'ANY_GOO' }.longMwh",
            ) >= portfolio.getLong("nodes.find { it.bucketCode == 'NORDIC_HYDRO' }.longMwh")
        ) shouldBe true
    }

    private fun operations(finland: String, sweden: String) {
        val invoices = asUser("accountant", "ACCOUNTANT").get("/api/v1/settlement/invoices?legalEntityId=$finland").then().statusCode(200)
            .extract().jsonPath()
        invoices.getList<String>("paymentStatus") shouldContainAll listOf("PAID", "PARTIALLY_PAID", "UNPAID")
        invoices.getList<String>("kind") shouldContainAll listOf("SALES", "PURCHASE", "CREDIT_NOTE")
        invoices.getList<String>("findAll { it.status == 'ISSUED' && it.paymentStatus != 'PAID' && it.dueDate < '${java.time.LocalDate.now(clock)}' }.id")
            .size shouldBeGreaterThanOrEqual 1
        asUser("controller", "ACCOUNTANT").get("/api/v1/workflow/tasks?type=INVOICE_APPROVAL&actionable=true").then()
            .body("items.size()", equalTo(1))
        asUser("controller", "ACCOUNTANT").get("/api/v1/settlement/netting?legalEntityId=$finland").then().statusCode(200)
            .body("status", hasItems("DRAFT", "SETTLED"))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/registry/instructions/search?holderEntityId=$finland&status=PENDING_APPROVAL").then()
            .statusCode(200).body("items.size()", equalTo(1))
        val account = asUser("admin", "ADMIN").get("/api/v1/reference/registry-accounts?holderType=LEGAL_ENTITY&holderId=$finland").then().extract().jsonPath()
            .getString("find { it.accountNumber == 'SIM-DEMO-FI' }.id")
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/inventory/breaks?registryAccountId=$account").then().statusCode(200)
            .body("size()", equalTo(1)).body("[0].type", equalTo("MISSING_IN_REGISTRY"))
        val seAccount = asUser("admin", "ADMIN").get("/api/v1/reference/registry-accounts?holderType=LEGAL_ENTITY&holderId=$sweden").then().extract().jsonPath()
            .getString("find { it.accountNumber == 'SIM-DEMO-SE' }.id")
        asUser(
            "backoffice",
            "BACK_OFFICE",
        ).get("/api/v1/inventory/breaks?registryAccountId=$seAccount&status=PENDING_ACCEPTANCE,RESOLVED").then().statusCode(200)
            .body("status", hasItems("PENDING_ACCEPTANCE", "RESOLVED"))
    }

    /** Futures margined through the clearing account, the member's statements in, and one day's difference in the inbox. */
    private fun clearing(finland: String) {
        val account = asUser("backoffice", "BACK_OFFICE").get("/api/v1/clearing/accounts?legalEntityId=$finland").then().statusCode(200)
            .body("size()", equalTo(1)).extract().path<String>("[0].id")
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/clearing/accounts/$account/margin?from=2000-01-01&to=2100-01-01").then().statusCode(200)
            .body("size()", greaterThan(100))
            .body("findAll { it.lines.size() > 0 }.size()", greaterThan(50))
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/clearing/accounts/$account/statements?from=2000-01-01&to=2100-01-01").then().statusCode(200)
            .body("size()", equalTo(15))
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/clearing/breaks?legalEntityId=$finland&status=OPEN").then().statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].differences[0].kind", equalTo("VARIATION_MARGIN"))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/workflow/tasks?type=CLEARING_BREAK&actionable=true").then()
            .body("items.size()", equalTo(1))
        asUser("backoffice", "BACK_OFFICE").get("/api/v1/clearing/margin-calls?legalEntityId=$finland").then().statusCode(200)
            .body("findAll { it.status == 'PAID' }.amount", hasItems(3500.0f))
            .body("findAll { it.status == 'OPEN' }.amount", hasItems(1250.0f))
        asUser("accountant", "ACCOUNTANT").get("/api/v1/workflow/tasks?type=MARGIN_CALL&actionable=true").then()
            .body("items.size()", equalTo(1))
    }

    private fun compliance(finland: String, sweden: String) {
        asUser("risk", "RISK").get("/api/v1/risk/limits?legalEntityId=$finland&activeOnly=false").then().statusCode(200)
            .body("findAll { it.status == 'ACTIVE' }.size()", greaterThanOrEqualTo(5))
            .body("findAll { it.status == 'PENDING_APPROVAL' }.size()", equalTo(1))
        asUser("risk", "RISK").get("/api/v1/risk/breaches?legalEntityId=$sweden").then().statusCode(200)
            .body("findAll { it.status == 'OPEN' }.size()", greaterThanOrEqualTo(1))
        asUser("risk", "RISK").get("/api/v1/risk/exposures?legalEntityId=$finland").then().statusCode(200).body("size()", greaterThan(0))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/ets/installations?legalEntityId=$sweden").then().statusCode(200).body("size()", equalTo(1))
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/workflow/tasks?type=VERIFIED_EMISSIONS_APPROVAL&actionable=true").then()
            .body("items.size()", equalTo(1))
        asUser("middle", "MIDDLE_OFFICE")
            .get("/api/v1/reports/emir/events?legalEntityId=$finland&from=2000-01-01&to=2100-01-01").then().statusCode(200)
            .body("action", hasItems("NEWT", "EROR", "VALU"))
        asUser("auditor", "AUDITOR").get("/api/v1/reports/exports?legalEntityId=$finland&reportCode=EMIR_TRADE_ACTIVITY").then().statusCode(200)
            .body("size()", greaterThanOrEqualTo(3))
    }

    /** Every approving persona finds work waiting in the inbox. */
    private fun inboxes() {
        listOf(
            "middle" to "MIDDLE_OFFICE",
            "middle2" to "MIDDLE_OFFICE",
            "backoffice" to "BACK_OFFICE,REGISTRY_OPERATOR",
            "controller" to "ACCOUNTANT",
            "accountant" to "ACCOUNTANT",
            "risk" to "RISK",
            "risk2" to "RISK",
        ).forEach { (user, roles) ->
            val counts = asUser(user, roles).get("/api/v1/workflow/tasks/counts?actionable=true").then().statusCode(200)
                .extract().jsonPath().getMap<String, Int>("")
            check(counts.values.sum() >= 1) { "$user has nothing to do in the inbox: $counts" }
        }
    }

    /** The home dashboard shows each section only to roles that may read it (ADR 0034). */
    private fun home(finland: String) {
        asUser("middle", "MIDDLE_OFFICE").get("/api/v1/home/summary?legalEntityId=$finland").then().statusCode(200)
            .body("tasks.actionable", greaterThanOrEqualTo(2))
            .body("valuation.totalMtm", notNullValue())
            .body("valuation.history.size()", greaterThan(1))
            .body("reconciliation.open", equalTo(1))
            .body("settlement", notNullValue())
            .body("risk", notNullValue())
            .body("goo.liveMwh", greaterThan(0))
            .body("eua.complianceYear", notNullValue())
            .body("ledger", nullValue())
            .body("activity.size()", greaterThan(0))
        asUser("trader", "TRADER").get("/api/v1/home/summary?legalEntityId=$finland").then().statusCode(200)
            .body("valuation", notNullValue())
            .body("reconciliation", nullValue())
            .body("settlement", nullValue())
            .body("activity", nullValue())
        asUser("accountant", "ACCOUNTANT").get("/api/v1/home/summary?legalEntityId=$finland").then().statusCode(200)
            .body("ledger.period", notNullValue())
        asUser("scoped", "TRADER").header("X-Entities", "00000000-0000-0000-0000-000000000000")
            .get("/api/v1/home/summary?legalEntityId=$finland").then().statusCode(403)
    }
}
