package com.pianonurmi.etrm

import io.kotest.matchers.shouldBe
import io.restassured.path.json.JsonPath
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasItems
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal

/**
 * Increment 3 end to end through REST, for one entity and September 2026:
 * 1. market data and official curves (four eyes);
 * 2. an EUA forward in a trading book and a GoO forward in an own-use book;
 * 3. two end-of-day valuations signed off and a P&L explain that adds up;
 * 4. EMIR events and an export;
 * 5. the period end (MtM posted for trading and reversed, own use disclosed, NRV on certificates);
 * 6. soft close, GL export, acknowledgement, reconciliation and hard close.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MonthEndIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val LEI = "529900ETRME2E0000104"
        private const val ANALYST = "RISK,MIDDLE_OFFICE"
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var s: ApiScenario

    private fun json(response: io.restassured.response.Response): JsonPath = response.then().extract().jsonPath()

    /** Approves the open task of [type] for [subject] as a second person. */
    private fun approve(type: String, subject: String, by: String = "checker", roles: String = ANALYST) {
        val task: String = s.asUser(by, roles).get("/api/v1/workflow/tasks?subjectRef=$subject&type=$type").then().statusCode(200)
            .body("items.size()", equalTo(1)).extract().path("items[0].id")
        s.asUser(by, roles).post("/api/v1/workflow/tasks/$task/approve").then().statusCode(200)
    }

    private fun officialCurve(code: String, date: String) {
        val snapshot: String = s.asUser(
            "analyst",
            ANALYST,
        ).body("""{"asOf":"$date"}""").post("/api/v1/market-data/curves/$code/snapshots").then().statusCode(201)
            .extract().path("id")
        s.asUser("analyst", ANALYST).post("/api/v1/market-data/curves/snapshots/$snapshot/submit").then().statusCode(200)
        approve("CURVE_SIGN_OFF", snapshot)
    }

    private fun quote(instrument: String, date: String, type: String, value: String, currency: String) =
        s.asUser("analyst", ANALYST).body("""{"instrumentCode":"$instrument","quoteDate":"$date","quoteType":"$type","value":$value,"currency":"$currency"}""")
            .put("/api/v1/market-data/quotes").then().statusCode(200)

    private fun officialValuation(date: String): String {
        val run: String = s.asUser("analyst", ANALYST).body("""{"legalEntityId":"${s.entityId}","valuationDate":"$date","kind":"EOD"}""")
            .post("/api/v1/valuation/runs").then().statusCode(201).extract().path("id")
        s.asUser("analyst", ANALYST).post("/api/v1/valuation/runs/$run/submit").then().statusCode(200)
        approve("VALUATION_SIGN_OFF", run, roles = "RISK")
        return run
    }

    private fun approvedAndConfirmed(tradeId: String) {
        approve("TRADE_APPROVAL", tradeId, by = "middle")
        s.asUser("backoffice").body("""{"confirmationRef":"CONF-$tradeId"}""").post("/api/v1/trades/$tradeId/confirm").then().statusCode(200)
    }

    private fun balance(period: String, account: String): BigDecimal =
        json(s.asUser("accountant").get("/api/v1/ledger/trial-balance?legalEntityId=${s.entityId}&period=$period"))
            .getList<Map<String, Any>>("").firstOrNull {
                it["accountCode"] == account
            }?.let { BigDecimal(it["debit"].toString()) - BigDecimal(it["credit"].toString()) }
            ?: BigDecimal.ZERO

    @Test
    @Suppress("LongMethod") // one month of one entity, step by step
    fun `a month from market data to hard close`() {
        s = ApiScenario(port).referenceData()
        val tag = s.entityId.takeLast(6).uppercase()
        s.asUser("admin").body("""{"lei":"$LEI","emirClassification":"NFC_PLUS","expectedVersion":1}""")
            .patch("/api/v1/reference/legal-entities/${s.entityId}").then().statusCode(200)
        val ownUseBook: String = s.asUser("admin").body("""{"legalEntityId":"${s.entityId}","code":"OWN-$tag","name":"Own use","bookType":"OWN_USE"}""")
            .post("/api/v1/reference/books").then().statusCode(201).extract().path("id")

        // 1. Market data: SEK rates, EUA spot closes, GoO vintage mids in SEK; curves signed off by a second person.
        listOf("2026-09-29", "2026-09-30").forEach { d ->
            s.asUser(
                "analyst",
                ANALYST,
            ).body("""{"date":"$d","base":"EUR","quote":"SEK","rate":10}""").put("/api/v1/market-data/fx-rates").then().statusCode(200)
        }
        s.asUser("analyst", ANALYST).body("""{"code":"EUA_SPOT_$tag","kind":"EUA_SPOT","description":"EUA spot","allowanceProductCode":"EUA"}""")
            .post("/api/v1/market-data/instruments").then().statusCode(201)
        listOf("NORDIC_HYDRO", "FI_HYDRO").forEach { bucket ->
            s.asUser("analyst", ANALYST).body(
                """{"code":"GOO_${bucket}_2026_$tag","kind":"GOO_VINTAGE","description":"$bucket 2026","bucketCode":"$bucket",
                   "productionYear":2026,"currency":"SEK"}""",
            ).post("/api/v1/market-data/instruments").then().statusCode(201)
            s.asUser(
                "analyst",
                ANALYST,
            ).body("""{"code":"GOO_${bucket}_$tag","kind":"GOO_VINTAGE","description":"$bucket vintages","bucketCode":"$bucket","currency":"SEK"}""")
                .post("/api/v1/market-data/curves").then().statusCode(201)
        }
        s.asUser("analyst", ANALYST).body("""{"code":"EUA_FWD_$tag","kind":"EUA_FORWARD","description":"EUA forward","allowanceProductCode":"EUA"}""")
            .post("/api/v1/market-data/curves").then().statusCode(201)
        mapOf("2026-09-29" to listOf("70.00", "3.00"), "2026-09-30" to listOf("72.00", "3.10")).forEach { (d, prices) ->
            quote("EUA_SPOT_$tag", d, "CLOSE", prices[0], "EUR")
            quote("GOO_NORDIC_HYDRO_2026_$tag", d, "MID", prices[1], "SEK")
            quote("GOO_FI_HYDRO_2026_$tag", d, "MID", "3.00", "SEK")
            listOf("EUA_FWD_$tag", "GOO_NORDIC_HYDRO_$tag", "GOO_FI_HYDRO_$tag").forEach { officialCurve(it, d) }
        }

        // 2. Certificates in stock, an EUA forward purchase (trading) and a GoO forward sale (own use).
        s.asUser("backoffice").body(
            """{"holderEntityId":"${s.entityId}","registryAccountId":"${s.gooAccountId}","issuanceRef":"ISS-ME-$tag","range":{"start":1,"end":500},
               "deviceGsrn":"${s.deviceGsrn}","productionPeriod":{"start":"2026-08-01","endExclusive":"2026-09-01"},"issueDate":"2026-09-05",
               "issuingBody":"SIM","issuingCountry":"FI","supportProduction":false,"supportInvestment":false,"acquisitionSource":"ISSUANCE",
               "costBasisPerMwh":1.20,"costCurrency":"EUR"}""",
        ).post("/api/v1/inventory/lots").then().statusCode(201)
        val eua: String = s.asUser("trader").body(
            """{"legalEntityId":"${s.entityId}","bookId":"${s.bookId}","counterpartyId":"${s.counterpartyId}","productFamily":"EMISSION_ALLOWANCE",
               "instrumentType":"FORWARD","tradeDate":"2026-09-01","regulatory":{"executionTimestamp":"2026-09-01T09:30:00Z","venueMic":"XOFF"},
               "legs":[{"side":"BUY","allowanceProductCode":"EUA","quantity":1000,"unit":"TCO2E","price":68,"priceCurrency":"EUR",
               "deliveries":[{"deliveryWindow":{"start":"2026-12-14","endExclusive":"2026-12-15"},"deliveryDate":"2026-12-14","quantity":1000}]}]}""",
        ).post("/api/v1/trades").then().statusCode(201).extract().path("id")
        val goo: String = s.asUser("trader").body(
            """{"legalEntityId":"${s.entityId}","bookId":"$ownUseBook","counterpartyId":"${s.counterpartyId}","productFamily":"GUARANTEE_OF_ORIGIN",
               "instrumentType":"FORWARD","tradeDate":"2026-09-01","legs":[{"side":"SELL","criterion":{"type":"all","of":[
                 {"type":"countryInRegion","region":"NORDIC"},{"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"HYDRO"}]},
               "quantity":1000,"unit":"MWH","price":1.95,"priceCurrency":"EUR","deliveryAccountId":"${s.gooAccountId}",
               "counterpartyAccountRef":"SIMULATOR:SIM-EXT-$tag",
               "deliveries":[{"deliveryWindow":{"start":"2026-10-01","endExclusive":"2027-01-01"},"deliveryDate":"2026-12-15","quantity":1000}]}]}""",
        ).post("/api/v1/trades").then().statusCode(201).extract().path("id")
        approvedAndConfirmed(eua)
        approvedAndConfirmed(goo)
        s.asUser("trader").get("/api/v1/trades/$eua").then().body("regulatory.uti", org.hamcrest.Matchers.startsWith(LEI))

        // 3. Two end-of-day valuations: EUA 1000 x (70 - 68) then (72 - 68); GoO -1000 x (0.30 - 1.95) then (0.31 - 1.95).
        officialValuation("2026-09-29")
        val september30 = officialValuation("2026-09-30")
        s.asUser("analyst", ANALYST).get("/api/v1/valuation/runs/$september30").then().statusCode(200)
            .body("totalMtmBase", equalTo(5640.0f)).body("exceptions", equalTo(0))
        val explain = json(s.asUser("analyst", ANALYST).get("/api/v1/valuation/pnl-explain?legalEntityId=${s.entityId}&from=2026-09-29&to=2026-09-30"))
        explain.getMap<String, Any>("components").values.sumOf { BigDecimal(it.toString()) }.compareTo(BigDecimal("1990")) shouldBe 0
        BigDecimal(explain.getString("mtmTo")).subtract(BigDecimal(explain.getString("mtmFrom"))).compareTo(BigDecimal("1990")) shouldBe 0
        // Portfolio value (ADR 0039), next to the run and never in it: the GoO sale's MtM of -1000 x (0.31 - 1.95) is in
        // the bridge, the official total above is unchanged, and the parts add up.
        val bridge = json(s.asUser("analyst", ANALYST).get("/api/v1/valuation/runs/$september30/portfolio"))
        BigDecimal(bridge.getString("tradeMtm")).compareTo(BigDecimal("1640")) shouldBe 0
        val parts = BigDecimal(bridge.getString("inventoryValue")) + BigDecimal(bridge.getString("tradeMtm")) -
            BigDecimal(bridge.getString("giveAway")) - BigDecimal(bridge.getString("transferFees")) - BigDecimal(bridge.getString("expiryHaircut"))
        parts.compareTo(BigDecimal(bridge.getString("portfolioValue"))) shouldBe 0
        val portfolioExplain = json(
            s.asUser("analyst", ANALYST).get("/api/v1/valuation/portfolio-explain?legalEntityId=${s.entityId}&from=2026-09-29&to=2026-09-30"),
        )
        portfolioExplain.getList<Any>("steps").size shouldBe 1

        // 4. EMIR: the forward's confirmation and its valuations; the GoO trade is not reportable.
        s.asUser("middle", "MIDDLE_OFFICE").get("/api/v1/reports/emir/events?legalEntityId=${s.entityId}&from=2026-01-01&to=2030-12-31").then().statusCode(200)
            .body("action", hasItems("NEWT", "VALU")).body("findAll { it.tradeId == '$goo' }.size()", equalTo(0))
        s.asUser("middle", "MIDDLE_OFFICE").get("/api/v1/reports/EMIR_TRADE_ACTIVITY?legalEntityId=${s.entityId}&from=2026-01-01&to=2030-12-31&format=csv")
            .then().statusCode(200).header("X-Report-Seq", "1")

        // 5. Period end: trading MtM posted and reversed on 1 October, own use disclosed, certificates written down.
        val run =
            json(
                s.asUser(
                    "accountant",
                ).body(
                    """{"legalEntityId":"${s.entityId}","period":"2026-09"}""",
                ).post("/api/v1/ledger/period-end/runs").then().statusCode(201).extract().response(),
            )
        val components = run.getList<Map<String, Any>>("components").associateBy { "${it["kind"]}:${it["key"]}" }
        BigDecimal(components.getValue("MTM:TRADING:ASSET")["posted"].toString()).compareTo(BigDecimal("4000")) shouldBe 0
        components.getValue("MTM:OWN_USE:ASSET")["disclosedOnly"] shouldBe true
        BigDecimal(components.getValue("MTM:OWN_USE:ASSET")["required"].toString()).compareTo(BigDecimal("1640")) shouldBe 0
        // 500 MWh at 1.20 against 0.30 less 0.02 cost to sell.
        BigDecimal(components.getValue("NRV:GOO")["required"].toString()).compareTo(BigDecimal("460")) shouldBe 0
        balance("2026-09", "1800").compareTo(BigDecimal("4000")) shouldBe 0
        balance("2026-10", "1800").compareTo(BigDecimal("-4000")) shouldBe 0
        balance("2026-09", "1490").compareTo(BigDecimal("-460")) shouldBe 0

        // 6. Soft close, export, acknowledge, reconcile with the GL, hard close.
        s.asUser("accountant").post("/api/v1/ledger/periods/2026-09/close?legalEntityId=${s.entityId}&hard=false").then().statusCode(200)
        s.asUser("accountant").get("/api/v1/ledger/periods/2026-09/hard-close-blockers?legalEntityId=${s.entityId}").then().statusCode(200)
            .body("size()", greaterThan(0))
        val export: String = s.asUser("accountant").post("/api/v1/ledger/exports/2026-09?legalEntityId=${s.entityId}&format=SAP_FLAT").then().statusCode(201)
            .body("kind", equalTo("PERIOD")).extract().path("id")
        s.asUser("accountant").body("""{"reference":"FI-2026-09-0001"}""").post("/api/v1/ledger/exports/$export/acknowledge").then().statusCode(200)
            .body("status", equalTo("ACKNOWLEDGED"))
        val rows = json(s.asUser("accountant").get("/api/v1/ledger/reconciliation?legalEntityId=${s.entityId}&period=2026-09")).getList<Map<String, Any>>("")
        val glBalances = rows.joinToString(",", "[", "]") { """{"glAccount":"${it["glAccount"]}","balance":${it["exported"]},"currency":"EUR"}""" }
        s.asUser("accountant").body(glBalances).put("/api/v1/ledger/gl-balances/2026-09?legalEntityId=${s.entityId}").then().statusCode(200)
            .body("findAll { !it.reconciled }.size()", equalTo(0))
        s.asUser("accountant").get("/api/v1/ledger/periods/2026-09/hard-close-blockers?legalEntityId=${s.entityId}").then().body("size()", equalTo(0))
        s.asUser("accountant").get("/api/v1/reports/GL_RECONCILIATION?legalEntityId=${s.entityId}&period=2026-09").then().statusCode(200)
            .body("rows.findAll { !it.reconciled }.size()", equalTo(0)).body("rows.size()", greaterThan(0))
        s.asUser("accountant").post("/api/v1/ledger/periods/2026-09/close?legalEntityId=${s.entityId}&hard=true").then().statusCode(200)
            .body("status", equalTo("CLOSED"))
        s.asUser("auditor", "AUDITOR").get("/api/v1/ledger/journal/verify?legalEntityId=${s.entityId}").then().body("ok", equalTo(true))
    }
}
