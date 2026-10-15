package com.pianonurmi.etrm.demo

import com.pianonurmi.etrm.config.SeedToken
import com.pianonurmi.etrm.web.DemoSeedState
import com.pianonurmi.etrm.web.DemoSeedStatus
import com.pianonurmi.etrm.web.DemoSeedView
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * Demo data for trying the UI (`demo` profile together with `dev`, `test` or `showcase`). Runs once the server is up,
 * through the REST API as the personas, step by step ([DemoScenario.steps]) with a run log in `etrm_demo`. A failed
 * run stops at the failing step; the next start resumes there with the same dates. Readiness stays down until it
 * ends, because Spring Boot accepts traffic only after the ready listeners return.
 */
@Component
@Profile("demo")
class DemoDataSeeder(
    private val environment: Environment,
    private val clock: Clock,
    private val seedToken: ObjectProvider<SeedToken>,
    jdbc: JdbcClient,
) : DemoSeedStatus {
    private val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)
    private val runLog = DemoRunLog(jdbc, clock)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!environment.matchesProfiles("dev | test | showcase")) {
            log.warn("The demo profile needs the dev, test or showcase profile; no demo data was created")
            return
        }
        seed()
    }

    override fun current(): DemoSeedView? = runLog.latest()?.let { run ->
        DemoSeedView(
            state = run.status,
            step = run.currentStep,
            completedSteps = runLog.completedSteps(run.id),
            stepCount = run.stepCount,
            finishedAt = run.finishedAt,
        )
    }

    /** Returns false when the demo data is already there. A failing step is logged and rethrown. */
    fun seed(): Boolean {
        val port = environment.getProperty("local.server.port") ?: environment.getProperty("server.port", "8080")
        // 127.0.0.1: the dev profile listens on IPv4 loopback, and the showcase accepts the seed token from loopback only.
        val api = DemoClient("http://127.0.0.1:$port", seedToken.ifAvailable?.value)
        val latest = runLog.latest()
        if (latest?.status == DemoSeedState.COMPLETED) {
            log.info("Demo data already present (seeded {})", latest.seedDate)
            return false
        }
        if (latest == null && api.getList(DemoUser.ADMIN, "/api/v1/reference/legal-entities").any { it["code"] == "DEMO-FI" }) {
            log.info("Demo data already present (created before the run log)")
            return false
        }
        // A resumed run keeps its dates, so the data stays consistent across days.
        val calendar = DemoCalendar(latest?.seedDate ?: LocalDate.now(clock))
        val steps = DemoScenario(api, calendar).steps()
        val run = latest?.id?.also { runLog.resume(it, steps.size) } ?: runLog.start(calendar.today, steps.size)
        val started = clock.instant()
        DemoSeedRunner(runLog).execute(run, steps, DemoContext(api, calendar))
        log.info(
            "Demo data created in {} s: entities DEMO-FI and DEMO-SE; act as a persona in the UI to explore",
            Duration.between(started, clock.instant()).toSeconds(),
        )
        return true
    }
}
