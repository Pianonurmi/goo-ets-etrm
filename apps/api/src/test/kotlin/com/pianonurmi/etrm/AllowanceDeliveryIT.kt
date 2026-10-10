package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * EU ETS deliveries end to end: an EUA sale delivered through a four-eyes Union Registry instruction to the buyer's
 * registered account, and an EUA purchase delivered by matching the receipt to the trade number.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AllowanceDeliveryIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    private fun ApiScenario.ownUnionAccount(): String = asUser("admin").body(
        """{"registryCode":"UNION_REGISTRY","accountNumber":"EU-100-$entityId","name":"Trading","accountType":"TRADING",
           "holderType":"LEGAL_ENTITY","holderId":"$entityId"}""",
    ).post("/api/v1/reference/registry-accounts").then().statusCode(201).extract().path("id")

    /** Registers the counterparty's Union Registry account through the four-eyes change request. */
    private fun ApiScenario.buyerUnionAccount(): String {
        val number = "EU-200-$counterpartyId"
        val requestId: String = asUser("ops", "REGISTRY_OPERATOR").body(
            """{"action":"CREATE","reason":"onboarding","registryCode":"UNION_REGISTRY","accountNumber":"$number","name":"Buyer","accountType":"TRADING"}""",
        ).post("/api/v1/reference/counterparties/$counterpartyId/registry-accounts/change-requests").then().statusCode(201).extract().path("id")
        val taskId: String = asUser("risk", "RISK").get("/api/v1/workflow/tasks?subjectRef=$requestId").then().extract().path("items[0].id")
        asUser("risk", "RISK").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200)
        return asUser("ops").get("/api/v1/reference/registry-accounts?holderType=COUNTERPARTY&holderId=$counterpartyId").then().statusCode(200)
            .extract().path("find { it.accountNumber == '$number' }.id")
    }

    private fun ApiScenario.confirmedEuaTrade(side: String, quantity: Long, price: String, ownAccount: String, buyerAccount: String?): String {
        val counterpartyAccount = buyerAccount?.let { "\"counterpartyAccountId\":\"$it\"," } ?: ""
        val tradeId: String = asUser("trader-$entityId").body(
            """{"legalEntityId":"$entityId","bookId":"$bookId","counterpartyId":"$counterpartyId","productFamily":"EMISSION_ALLOWANCE",
               "instrumentType":"SPOT","tradeDate":"2026-10-12","legs":[{"side":"$side","allowanceProductCode":"EUA","quantity":$quantity,
               "unit":"TCO2E","price":$price,"priceCurrency":"EUR","deliveryAccountId":"$ownAccount",$counterpartyAccount
               "deliveries":[{"deliveryWindow":{"start":"2026-10-12","endExclusive":"2026-10-20"},"deliveryDate":"2026-10-14","quantity":$quantity}]}]}""",
        ).post("/api/v1/trades").then().statusCode(201).extract().path("id")
        val task: String = asUser("middle-$entityId").get("/api/v1/workflow/tasks?subjectRef=$tradeId").then().extract().path("items[0].id")
        asUser("middle-$entityId").post("/api/v1/workflow/tasks/$task/approve").then().statusCode(200)
        asUser("backoffice").body("""{"confirmationRef":"CONF-$tradeId"}""").post("/api/v1/trades/$tradeId/confirm").then().statusCode(200)
        return tradeId
    }

    @Test
    fun `an EUA sale is delivered by a four-eyes Union Registry transfer and books the proceeds`() {
        val s = ApiScenario(port).referenceData()
        val ours = s.ownUnionAccount()
        val buyer = s.buyerUnionAccount()
        s.asUser("backoffice").body(
            """{"holderEntityId":"${s.entityId}","registryAccountId":"$ours","productCode":"EUA","type":"PURCHASE","quantity":1000,
               "effectiveDate":"2026-10-01","costPerUnit":65.00,"costCurrency":"EUR","reference":"OPENING"}""",
        ).post("/api/v1/inventory/holdings/movements").then().statusCode(201)
        val tradeId = s.confirmedEuaTrade("SELL", 400, "72.25", ours, buyer)
        val scheduleId: String = s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().extract().path("legs[0].deliveries[0].id")

        val before = Instant.now()
        val instructionId: String = s.asUser("operator-a").body("""{"scheduleId":"$scheduleId"}""").post("/api/v1/deliveries/allowances").then()
            .statusCode(201)
            .body("status", equalTo("PENDING_APPROVAL"))
            .body("type", equalTo("ALLOWANCE_TRANSFER"))
            .body("unit", equalTo("TCO2E"))
            .body("quantity", equalTo(400))
            .body("toAccountRef", equalTo("EU-200-${s.counterpartyId}"))
            .extract().path("id")
        // The buyer's account is not on the trusted list: the registry executes 26 hours after approval.
        val expected = Instant.parse(
            s.asUser("operator-a").get("/api/v1/registry/instructions/$instructionId").then().extract().path<String>("expectedEffectiveAt"),
        )
        (ChronoUnit.HOURS.between(before, expected) in 25..26) shouldBe true
        s.asUser("operator-a").body("""{"scheduleId":"$scheduleId"}""").post("/api/v1/deliveries/allowances").then().statusCode(201)
            .body("id", equalTo(instructionId))

        val taskId: String = s.asUser("operator-b").get("/api/v1/workflow/tasks?subjectRef=$instructionId&type=INSTRUCTION_APPROVAL").then()
            .extract().path("items[0].id")
        s.asUser("operator-b").post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200)
        s.asUser("operator-b").get("/api/v1/registry/instructions/$instructionId").then().body("status", equalTo("SENT")).body("channel", equalTo("MANUAL"))

        s.asUser("operator-b").body("""{"externalRef":"EU-TX-$tradeId"}""").post("/api/v1/registry/instructions/$instructionId/confirm").then()
            .statusCode(200).body("status", equalTo("CONFIRMED"))
        s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().body("status", equalTo("DELIVERED"))
            .body("legs[0].deliveries[0].deliveredQuantity", equalTo(400))
        s.asUser("backoffice").get("/api/v1/inventory/holdings?holderEntityId=${s.entityId}").then()
            .body("find { it.productCode == 'EUA' }.quantity", equalTo(600))
            .body("find { it.productCode == 'EUA' }.reservedQuantity", equalTo(0))
        val rules = s.asUser("accountant").get("/api/v1/ledger/entries?legalEntityId=${s.entityId}&period=2026-10").then().statusCode(200)
            .extract().jsonPath().getList<String>("ruleCode")
        rules shouldContainAll listOf("EUA_SALE_PROCEEDS", "EUA_SALE_COST")
    }

    @Test
    fun `an EUA purchase is delivered when allowances arrive with the trade number as reference`() {
        val s = ApiScenario(port).referenceData()
        val ours = s.ownUnionAccount()
        val tradeId = s.confirmedEuaTrade("BUY", 250, "70.00", ours, null)
        val tradeNumber: String = s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().extract().path("tradeNumber")

        s.asUser("backoffice").body(
            """{"holderEntityId":"${s.entityId}","registryAccountId":"$ours","productCode":"EUA","type":"PURCHASE","quantity":100,
               "effectiveDate":"2026-10-14","costPerUnit":70.00,"costCurrency":"EUR","reference":"$tradeNumber"}""",
        ).post("/api/v1/inventory/holdings/movements").then().statusCode(201)
        s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().body("status", equalTo("PARTIALLY_DELIVERED"))
            .body("legs[0].deliveries[0].deliveredQuantity", equalTo(100))

        // A receipt without the trade number stays unmatched until it is matched by hand.
        s.asUser("backoffice").body(
            """{"holderEntityId":"${s.entityId}","registryAccountId":"$ours","productCode":"EUA","type":"TRANSFER_IN","quantity":150,
               "effectiveDate":"2026-10-15","reference":"EU-TX-UNKNOWN"}""",
        ).post("/api/v1/inventory/holdings/movements").then().statusCode(201)
        val holdingId: String = s.asUser("backoffice").get("/api/v1/inventory/holdings?holderEntityId=${s.entityId}").then()
            .extract().path("find { it.productCode == 'EUA' }.id")
        val seq: Int = s.asUser("backoffice").get("/api/v1/inventory/holdings/$holdingId/movements").then()
            .extract().path("find { it.reference == 'EU-TX-UNKNOWN' }.seq")
        val scheduleId: String = s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().extract().path("legs[0].deliveries[0].id")
        val match = """{"scheduleId":"$scheduleId","holdingId":"$holdingId","movementSeq":$seq}"""
        s.asUser("backoffice").body(match).post("/api/v1/deliveries/receipts").then().statusCode(200).body("quantity", equalTo(150))
        s.asUser("backoffice").body(match).post("/api/v1/deliveries/receipts").then().statusCode(409)
        s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().body("status", equalTo("DELIVERED"))
    }

    @Test
    fun `a GoO purchase is delivered when the certificates arrive with the trade number as acquisition reference`() {
        val s = ApiScenario(port).referenceData()
        val tradeId: String = s.asUser("trader-${s.entityId}").body(
            """{"legalEntityId":"${s.entityId}","bookId":"${s.bookId}","counterpartyId":"${s.counterpartyId}","productFamily":"GUARANTEE_OF_ORIGIN",
               "instrumentType":"SPOT","tradeDate":"2026-10-12","legs":[{"side":"BUY","criterion":{"type":"countryInRegion","region":"NORDIC"},
               "quantity":300,"unit":"MWH","price":0.80,"priceCurrency":"EUR","deliveryAccountId":"${s.gooAccountId}",
               "deliveries":[{"deliveryWindow":{"start":"2026-10-01","endExclusive":"2026-11-01"},"deliveryDate":"2026-10-15","quantity":300}]}]}""",
        ).post("/api/v1/trades").then().statusCode(201).extract().path("id")
        val task: String = s.asUser("middle-${s.entityId}").get("/api/v1/workflow/tasks?subjectRef=$tradeId").then().extract().path("items[0].id")
        s.asUser("middle-${s.entityId}").post("/api/v1/workflow/tasks/$task/approve").then().statusCode(200)
        s.asUser("backoffice").body("""{"confirmationRef":"CONF-$tradeId"}""").post("/api/v1/trades/$tradeId/confirm").then().statusCode(200)
        val tradeNumber: String = s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().extract().path("tradeNumber")

        s.asUser("backoffice").body(
            """{"holderEntityId":"${s.entityId}","registryAccountId":"${s.gooAccountId}","issuanceRef":"ISS-BUY-$tradeId","range":{"start":1,"end":300},
               "deviceGsrn":"${s.deviceGsrn}","productionPeriod":{"start":"2026-03-01","endExclusive":"2026-04-01"},"issueDate":"2026-04-12",
               "issuingBody":"SIM","issuingCountry":"FI","supportProduction":false,"supportInvestment":false,"acquisitionSource":"TRANSFER_IN",
               "acquisitionRef":"$tradeNumber","costBasisPerMwh":0.80,"costCurrency":"EUR"}""",
        ).post("/api/v1/inventory/lots").then().statusCode(201)
        s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().body("status", equalTo("DELIVERED"))
            .body("legs[0].deliveries[0].deliveredQuantity", equalTo(300))
    }
}
