package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Two traders propose plans for the same entity at once. Proposals take the entity's advisory lock
 * (`AllocationPlanRepository.lockEntity`), so the second one solves against the first one's soft reservations and the
 * lot is never soft-reserved beyond its quantity. The first test holds the entity lock from outside until both
 * requests wait on it, which shows that the lock is what makes them queue; the second releases both together.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AllocationProposeRaceIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val TIMEOUT_SECONDS = 30L
        private const val LOT_MWH = 1000
    }

    @LocalServerPort
    private var port: Int = 0

    private val pool = Executors.newFixedThreadPool(2)

    @AfterEach
    fun stop() {
        pool.shutdownNow()
    }

    private fun propose(scenario: ApiScenario, key: String, start: CountDownLatch): Future<Int> = pool.submit<Int> {
        start.await()
        scenario.asUser("trader-$key").body("""{"holderEntityId":"${scenario.entityId}","idempotencyKey":"$key"}""")
            .post("/api/v1/allocation/plans").statusCode
    }

    /** One lot of 1 000 MWh and two sales of 600 MWh: two plans solved without each other would reserve 1 200. */
    private fun prepare(): ApiScenario {
        val scenario = ApiScenario(port).referenceData()
        scenario.receiveLot(1, LOT_MWH.toLong())
        scenario.confirmedHydroSale(600)
        scenario.confirmedHydroSale(600)
        return scenario
    }

    @Test
    fun `two proposals queue on the entity lock`() {
        val scenario = prepare()
        val holder = TestDatabase.dataSource.connection
        val statuses = try {
            holder.autoCommit = false
            holder.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use {
                it.setString(1, scenario.entityId)
                it.executeQuery().close()
            }
            val start = CountDownLatch(1)
            val results = listOf("a", "b").map { propose(scenario, "race-${scenario.entityId.takeLast(6)}-$it", start) }
            start.countDown()
            awaitBackendsWaitingOnAdvisoryLock(2)
            results.forEach { it.isDone shouldBe false }
            holder.commit()
            results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            holder.rollback()
            holder.close()
        }
        statuses shouldBe listOf(201, 201)
        softReserved(scenario) shouldBeLessThanOrEqual LOT_MWH
    }

    @Test
    fun `two proposals released together never reserve the lot twice`() {
        val scenario = prepare()
        val start = CountDownLatch(1)
        val results = listOf("a", "b").map { propose(scenario, "free-${scenario.entityId.takeLast(6)}-$it", start) }
        start.countDown()
        results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) } shouldBe listOf(201, 201)
        softReserved(scenario) shouldBeLessThanOrEqual LOT_MWH
    }

    private fun softReserved(scenario: ApiScenario): Int = TestDatabase.dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT coalesce(sum(s.quantity_mwh), 0) FROM etrm_allocation.soft_reservation s
               JOIN etrm_allocation.allocation_plan p ON p.id = s.plan_id WHERE p.holder_entity_id = ?::uuid""",
        ).use { statement ->
            statement.setString(1, scenario.entityId)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /** Waits until [expected] backends wait for an advisory lock: both proposals have reached the entity lock. */
    private fun awaitBackendsWaitingOnAdvisoryLock(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        var waiting = 0
        while (System.nanoTime() < deadline) {
            waiting = TestDatabase.dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
            if (waiting >= expected) return
            Thread.onSpinWait()
            Thread.yield()
        }
        throw AssertionError("only $waiting of $expected proposals wait on the entity lock")
    }
}
