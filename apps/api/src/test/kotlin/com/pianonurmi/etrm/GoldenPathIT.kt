package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * End-to-end golden path through the REST API with the simulator registry:
 * reference data -> certificates received -> GoO sale captured, approved (four eyes) and confirmed ->
 * coverage shows the sale covered -> allocation proposed and approved by a second user -> registry transfer
 * confirmed -> trade delivered -> ledger shows revenue and COGS -> positions and audit reflect it;
 * then EUA free allocation, purchase and surrender through the holdings API with the compliance ladder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GoldenPathIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var entityId: String
    private lateinit var bookId: String
    private lateinit var counterpartyId: String
    private lateinit var gooAccountId: String
    private lateinit var etsAccountId: String
    private lateinit var tradeId: String
    private lateinit var scheduleId: String
    private lateinit var lotId: String
    private lateinit var planId: String

    private fun asUser(user: String, roles: String = "TRADER,MIDDLE_OFFICE,BACK_OFFICE,ACCOUNTANT,REGISTRY_OPERATOR"): RequestSpecification =
        RestAssured.given().baseUri("http://localhost").port(port).contentType(ContentType.JSON).header("X-Actor", user).header("X-Roles", roles)

    @BeforeAll
    fun setUp() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    @Test
    @Order(1)
    fun `reference data`() {
        entityId = asUser("admin").body(
            """{"code":"DEMO-OY","name":"Demo Energy Oy","country":"FI","validFrom":"2026-01-01"}""",
        ).post("/api/v1/reference/legal-entities").then().statusCode(201).extract().path("id")
        bookId = asUser("admin").body("""{"legalEntityId":"$entityId","code":"GOO-TRADING","name":"GoO trading","bookType":"TRADING"}""")
            .post("/api/v1/reference/books").then().statusCode(201).extract().path("id")
        counterpartyId = asUser("admin").body("""{"code":"EXT-DE","name":"External Energie GmbH","country":"DE"}""")
            .post("/api/v1/reference/counterparties").then().statusCode(201).extract().path("id")
        gooAccountId = asUser("admin").body(
            """{"registryCode":"SIMULATOR","accountNumber":"SIM-DEMO-1","name":"Demo GoO account","accountType":"HOLDING",
               "holderType":"LEGAL_ENTITY","holderId":"$entityId"}""",
        ).post("/api/v1/reference/registry-accounts").then().statusCode(201).extract().path("id")
        etsAccountId = asUser("admin").body(
            """{"registryCode":"UNION_REGISTRY","accountNumber":"EU-100-DEMO","name":"Demo OHA","accountType":"OPERATOR_HOLDING",
               "holderType":"LEGAL_ENTITY","holderId":"$entityId"}""",
        ).post("/api/v1/reference/registry-accounts").then().statusCode(201).extract().path("id")
        asUser("admin").body(
            """{"gsrn":"643000000000000016","name":"Demo hydro","country":"FI","capacityKw":25000,"commissioningDate":"2012-05-01",
               "energySourceCode":"HYDRO_RESERVOIR","technologyCode":"HYDRO_TURBINE_STORAGE","registryCode":"SIMULATOR",
               "ownerType":"LEGAL_ENTITY","ownerId":"$entityId","ownDevice":true}""",
        ).post("/api/v1/reference/production-devices").then().statusCode(201)
    }

    @Test
    @Order(2)
    fun `certificates received into inventory`() {
        lotId = asUser("backoffice").body(
            """{"holderEntityId":"$entityId","registryAccountId":"$gooAccountId","issuanceRef":"SIM-2026-0001","range":{"start":1,"end":5000},
               "deviceGsrn":"643000000000000016","productionPeriod":{"start":"2026-03-01","endExclusive":"2026-04-01"},"issueDate":"2026-04-12",
               "issuingBody":"SIM","issuingCountry":"FI","supportProduction":false,"supportInvestment":false,"acquisitionSource":"ISSUANCE",
               "costBasisPerMwh":1.20,"costCurrency":"EUR"}""",
        ).post("/api/v1/inventory/lots").then().statusCode(201).body("status", org.hamcrest.Matchers.equalTo("HELD")).extract().path("id")
        asUser("backoffice").get("/api/v1/ledger/entries?legalEntityId=$entityId&sourceRef=$lotId").then().statusCode(200)
            .body("size()", org.hamcrest.Matchers.equalTo(1)).body("[0].ruleCode", org.hamcrest.Matchers.equalTo("GOO_ISSUANCE"))
    }

    @Test
    @Order(3)
    fun `GoO sale captured, approved by a second user and confirmed`() {
        val response = asUser("trader1").body(
            """{"legalEntityId":"$entityId","bookId":"$bookId","counterpartyId":"$counterpartyId","productFamily":"GUARANTEE_OF_ORIGIN","instrumentType":"FORWARD",
               "tradeDate":"2026-05-04","legs":[{"side":"SELL","criterion":{"type":"all","of":[
                 {"type":"countryInRegion","region":"NORDIC"},{"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"HYDRO"},
                 {"type":"vintage","rule":{"type":"productionYear","year":2026}}]},
               "quantity":3000,"unit":"MWH","price":1.95,"priceCurrency":"EUR","deliveryAccountId":"$gooAccountId",
               "counterpartyAccountRef":"SIMULATOR:SIM-EXT-DE",
               "deliveries":[{"deliveryWindow":{"start":"2026-10-01","endExclusive":"2027-01-01"},"deliveryDate":"2026-12-15","quantity":3000}]}]}""",
        ).post("/api/v1/trades").then().statusCode(201).body("status", org.hamcrest.Matchers.equalTo("PENDING_APPROVAL")).extract()
        tradeId = response.path("id")
        scheduleId = response.path("legs[0].deliveries[0].id")

        asUser("trader1").post("/api/v1/trades/$tradeId/approve").then().statusCode(403)
        val task: String = asUser("middle").get("/api/v1/workflow/tasks?legalEntityId=$entityId&actionable=true").then().statusCode(200)
            .body("items.size()", org.hamcrest.Matchers.equalTo(1)).body("items[0].type", org.hamcrest.Matchers.equalTo("TRADE_APPROVAL"))
            .extract().path("items[0].id")
        asUser("middle").post("/api/v1/workflow/tasks/$task/approve").then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("APPROVED"))
        asUser("backoffice").body("""{"confirmationRef":"CONF-DEMO-1"}""").post("/api/v1/trades/$tradeId/confirm").then().statusCode(200)
            .body("status", org.hamcrest.Matchers.equalTo("CONFIRMED"))
        asUser("backoffice").get("/api/v1/allocation/obligations?holderEntityId=$entityId").then().statusCode(200)
            .body("size()", org.hamcrest.Matchers.equalTo(1)).body("[0].kind", org.hamcrest.Matchers.equalTo("SALE"))
            .body("[0].targetRegistryCode", org.hamcrest.Matchers.equalTo("SIMULATOR"))
    }

    @Test
    @Order(4)
    fun `coverage, allocation with four eyes, registry transfer and delivery`() {
        asUser("trader1").get("/api/v1/allocation/coverage?holderEntityId=$entityId").then().statusCode(200)
            .body("totalDemandMwh", org.hamcrest.Matchers.equalTo(3000)).body("totalCoveredMwh", org.hamcrest.Matchers.equalTo(3000))
        planId = asUser("trader1").body("""{"holderEntityId":"$entityId","idempotencyKey":"demo-plan-1"}""")
            .post("/api/v1/allocation/plans").then().statusCode(201).body("status", org.hamcrest.Matchers.equalTo("PROPOSED"))
            .body("allocatedMwh", org.hamcrest.Matchers.equalTo(3000)).extract().path("id")
        asUser("trader1").post("/api/v1/allocation/plans/$planId/approve").then().statusCode(403)
        asUser("middle").post("/api/v1/allocation/plans/$planId/approve").then().statusCode(200)
            .body("status", org.hamcrest.Matchers.equalTo("CONFIRMED")).body("lines[0].status", org.hamcrest.Matchers.equalTo("CONFIRMED"))
        asUser("backoffice").get("/api/v1/trades/$tradeId").then().statusCode(200).body("status", org.hamcrest.Matchers.equalTo("DELIVERED"))
        asUser("backoffice").get("/api/v1/inventory/lots?holderEntityId=$entityId&status=HELD").then().statusCode(200)
            .body("quantityMwh.sum()", org.hamcrest.Matchers.equalTo(2000))
        asUser("backoffice").get("/api/v1/registry/instructions?registryCode=SIMULATOR").then().statusCode(200).body("size()", org.hamcrest.Matchers.equalTo(0))
    }

    @Test
    @Order(5)
    fun `ledger shows revenue and cost of goods sold, positions and audit reflect the flow`() {
        val entries = asUser("accountant").get("/api/v1/ledger/entries?legalEntityId=$entityId").then().statusCode(200).extract().jsonPath()
        val ruleCodes = entries.getList<String>("ruleCode")
        ruleCodes shouldContainAll listOf("GOO_ISSUANCE", "TRADE_SALE_COMMITMENT", "GOO_SALE_DELIVERY_REVENUE", "GOO_SALE_DELIVERY_COGS")
        val revenue = entries.getList<Map<String, Any>>("findAll { it.ruleCode == 'GOO_SALE_DELIVERY_REVENUE' }.lines.flatten()")
        revenue.first { it["accountCode"] == "3000" }["credit"].toString().toDouble() shouldBe 5850.0
        asUser("accountant").get("/api/v1/ledger/trial-balance?legalEntityId=$entityId&period=2026-10").then().statusCode(200)
        asUser("accountant").get("/api/v1/ledger/journal/verify?legalEntityId=$entityId").then().statusCode(200).body("ok", org.hamcrest.Matchers.equalTo(true))

        asUser("risk").get("/api/v1/positions/goo?legalEntityId=$entityId").then().statusCode(200)
            .body("totalLiveMwh", org.hamcrest.Matchers.equalTo(2000)).body("totalOpenObligationMwh", org.hamcrest.Matchers.equalTo(0))
            .body("inventory[0].bucketCode", org.hamcrest.Matchers.equalTo("FI_HYDRO"))
        val audit = asUser("auditor").get("/api/v1/audit/events?aggregateRef=$tradeId").then().statusCode(200).extract().jsonPath().getList<String>("eventType")
        audit shouldContainAll listOf("TradeCaptured", "TradeApproved", "TradeConfirmed")
        asUser("auditor").get("/api/v1/inventory/journal/verify").then().statusCode(200).body("ok", org.hamcrest.Matchers.equalTo(true))
    }

    @Test
    @Order(6)
    fun `EU ETS holdings and compliance ladder`() {
        fun move(type: String, qty: Long, price: String?, year: Int) = asUser("backoffice").body(
            """{"holderEntityId":"$entityId","registryAccountId":"$etsAccountId","productCode":"EUA","type":"$type","quantity":$qty,"effectiveDate":"2026-06-30",
               ${price?.let { "\"costPerUnit\":$it,\"costCurrency\":\"EUR\"," } ?: ""}"complianceYear":$year}""",
        ).post("/api/v1/inventory/holdings/movements").then().statusCode(201)
        move("FREE_ALLOCATION", 10_000, null, 2026)
        move("PURCHASE", 4_000, "72.50", 2026)
        asUser("risk").body("""{"legalEntityId":"$entityId","complianceYear":2026,"forecastEmissionsTco2e":12000,"freeAllocationExpected":0}""")
            .put("/api/v1/positions/ets/inputs").then().statusCode(200).body("heldAllowances", org.hamcrest.Matchers.equalTo(14000))
            .body("netPosition", org.hamcrest.Matchers.equalTo(2000))
        move("SURRENDER", 12_000, null, 2026)
        asUser(
            "risk",
        ).get(
            "/api/v1/positions/ets?legalEntityId=$entityId&complianceYear=2026",
        ).then().statusCode(200).body("heldAllowances", org.hamcrest.Matchers.equalTo(2000))
        val eua = asUser(
            "accountant",
        ).get("/api/v1/ledger/entries?legalEntityId=$entityId&period=2026-06").then().statusCode(200).extract().jsonPath().getList<String>("ruleCode")
        eua shouldContainAll listOf("EUA_PURCHASE_INTANGIBLE", "EUA_SURRENDER")
        asUser("risk").post("/api/v1/positions/snapshots?legalEntityId=$entityId").then().statusCode(200).body("size()", org.hamcrest.Matchers.equalTo(2))
        planId shouldNotBe null
    }
}
