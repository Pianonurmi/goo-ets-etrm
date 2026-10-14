package com.pianonurmi.etrm

import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotExpired
import com.pianonurmi.etrm.platform.errors.ValidationException
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The nightly expiry sweep takes each lot in its own transaction (D6): a lot that a listener refuses to expire stays
 * as it was, is counted and gets a work task, and the other due lots expire. Runs with real transaction proxies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(LotExpiryIT.ExpiryVeto::class)
class LotExpiryIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        /** The scenario's lots expire on this date, long before any other test's lots. */
        private val EXPIRY: LocalDate = LocalDate.of(2025, 3, 1)
    }

    /** Stands in for the ledger listener: refuses the expiry of the lots registered here. */
    @TestConfiguration
    class ExpiryVeto {
        val lots: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

        @EventListener
        fun on(event: LotExpired) {
            if (event.lot.id in lots) throw ValidationException("no FX rate for the posting")
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var lotCommand: LotCommand

    @Autowired
    private lateinit var veto: ExpiryVeto

    @Autowired
    private lateinit var meters: MeterRegistry

    private fun receiveDueLot(s: ApiScenario, start: Long, end: Long): String = s.asUser("backoffice").body(
        """{"holderEntityId":"${s.entityId}","registryAccountId":"${s.gooAccountId}","issuanceRef":"ISS-${s.tag}-$start","range":{"start":$start,"end":$end},
           "deviceGsrn":"${s.deviceGsrn}","productionPeriod":{"start":"2024-01-01","endExclusive":"2024-02-01"},"issueDate":"2024-02-10",
           "issuingBody":"SIM","issuingCountry":"FI","supportProduction":false,"supportInvestment":false,"acquisitionSource":"ISSUANCE",
           "expiryDate":"$EXPIRY","costBasisPerMwh":1.20,"costCurrency":"EUR"}""",
    ).post("/api/v1/inventory/lots").then().statusCode(201).extract().path("id")

    private fun openTasks(s: ApiScenario, lotId: String): Int = s.asUser("backoffice")
        .get("/api/v1/workflow/tasks?subjectRef=$lotId&type=LOT_EXPIRY_FAILED").then().statusCode(200).extract().path("items.size()")

    @Test
    fun `one lot that cannot be expired leaves the others expired and gets a task`() {
        val s = ApiScenario(port).referenceData()
        val first = receiveDueLot(s, 1, 10)
        val stuck = receiveDueLot(s, 11, 20)
        val last = receiveDueLot(s, 21, 30)
        veto.lots += UUID.fromString(stuck)
        val failures = meters.counter("etrm.inventory.expiry.failures")
        val before = failures.count()

        lotCommand.expireDueLots(EXPIRY) shouldBe 2

        for ((id, status) in listOf(first to "EXPIRED", stuck to "HELD", last to "EXPIRED")) {
            s.asUser("backoffice").get("/api/v1/inventory/lots/$id").then().statusCode(200).body("status", equalTo(status))
        }
        failures.count() - before shouldBe 1.0
        openTasks(s, stuck) shouldBe 1
        openTasks(s, first) shouldBe 0

        // The cause is fixed: the next sweep expires the lot and closes the task.
        veto.lots.clear()
        lotCommand.expireDueLots(EXPIRY) shouldBe 1
        s.asUser("backoffice").get("/api/v1/inventory/lots/$stuck").then().statusCode(200).body("status", equalTo("EXPIRED"))
        openTasks(s, stuck) shouldBe 0
        s.asUser("backoffice").get("/api/v1/workflow/tasks?subjectRef=$stuck&type=LOT_EXPIRY_FAILED&status=COMPLETED").then().statusCode(200)
            .body("items.size()", equalTo(1))
    }
}
