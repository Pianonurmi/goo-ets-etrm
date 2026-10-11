package com.pianonurmi.etrm.demo

import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.web.DemoSeedState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The run log makes a failed seed resumable: completed steps are skipped and their values come back from the log. */
class DemoSeedRunnerIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-11T08:00:00Z"), ZoneOffset.UTC)
    private val runLog = DemoRunLog(JdbcClient.create(TestDatabase.dataSource), clock)
    private val calendar = DemoCalendar(LocalDate.parse("2026-10-11"))

    data class Thing(val id: String, val codes: List<String>)

    @BeforeEach
    fun migrate() = TestDatabase.migrate("demo")

    private fun context() = DemoContext(DemoClient("http://127.0.0.1:1"), calendar)

    @Test
    fun `a failed run stops at the failing step and the next attempt resumes there`() {
        var created = 0
        var failNext = true
        var seen: Thing? = null
        val steps = listOf(
            DemoStep("Create") {
                created++
                it.put("thing", Thing("T-1", listOf("A", "B")))
            },
            DemoStep("Flaky") { if (failNext) throw DemoRequestFailed("POST /api/v1/x answered 500: boom") },
            DemoStep("Use") { seen = it.get("thing") },
        )
        val run = runLog.start(calendar.today, steps.size)

        shouldThrow<DemoRequestFailed> { DemoSeedRunner(runLog).execute(run, steps, context()) }
        runLog.latest()!!.status shouldBe DemoSeedState.FAILED
        runLog.latest()!!.error shouldBe "Flaky: POST /api/v1/x answered 500: boom"
        runLog.completedSteps(run) shouldBe 1

        failNext = false
        runLog.resume(run, steps.size)
        DemoSeedRunner(runLog).execute(run, steps, context())

        created shouldBe 1
        seen shouldBe Thing("T-1", listOf("A", "B"))
        runLog.latest()!!.status shouldBe DemoSeedState.COMPLETED
        runLog.completedSteps(run) shouldBe 3
        runLog.latest()!!.seedDate shouldBe calendar.today
    }
}
