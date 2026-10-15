package com.pianonurmi.etrm

import com.pianonurmi.etrm.allocation.api.GooBasis
import com.pianonurmi.etrm.allocation.api.GooConcentration
import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.allocation.api.SaleProjection
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.trade.api.TradeView
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Risk reads allocation's GoO measures while measuring a limit and while checking a trade before approval, inside the
 * transaction of the approval or the capture. A measure that fails in a transactional service marks that transaction
 * rollback-only unless the call runs in a savepoint (review finding D5), so the limit approval or the trade capture
 * would fail at commit. Here every GoO measure fails inside a transaction of its own; both still commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RiskSavepointIT.FailingGooConfiguration::class)
class RiskSavepointIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val RISK = "RISK"
    }

    @TestConfiguration
    class FailingGooConfiguration {
        @Bean
        @Primary
        fun failingGooRisk(): GooRiskQuery = FailingGooRisk()
    }

    /** GoO measures that fail inside a transaction, as allocation's would when a lookup in another module fails. */
    @Transactional
    class FailingGooRisk : GooRiskQuery {
        override fun concentration(holderEntityId: UUID, bucketCode: String, productionYear: Int?): GooConcentration? = fail()

        override fun unusedExpiringMwh(holderEntityId: UUID, horizonDays: Int, bucketCode: String?, productionYear: Int?): Long = fail()

        override fun basis(holderEntityId: UUID, productionYear: Int?): GooBasis? = fail()

        override fun vintages(trade: TradeView): Map<UUID, Int> = fail()

        override fun projectSale(trade: TradeView): SaleProjection? = fail()

        private fun fail(): Nothing = throw ValidationException("GoO figures are not available in this test")
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a GoO limit is approved and a GoO sale captured although every GoO measure fails`() {
        val s = ApiScenario(port).referenceData()
        val limitId: String = s.asUser("risk-a", RISK).body(
            """{"legalEntityId":"${s.entityId}","type":"GOO_BASIS","scope":{},"amount":1000,"currency":"EUR","reason":"give-away"}""",
        ).post("/api/v1/risk/limits").then().statusCode(201).extract().path("id")
        val taskId: String = s.asUser("risk-b", RISK).get("/api/v1/workflow/tasks?subjectRef=$limitId&type=LIMIT_CHANGE").then().statusCode(200)
            .extract().path("items[0].id")

        // Approval measures the new limit at once: the measure fails, the limit is not measured, and the approval commits.
        s.asUser("risk-b", RISK).post("/api/v1/workflow/tasks/$taskId/approve").then().statusCode(200)
        s.asUser("risk-a", RISK).get("/api/v1/risk/limits/$limitId").then().statusCode(200).body("status", equalTo("ACTIVE"))
        s.asUser("risk-a", RISK).get("/api/v1/risk/limits/$limitId/utilisation").then().statusCode(200)
            .body("status", equalTo("NOT_MEASURED"))

        // The pre-trade check projects the sale's give-away: the projection fails, and the capture still commits.
        val tradeId = s.capturedHydroSale(1000)
        s.asUser("trader").get("/api/v1/trades/$tradeId").then().statusCode(200)
            .body("status", equalTo("PENDING_APPROVAL"))
    }
}
