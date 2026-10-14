package com.pianonurmi.etrm

import com.pianonurmi.etrm.inventory.api.LotReleased
import com.pianonurmi.etrm.platform.errors.ValidationException
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Cancelling a plan must cope with a registry instruction that cannot be cancelled and a lot that cannot be released:
 * each failure is logged, the plan is cancelled and the command commits. Runs with real transaction proxies; a
 * `DomainException` from the other service must not leave the cancel transaction rollback-only (D5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AllocationCancelSavepointIT.ReleaseVeto::class)
class AllocationCancelSavepointIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    /** A listener that refuses the release of the lots reserved for the registered plans. */
    @TestConfiguration
    class ReleaseVeto {
        val plans: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

        @EventListener
        fun on(event: LotReleased) {
            if (event.reservationRef in plans) throw ValidationException("release vetoed for the test")
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var veto: ReleaseVeto

    /** Approves a plan (the simulator confirms at once) and puts it back to INSTRUCTED so that it can be cancelled. */
    private fun instructedPlan(s: ApiScenario): String {
        s.receiveLot(1, 1000)
        s.confirmedHydroSale(600)
        val planId = s.proposePlan()
        s.asUser("approver").post("/api/v1/allocation/plans/$planId/approve").then().statusCode(200).body("status", equalTo("CONFIRMED"))
        jdbc.update("UPDATE etrm_allocation.allocation_plan SET status = 'INSTRUCTED' WHERE id = ?::uuid", planId)
        jdbc.update("UPDATE etrm_allocation.allocation_line SET status = 'INSTRUCTED' WHERE plan_id = ?::uuid", planId)
        return planId
    }

    @Test
    fun `cancelling a plan whose instruction cannot be cancelled still commits`() {
        val s = ApiScenario(port).referenceData()
        val planId = instructedPlan(s)

        // The instruction is already CONFIRMED, so the registry module refuses to cancel it.
        s.asUser("approver").body("""{"reason":"no longer needed"}""").post("/api/v1/allocation/plans/$planId/cancel")
            .then().statusCode(200).body("status", equalTo("CANCELLED"))
        s.asUser("approver").get("/api/v1/allocation/plans/$planId").then().statusCode(200).body("status", equalTo("CANCELLED"))
    }

    @Test
    fun `cancelling a plan whose lot cannot be released still commits`() {
        val s = ApiScenario(port).referenceData()
        val planId = instructedPlan(s)
        jdbc.update("UPDATE etrm_allocation.allocation_line SET instruction_id = NULL WHERE plan_id = ?::uuid", planId)
        jdbc.update(
            """
            UPDATE etrm_inventory.certificate_lot SET status = 'RESERVED', reservation_ref = ?::uuid
             WHERE id IN (SELECT reserved_lot_id FROM etrm_allocation.allocation_line WHERE plan_id = ?::uuid)
            """.trimIndent(),
            planId,
            planId,
        )
        veto.plans += UUID.fromString(planId)

        s.asUser("approver").body("""{"reason":"no longer needed"}""").post("/api/v1/allocation/plans/$planId/cancel")
            .then().statusCode(200).body("status", equalTo("CANCELLED"))
        s.asUser("approver").get("/api/v1/allocation/plans/$planId").then().statusCode(200).body("status", equalTo("CANCELLED"))
    }
}
