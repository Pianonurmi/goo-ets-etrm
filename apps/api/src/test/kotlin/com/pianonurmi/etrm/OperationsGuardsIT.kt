package com.pianonurmi.etrm

import io.restassured.path.json.JsonPath
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
import java.time.LocalDate

/**
 * Settlement, registry instructions, reconciliation and counterparty confirmations over REST: the front office is
 * refused, and a user scoped to one legal entity sees and works only that entity's records.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OperationsGuardsIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    /** One entity's operations data: a delivered sale, a pending transfer, a statement break and a mismatched confirmation. */
    private data class Operations(
        val s: ApiScenario,
        val itemId: String,
        val instructionId: String,
        val breakId: String,
        val confirmationId: String,
        val documentId: String,
    )

    private fun operations(): Operations {
        val s = ApiScenario(port).referenceData()
        val sold = s.confirmedHydroSale(100)
        val scheduleId: String = s.asUser("backoffice").get("/api/v1/trades/$sold").then().extract().path("legs[0].deliveries[0].id")
        s.asUser("backoffice").body("""{"scheduleId":"$scheduleId","quantity":100,"deliveredOn":"${LocalDate.now()}"}""")
            .post("/api/v1/trades/$sold/deliveries").then().statusCode(200)
        val itemId: String = s.asUser("backoffice").get("/api/v1/settlement/items?tradeId=$sold").then().statusCode(200).extract().path("[0].id")

        val lotId = s.receiveLot(1, 100)
        val issuance: String = s.asUser("backoffice").get("/api/v1/inventory/lots/$lotId").then().extract().path("issuanceRef")
        val csv = "issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end\n$issuance,1,60,${s.deviceGsrn},2026-03-01,2026-04-01\n"
        s.asUser("backoffice").contentType("text/plain").body(csv)
            .post("/api/v1/registry/statements/csv?registryAccountId=${s.gooAccountId}&statementDate=${LocalDate.now()}").then().statusCode(201)
        val breakId: String = s.asUser("backoffice").get("/api/v1/inventory/breaks?registryAccountId=${s.gooAccountId}").then().extract().path("[0].id")
        val instructionId: String = s.asUser("backoffice").body(
            """{"lotId":"$lotId","quantityMwh":10,"toRegistryCode":"SIMULATOR","toAccountRef":"SIM-OTHER","idempotencyKey":"guards-${s.entityId}"}""",
        ).post("/api/v1/registry/instructions/transfers").then().statusCode(201).extract().path("id")

        val captured = s.capturedHydroSale(50, "2.00")
        val task: String = s.asUser("middle").get("/api/v1/workflow/tasks?subjectRef=$captured").then().extract().path("items[0].id")
        s.asUser("middle").post("/api/v1/workflow/tasks/$task/approve").then().statusCode(200)
        val ours = s.asUser("middle").get("/api/v1/trades/$captured/confirmations").then().extract().jsonPath()
        val terms = JsonPath.from(ours.prettify()).getMap<String, Any?>("[0].terms").toMutableMap()
        val documentId = ours.getString("[0].documentId")

        @Suppress("UNCHECKED_CAST")
        val leg = (terms["legs"] as List<Map<String, Any?>>).single().toMutableMap()
        leg["price"] = 2.40
        leg["amount"] = 120.00
        terms["legs"] = listOf(leg)
        val confirmationId: String = s.asUser("middle").body(mapOf("terms" to terms, "reference" to "THEIRS-${s.entityId}", "source" to "MANUAL"))
            .post("/api/v1/confirmations/counterparty").then().statusCode(201).body("status", equalTo("MISMATCHED")).extract().path("id")
        return Operations(s, itemId, instructionId, breakId, confirmationId, documentId)
    }

    @Test
    fun `the front office cannot read or work operations data`() {
        val mine = operations()

        fun trader() = mine.s.asUser("a-trader", "TRADER")

        trader().get("/api/v1/settlement/items").then().statusCode(403)
        trader().get("/api/v1/settlement/items/${mine.itemId}").then().statusCode(403)
        trader().post("/api/v1/settlement/items/${mine.itemId}/reevaluate").then().statusCode(403)
        trader().get("/api/v1/settlement/invoices").then().statusCode(403)
        trader().get("/api/v1/settlement/payments?legalEntityId=${mine.s.entityId}").then().statusCode(403)
        trader().get("/api/v1/registry/instructions/search").then().statusCode(403)
        trader().get("/api/v1/registry/instructions/${mine.instructionId}").then().statusCode(403)
        trader().body("""{"externalRef":"X"}""").post("/api/v1/registry/instructions/${mine.instructionId}/confirm").then().statusCode(403)
        trader().get("/api/v1/inventory/statements?registryAccountId=${mine.s.gooAccountId}").then().statusCode(403)
        trader().get("/api/v1/inventory/breaks/${mine.breakId}").then().statusCode(403)
        trader().contentType("text/plain").body("issuance_ref\n")
            .post("/api/v1/registry/statements/csv?registryAccountId=${mine.s.gooAccountId}&statementDate=${LocalDate.now()}").then().statusCode(403)
        trader().contentType("text/plain").body("confirmation_ref\n")
            .post("/api/v1/confirmations/counterparty/csv?counterpartyId=${mine.s.counterpartyId}").then().statusCode(403)
        trader().body("""{"fromRegistryCode":"SIMULATOR","toRegistryCode":"SIMULATOR","feasible":true,"feePerMwh":0,"expectedDays":0}""")
            .put("/api/v1/registry/routes").then().statusCode(403)
        trader().body("""{"registryCode":"SIMULATOR","profileCode":"X","kind":"GOO_HOLDINGS","columnMapping":{}}""")
            .put("/api/v1/registry/statement-formats").then().statusCode(403)
    }

    @Test
    fun `a user scoped to one entity sees and works only that entity's operations data`() {
        val mine = operations()
        val theirs = operations()

        fun scoped() = mine.s.asUser("scoped-ops", "BACK_OFFICE,MIDDLE_OFFICE,REGISTRY_OPERATOR").header("X-Entities", mine.s.entityId)

        scoped().get("/api/v1/settlement/items").then().statusCode(200)
            .body("id", hasItem(mine.itemId)).body("id", not(hasItem(theirs.itemId)))
        scoped().get("/api/v1/settlement/items/${theirs.itemId}").then().statusCode(403)
        scoped().get("/api/v1/settlement/invoices").then().statusCode(200).body("legalEntityId", everyItem(equalTo(mine.s.entityId)))

        scoped().get("/api/v1/registry/instructions/search?limit=500").then().statusCode(200)
            .body("items.id", hasItem(mine.instructionId)).body("items.id", not(hasItem(theirs.instructionId)))
        scoped().get("/api/v1/registry/instructions/search?holderEntityId=${theirs.s.entityId}").then().statusCode(403)
        scoped().get("/api/v1/registry/instructions/${theirs.instructionId}").then().statusCode(403)
        scoped().body("""{"reason":"not ours"}""").post("/api/v1/registry/instructions/${theirs.instructionId}/cancel").then().statusCode(403)

        scoped().get("/api/v1/inventory/breaks").then().statusCode(200)
            .body("id", hasItem(mine.breakId)).body("id", not(hasItem(theirs.breakId)))
        scoped().get("/api/v1/inventory/breaks/${theirs.breakId}").then().statusCode(403)
        scoped().get("/api/v1/inventory/statements?registryAccountId=${mine.s.gooAccountId}").then().statusCode(200).body("size()", equalTo(1))
        scoped().get("/api/v1/inventory/statements?registryAccountId=${theirs.s.gooAccountId}").then().statusCode(403)
        scoped().contentType("text/plain").body("issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end\n")
            .post("/api/v1/registry/statements/csv?registryAccountId=${theirs.s.gooAccountId}&statementDate=${LocalDate.now()}").then().statusCode(403)

        scoped().get("/api/v1/confirmations").then().statusCode(200)
            .body("id", hasItem(mine.confirmationId)).body("id", not(hasItem(theirs.confirmationId)))
        scoped().get("/api/v1/confirmations?legalEntityId=${theirs.s.entityId}").then().statusCode(403)
        scoped().get("/api/v1/confirmations/${theirs.confirmationId}").then().statusCode(403)

        // Issued documents (our confirmation PDFs) and the break ageing figures follow the same scope.
        scoped().get("/api/v1/documents/${mine.documentId}/content").then().statusCode(200)
        scoped().get("/api/v1/documents/${theirs.documentId}/content").then().statusCode(403)
        scoped().get("/api/v1/documents/${theirs.documentId}").then().statusCode(403)
        scoped().get("/api/v1/inventory/breaks/ageing").then().statusCode(200).body("upToOneDay", equalTo(1))
        scoped().get("/api/v1/inventory/breaks/ageing?legalEntityId=${mine.s.entityId}").then().statusCode(200).body("upToOneDay", equalTo(1))
        scoped().get("/api/v1/inventory/breaks/ageing?legalEntityId=${theirs.s.entityId}").then().statusCode(403)
    }

    @Test
    fun `allowance movements and the lot journal check need an operations role`() {
        val s = ApiScenario(port).referenceData()
        s.asUser("trader", "TRADER").body(
            """{"holderEntityId":"${s.entityId}","registryAccountId":"${s.gooAccountId}","productCode":"EUA","type":"PURCHASE","quantity":5,
               "effectiveDate":"${LocalDate.now()}"}""",
        ).post("/api/v1/inventory/holdings/movements").then().statusCode(403)
        s.asUser("trader", "TRADER").get("/api/v1/inventory/journal/verify").then().statusCode(403)
        s.asUser("auditor", "AUDITOR").get("/api/v1/inventory/journal/verify").then().statusCode(200).body("ok", equalTo(true))
        s.asUser("backoffice").get("/api/v1/inventory/holdings/${java.util.UUID.randomUUID()}/movements").then().statusCode(404)
    }

    @Test
    fun `manual obligations are kept by middle and back office on their own entity, and position snapshots by operations`() {
        val s = ApiScenario(port).referenceData()
        val body = """{"kind":"OWN_CONSUMPTION","holderEntityId":"${s.entityId}","criterion":{"type":"matchAll"},"quantityMwh":10,
            "deliveryWindow":{"start":"${LocalDate.now()}","endExclusive":"${LocalDate.now().plusDays(30)}"},"deliveryDate":"${LocalDate.now().plusDays(20)}",
            "sourceRef":"${java.util.UUID.randomUUID()}","cancellation":{"purpose":"OWN_CONSUMPTION","beneficiaryName":"Offices",
            "consumptionCountry":"FI","consumptionPeriod":{"start":"2026-01-01","endExclusive":"2026-02-01"}}}"""
        s.asUser("trader", "TRADER").body(body).post("/api/v1/allocation/obligations").then().statusCode(403)
        s.asUser("scoped", "BACK_OFFICE").header("X-Entities", java.util.UUID.randomUUID().toString()).body(body)
            .post("/api/v1/allocation/obligations").then().statusCode(403)
        val id: String = s.asUser("bo", "BACK_OFFICE").body(body).post("/api/v1/allocation/obligations").then().statusCode(201).extract().path("id")
        s.asUser("trader", "TRADER").body("[]").put("/api/v1/allocation/obligations/$id/constraints").then().statusCode(403)
        s.asUser("bo", "BACK_OFFICE").body("""{"reason":"no longer needed"}""").post("/api/v1/allocation/obligations/$id/close").then().statusCode(200)
        s.asUser("bo", "BACK_OFFICE").body("[]").put("/api/v1/allocation/obligations/$id/constraints").then().statusCode(409)
        s.asUser("trader", "TRADER").post("/api/v1/positions/snapshots?legalEntityId=${s.entityId}").then().statusCode(403)
    }

    @Test
    fun `the trader withdraws a trade from approval, which cancels the trade`() {
        val s = ApiScenario(port).referenceData()
        val tradeId = s.capturedHydroSale(30)
        val taskId: String = s.asUser("trader-${s.tag}", "TRADER").get("/api/v1/workflow/tasks?subjectRef=$tradeId").then().extract().path("items[0].id")
        s.asUser("trader-${s.tag}", "TRADER").body("""{"reason":"booked twice"}""").post("/api/v1/workflow/tasks/$taskId/cancel").then().statusCode(200)
            .body("status", equalTo("CANCELLED"))
        s.asUser("backoffice").get("/api/v1/trades/$tradeId").then().body("status", equalTo("CANCELLED"))
    }

    @Test
    fun `back office sets the default book of an own registry account`() {
        val s = ApiScenario(port).referenceData()
        s.asUser("bo", "BACK_OFFICE").body("""{"bookId":"${s.bookId}"}""").put("/api/v1/reference/registry-accounts/${s.gooAccountId}/default-book")
            .then().statusCode(200).body("defaultBookId", equalTo(s.bookId))
        s.asUser("trader", "TRADER").body("""{"bookId":null}""").put("/api/v1/reference/registry-accounts/${s.gooAccountId}/default-book")
            .then().statusCode(403)
    }
}
