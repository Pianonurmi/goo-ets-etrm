package com.pianonurmi.etrm

import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification
import org.hamcrest.Matchers.equalTo
import java.util.concurrent.atomic.AtomicInteger

/**
 * REST helpers that set up an isolated scenario (own legal entity, codes and device) so end-to-end tests can share
 * the test database without interfering. Users are identified with the dev/test `X-Actor` / `X-Roles` headers.
 */
class ApiScenario(private val port: Int, private val tag: String = "S${COUNTER.incrementAndGet()}${System.nanoTime() % 100_000}") {
    lateinit var entityId: String
    lateinit var bookId: String
    lateinit var counterpartyId: String
    lateinit var gooAccountId: String
    lateinit var deviceGsrn: String

    fun asUser(user: String, roles: String = ALL_ROLES): RequestSpecification =
        RestAssured.given().baseUri("http://localhost").port(port).contentType(ContentType.JSON).header("X-Actor", user).header("X-Roles", roles)

    /** Legal entity, trading book, external counterparty, a simulator GoO account and an own hydro device. */
    fun referenceData(): ApiScenario {
        entityId = asUser("admin").body("""{"code":"E-$tag","name":"Entity $tag Oy","country":"FI","validFrom":"2026-01-01"}""")
            .post("/api/v1/reference/legal-entities").then().statusCode(201).extract().path("id")
        bookId = asUser("admin").body("""{"legalEntityId":"$entityId","code":"B-$tag","name":"Book $tag","bookType":"TRADING"}""")
            .post("/api/v1/reference/books").then().statusCode(201).extract().path("id")
        counterpartyId = asUser("admin").body("""{"code":"C-$tag","name":"Counterparty $tag GmbH","country":"DE"}""")
            .post("/api/v1/reference/counterparties").then().statusCode(201).extract().path("id")
        gooAccountId = asUser("admin").body(
            """{"registryCode":"SIMULATOR","accountNumber":"SIM-$tag","name":"GoO $tag","accountType":"HOLDING",
               "holderType":"LEGAL_ENTITY","holderId":"$entityId"}""",
        ).post("/api/v1/reference/registry-accounts").then().statusCode(201).extract().path("id")
        deviceGsrn = gsrn(DEVICE_COUNTER.incrementAndGet())
        asUser("admin").body(
            """{"gsrn":"$deviceGsrn","name":"Hydro $tag","country":"FI","capacityKw":25000,"commissioningDate":"2012-05-01",
               "energySourceCode":"HYDRO_RESERVOIR","technologyCode":"HYDRO_TURBINE_STORAGE","registryCode":"SIMULATOR",
               "ownerType":"LEGAL_ENTITY","ownerId":"$entityId","ownDevice":true}""",
        ).post("/api/v1/reference/production-devices").then().statusCode(201)
        return this
    }

    /** Receives a lot of Nordic hydro certificates produced in March 2026; returns the lot id. */
    fun receiveLot(start: Long, end: Long, costBasis: String = "1.20"): String =
        asUser("backoffice").body(
            """{"holderEntityId":"$entityId","registryAccountId":"$gooAccountId","issuanceRef":"ISS-$tag","range":{"start":$start,"end":$end},
               "deviceGsrn":"$deviceGsrn","productionPeriod":{"start":"2026-03-01","endExclusive":"2026-04-01"},"issueDate":"2026-04-12",
               "issuingBody":"SIM","issuingCountry":"FI","supportProduction":false,"supportInvestment":false,"acquisitionSource":"ISSUANCE",
               "costBasisPerMwh":$costBasis,"costCurrency":"EUR"}""",
        ).post("/api/v1/inventory/lots").then().statusCode(201).extract().path("id")

    /** Captures a Nordic hydro sale, approves it through the workflow inbox as a second user and confirms it; returns the trade id. */
    fun confirmedHydroSale(quantity: Long, price: String = "1.95"): String {
        val tradeId = capturedHydroSale(quantity, price)
        val task: String = asUser("middle-$tag").get("/api/v1/workflow/tasks?legalEntityId=$entityId").then().statusCode(200)
            .extract().path("items.find { it.subjectRef == '$tradeId' }.id")
        asUser("middle-$tag").post("/api/v1/workflow/tasks/$task/approve").then().statusCode(200)
        asUser("backoffice").body("""{"confirmationRef":"CONF-$tag"}""").post("/api/v1/trades/$tradeId/confirm").then().statusCode(200)
            .body("status", equalTo("CONFIRMED"))
        return tradeId
    }

    /** Captures a Nordic hydro sale (pending approval); [capturedBy] may capture on behalf of [trader]. Returns the trade id. */
    fun capturedHydroSale(quantity: Long, price: String = "1.95", trader: String = "trader-$tag", capturedBy: String = trader): String {
        val tradeId: String = asUser(capturedBy).body(
            """{"legalEntityId":"$entityId","bookId":"$bookId","counterpartyId":"$counterpartyId","productFamily":"GUARANTEE_OF_ORIGIN","trader":"$trader",
               "instrumentType":"FORWARD","tradeDate":"2026-05-04","legs":[{"side":"SELL","criterion":{"type":"all","of":[
                 {"type":"countryInRegion","region":"NORDIC"},{"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"HYDRO"}]},
               "quantity":$quantity,"unit":"MWH","price":$price,"priceCurrency":"EUR","deliveryAccountId":"$gooAccountId",
               "counterpartyAccountRef":"SIMULATOR:SIM-EXT-$tag",
               "deliveries":[{"deliveryWindow":{"start":"2026-10-01","endExclusive":"2027-01-01"},"deliveryDate":"2026-12-15","quantity":$quantity}]}]}""",
        ).post("/api/v1/trades").then().statusCode(201).extract().path("id")
        return tradeId
    }

    /** Proposes an allocation plan for all open obligations of the entity; returns the plan id. */
    fun proposePlan(key: String = "plan-$tag"): String =
        asUser("trader-$tag").body("""{"holderEntityId":"$entityId","idempotencyKey":"$key"}""")
            .post("/api/v1/allocation/plans").then().statusCode(201).body("status", equalTo("PROPOSED")).extract().path("id")

    companion object {
        const val ALL_ROLES = "TRADER,MIDDLE_OFFICE,BACK_OFFICE,ACCOUNTANT,REGISTRY_OPERATOR"
        private val COUNTER = AtomicInteger()
        private val DEVICE_COUNTER = AtomicInteger(500)

        /** A valid 18-digit GS1 GSRN derived from a sequence number (mod-10 check digit). */
        fun gsrn(sequence: Int): String {
            val body = "64390" + sequence.toString().padStart(12, '0')
            val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
            return body + ((10 - sum % 10) % 10)
        }
    }
}
