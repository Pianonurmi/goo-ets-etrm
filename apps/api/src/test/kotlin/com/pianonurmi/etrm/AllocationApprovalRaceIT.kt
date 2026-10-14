package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Two approvers approve the same PROPOSED plan at once. Proves the plan's row lock (`AllocationPlanRepository.lock`,
 * `SELECT ... FOR UPDATE`) and the state check behind it: exactly one approval commits (200), the other is refused with
 * 409 once it sees the decided plan, and the approval's effects (registry instruction, lot reservation) happen once.
 * The first test holds the plan row from outside until both requests are blocked on it, so it shows that the lock is
 * what makes them queue; the second releases both requests together.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AllocationApprovalRaceIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val TIMEOUT_SECONDS = 30L
    }

    @LocalServerPort
    private var port: Int = 0

    private val pool = Executors.newFixedThreadPool(2)

    @AfterEach
    fun stop() {
        pool.shutdownNow()
    }

    private class Outcome(val approver: String, val status: Int, val planStatus: String?)

    private fun approve(scenario: ApiScenario, planId: String, approver: String, start: CountDownLatch): Future<Outcome> = pool.submit<Outcome> {
        start.await()
        val response = scenario.asUser(approver).post("/api/v1/allocation/plans/$planId/approve")
        Outcome(approver, response.statusCode, if (response.statusCode == 200) response.path<String>("status") else null)
    }

    private fun prepare(): Pair<ApiScenario, String> {
        val scenario = ApiScenario(port).referenceData()
        scenario.receiveLot(1, 1000)
        scenario.confirmedHydroSale(600)
        return scenario to scenario.proposePlan()
    }

    @Test
    fun `two approvers queue on the plan row lock and only the first decides`() {
        val (scenario, planId) = prepare()
        val holder = TestDatabase.dataSource.connection
        val outcomes = try {
            holder.autoCommit = false
            holder.prepareStatement("SELECT id FROM etrm_allocation.allocation_plan WHERE id = ? FOR UPDATE").use {
                it.setObject(1, UUID.fromString(planId))
                it.executeQuery().use { rows -> rows.next() shouldBe true }
            }
            val start = CountDownLatch(1)
            val results = listOf("approver-1", "approver-2").map { approve(scenario, planId, it, start) }
            start.countDown()
            awaitBackendsBlockedOnPlans(2)
            results.forEach { it.isDone shouldBe false }
            holder.commit()
            results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            holder.rollback()
            holder.close()
        }
        assertOneWinner(scenario, planId, outcomes)
    }

    @Test
    fun `two approvers released together decide the plan once`() {
        val (scenario, planId) = prepare()
        val start = CountDownLatch(1)
        val results = listOf("approver-1", "approver-2").map { approve(scenario, planId, it, start) }
        start.countDown()
        assertOneWinner(scenario, planId, results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) })
    }

    private fun assertOneWinner(scenario: ApiScenario, planId: String, outcomes: List<Outcome>) {
        outcomes.map { it.status } shouldContainExactlyInAnyOrder listOf(200, 409)
        val winner = outcomes.single { it.status == 200 }
        // The plan went through reservation and instruction exactly once.
        scenario.asUser("viewer").get("/api/v1/allocation/plans/$planId").then().statusCode(200).extract().let {
            it.path<String>("approvedBy") shouldBe winner.approver
            it.path<List<Any>>("lines").size shouldBe 1
            it.path<String>("status") shouldBe winner.planStatus
        }
        count("SELECT count(*) FROM etrm_registry.registry_instruction WHERE plan_ref = ?", UUID.fromString(planId)) shouldBe 1
        count(
            "SELECT count(*) FROM etrm_inventory.lot_event WHERE event_type = 'LotReserved' AND payload ->> 'reservationRef' = ?",
            planId,
        ) shouldBe 1
    }

    /** Waits until [expected] backends wait for a row lock on the plan table: both requests have reached the lock. */
    private fun awaitBackendsBlockedOnPlans(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        var waiting = 0
        while (System.nanoTime() < deadline) {
            waiting = count(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE '%allocation_plan%'",
            )
            if (waiting >= expected) return
            Thread.onSpinWait()
            Thread.yield()
        }
        throw AssertionError("only $waiting of $expected requests are blocked on the plan row")
    }

    private fun count(sql: String, vararg params: Any): Int = TestDatabase.dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }
}
