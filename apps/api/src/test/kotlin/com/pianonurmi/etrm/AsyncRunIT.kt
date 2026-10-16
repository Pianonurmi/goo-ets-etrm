package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.web.AsyncRunner
import io.kotest.matchers.shouldBe
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Long operations answer 202 and finish on their own thread (increment 16 M2): the work runs as the requester, a
 * failure becomes the run's problem, a refusal is still an immediate 403, only the requester reads the run, and a run
 * whose instance went away is failed by the sweep.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AsyncRunIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val ANALYST = "RISK,MIDDLE_OFFICE"
        private const val POLLS = 600
        private const val POLL_MILLIS = 50L
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var runner: AsyncRunner

    @Autowired
    private lateinit var clock: Clock

    private lateinit var s: ApiScenario

    @BeforeEach
    fun scenario() {
        s = ApiScenario(port).referenceData()
    }

    private fun valuationBody() = """{"legalEntityId":"${s.entityId}","valuationDate":"${LocalDate.now(clock)}","kind":"ADHOC"}"""

    @Test
    fun `a valuation run answers 202 and runs as the requester`() {
        s.asUser("analyst", ANALYST).body(valuationBody()).post("/api/v1/valuation/runs")
            .then().statusCode(202).body("kind", equalTo("VALUATION")).body("requestedBy", equalTo("analyst"))
            .body("legalEntityId", equalTo(s.entityId))

        val valuationRun = s.startRun("analyst", ANALYST, "/api/v1/valuation/runs", valuationBody())

        s.asUser("analyst", ANALYST).get("/api/v1/valuation/runs/$valuationRun").then().statusCode(200)
            .body("createdBy", equalTo("analyst"))
    }

    @Test
    fun `a failing run records the problem the request would have answered`() {
        // No official valuation of the period's last business day: period end refuses with 422.
        val run: String = s.asUser("accountant", "ACCOUNTANT").body("""{"legalEntityId":"${s.entityId}","period":"2026-09"}""")
            .post("/api/v1/ledger/period-end/runs").then().statusCode(202).extract().path("id")

        val failed = awaitFinished("accountant", "ACCOUNTANT", run)
        failed.getString("status") shouldBe "FAILED"
        failed.getInt("problem.status") shouldBe 422
        failed.getString("problem.detail").contains("No official valuation") shouldBe true
        failed.getString("resultId") shouldBe null
    }

    @Test
    fun `a refusal is immediate and starts no run`() {
        val before = runCount()
        s.asUser("trader", "TRADER").body(valuationBody()).post("/api/v1/valuation/runs").then().statusCode(403)
        s.asUser("trader", "TRADER").body("""{"format":"CSV","parameters":{"legalEntityId":"${s.entityId}","period":"2026-09"}}""")
            .post("/api/v1/reports/GL_TRIAL_BALANCE/exports").then().statusCode(403)
        runCount() shouldBe before
    }

    @Test
    fun `only the requester reads a run`() {
        val run: String = s.asUser("analyst", ANALYST).body(valuationBody()).post("/api/v1/valuation/runs")
            .then().statusCode(202).extract().path("id")
        s.asUser("analyst2", ANALYST).get("/api/v1/runs/$run").then().statusCode(404)
        s.asUser("analyst", ANALYST).get("/api/v1/runs/$run").then().statusCode(200)
        s.asUser("analyst", ANALYST).get("/api/v1/runs/${UUID.randomUUID()}").then().statusCode(404)
    }

    @Test
    fun `a report export runs and its content is downloaded by the result id`() {
        val export = s.startRun(
            "accountant",
            "ACCOUNTANT",
            "/api/v1/reports/GL_TRIAL_BALANCE/exports",
            """{"format":"CSV","parameters":{"legalEntityId":"${s.entityId}","period":"2026-09"}}""",
        )

        s.asUser("accountant", "ACCOUNTANT").get("/api/v1/reports/exports/$export").then().statusCode(200)
            .header("X-Report-Export", export)
            .contentType("text/csv;charset=UTF-8")
    }

    @Test
    fun `the sweep fails a run whose instance stopped beating and forgets old ones`() {
        val now = clock.instant()
        val orphan = insertRun(status = "RUNNING", heartbeat = now.minus(Duration.ofMinutes(10)), finished = null)
        val alive = insertRun(status = "RUNNING", heartbeat = now, finished = null)
        val old = insertRun(status = "SUCCEEDED", heartbeat = now.minus(Duration.ofDays(9)), finished = now.minus(Duration.ofDays(8)))

        runner.sweep()

        runner.find(orphan)!!.let {
            it.status.name shouldBe "FAILED"
            it.problem!!.title shouldBe "abandoned"
        }
        runner.find(alive)!!.status.name shouldBe "RUNNING"
        runner.find(old) shouldBe null
    }

    private fun awaitFinished(user: String, roles: String, run: String): io.restassured.path.json.JsonPath {
        repeat(POLLS) {
            val status = s.asUser(user, roles).get("/api/v1/runs/$run").then().statusCode(200).extract().jsonPath()
            if (status.getString("status") != "RUNNING") return status
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Run $run did not finish")
    }

    private fun runCount(): Int = TestDatabase.dataSource.connection.use { connection ->
        connection.createStatement().use { it.executeQuery("SELECT count(*) FROM etrm_platform.async_run").use { rows -> rows.next().let { rows.getInt(1) } } }
    }

    private fun insertRun(status: String, heartbeat: java.time.Instant, finished: java.time.Instant?): UUID {
        val id = UUID.randomUUID()
        TestDatabase.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """INSERT INTO etrm_platform.async_run (id, kind, status, result_id, requested_by, requested_at, heartbeat_at, finished_at)
                   VALUES (?, 'TEST', ?, ?, 'someone', ?, ?, ?)""",
            ).use {
                it.setObject(1, id)
                it.setString(2, status)
                it.setObject(3, if (status == "SUCCEEDED") UUID.randomUUID() else null)
                it.setTimestamp(4, Timestamp.from(heartbeat))
                it.setTimestamp(5, Timestamp.from(heartbeat))
                it.setTimestamp(6, finished?.let(Timestamp::from))
                it.executeUpdate()
            }
        }
        return id
    }
}
