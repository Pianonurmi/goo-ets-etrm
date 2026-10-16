package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Two operators act on one subject at once: two deliveries on one schedule, and two re-evaluations of one settlement
 * item. Each test holds the subject's row from outside until both requests wait on it, which shows that the row lock
 * (`TradeSupport.locked`, `SettlementItemRepository.find(forUpdate = true)`) is what makes them queue, then checks
 * that the queued requests leave the subject as if they had run one after the other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeliverySettlementRaceIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val TIMEOUT_SECONDS = 30L
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var clock: Clock

    private val pool = Executors.newFixedThreadPool(2)

    @AfterEach
    fun stop() {
        pool.shutdownNow()
    }

    private fun scheduleOf(scenario: ApiScenario, tradeId: String): String =
        scenario.asUser("backoffice").get("/api/v1/trades/$tradeId").then().extract().path("legs[0].deliveries[0].id")

    private fun deliver(scenario: ApiScenario, tradeId: String, scheduleId: String, key: String, start: CountDownLatch): Future<Int> = pool.submit<Int> {
        start.await()
        scenario.asUser("backoffice")
            .body("""{"scheduleId":"$scheduleId","quantity":100,"deliveredOn":"${LocalDate.now(clock)}","idempotencyKey":"$key"}""")
            .post("/api/v1/trades/$tradeId/deliveries").statusCode
    }

    @Test
    fun `two deliveries with one key on one schedule record it once`() {
        val scenario = ApiScenario(port).referenceData()
        val tradeId = scenario.confirmedHydroSale(100)
        val scheduleId = scheduleOf(scenario, tradeId)
        val key = "race:$tradeId"
        val statuses = whileHolding("SELECT id FROM etrm_trade.trade WHERE id = ? FOR UPDATE", UUID.fromString(tradeId), "trade") { start ->
            listOf(deliver(scenario, tradeId, scheduleId, key, start), deliver(scenario, tradeId, scheduleId, key, start))
        }
        statuses shouldBe listOf(200, 200)
        count("SELECT count(*) FROM etrm_trade.delivery_record WHERE idempotency_key = ?", key) shouldBe 1
    }

    @Test
    fun `two deliveries of the whole schedule with different keys record one`() {
        val scenario = ApiScenario(port).referenceData()
        val tradeId = scenario.confirmedHydroSale(100)
        val scheduleId = scheduleOf(scenario, tradeId)
        val statuses = whileHolding("SELECT id FROM etrm_trade.trade WHERE id = ? FOR UPDATE", UUID.fromString(tradeId), "trade") { start ->
            listOf(deliver(scenario, tradeId, scheduleId, "a:$tradeId", start), deliver(scenario, tradeId, scheduleId, "b:$tradeId", start))
        }
        statuses.count { it == 200 } shouldBe 1
        count("SELECT count(*) FROM etrm_trade.delivery_record WHERE schedule_id = ?", UUID.fromString(scheduleId)) shouldBe 1
    }

    @Test
    fun `two re-evaluations of one settlement item apply one after the other`() {
        val scenario = ApiScenario(port).referenceData()
        val tradeId = scenario.confirmedHydroSale(100)
        val scheduleId = scheduleOf(scenario, tradeId)
        scenario.asUser("backoffice")
            .body("""{"scheduleId":"$scheduleId","quantity":100,"deliveredOn":"${LocalDate.now(clock)}","idempotencyKey":"item:$tradeId"}""")
            .post("/api/v1/trades/$tradeId/deliveries").then().statusCode(200)
        val itemId: String = scenario.asUser("backoffice").get("/api/v1/settlement/items?tradeId=$tradeId").then().statusCode(200).extract().path("[0].id")
        val before = count("SELECT version FROM etrm_settlement.settlement_item WHERE id = ?", UUID.fromString(itemId))

        val statuses =
            whileHolding("SELECT id FROM etrm_settlement.settlement_item WHERE id = ? FOR UPDATE", UUID.fromString(itemId), "settlement_item") { start ->
                listOf("accountant-1", "accountant-2").map { user ->
                    pool.submit<Int> {
                        start.await()
                        scenario.asUser(user).post("/api/v1/settlement/items/$itemId/reevaluate").statusCode
                    }
                }
            }
        statuses shouldContainExactlyInAnyOrder listOf(200, 200)
        // Each re-evaluation read the other's result: no update was lost.
        count("SELECT version FROM etrm_settlement.settlement_item WHERE id = ?", UUID.fromString(itemId)) shouldBe before + 2
    }

    /** Holds [lockSql]'s row, starts the requests, waits until both are blocked on [table], then lets them run. */
    private fun whileHolding(lockSql: String, id: UUID, table: String, requests: (CountDownLatch) -> List<Future<Int>>): List<Int> {
        val holder = TestDatabase.dataSource.connection
        return try {
            holder.autoCommit = false
            holder.prepareStatement(lockSql).use {
                it.setObject(1, id)
                it.executeQuery().use { rows -> rows.next() shouldBe true }
            }
            val start = CountDownLatch(1)
            val results = requests(start)
            start.countDown()
            awaitBackendsBlockedOn(table, results.size)
            results.forEach { it.isDone shouldBe false }
            holder.commit()
            results.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            holder.rollback()
            holder.close()
        }
    }

    private fun awaitBackendsBlockedOn(table: String, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        var waiting = 0
        while (System.nanoTime() < deadline) {
            waiting = count(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query ILIKE ?",
                "%$table%",
            )
            if (waiting >= expected) return
            Thread.onSpinWait()
            Thread.yield()
        }
        throw AssertionError("only $waiting of $expected requests are blocked on $table")
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
