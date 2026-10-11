package com.pianonurmi.etrm.demo

import com.pianonurmi.etrm.config.SeedToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Demo data for trying the UI (`demo` profile together with `dev`, `test` or `showcase`). Runs once the server is up,
 * through the REST API as the personas, and only into a database without the `DEMO-FI` entity.
 */
@Component
@Profile("demo")
class DemoDataSeeder(
    private val environment: Environment,
    private val clock: Clock,
    private val seedToken: ObjectProvider<SeedToken>,
) {
    private val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!environment.matchesProfiles("dev | test | showcase")) {
            log.warn("The demo profile needs the dev, test or showcase profile; no demo data was created")
            return
        }
        seed()
    }

    /** Returns false when the demo data is already there. */
    fun seed(): Boolean {
        val port = environment.getProperty("local.server.port") ?: environment.getProperty("server.port", "8080")
        // 127.0.0.1: the dev profile listens on IPv4 loopback, and the showcase accepts the seed token from loopback only.
        val api = DemoClient("http://127.0.0.1:$port", seedToken.ifAvailable?.value)
        val present = api.getList(DemoUser.ADMIN, "/api/v1/reference/legal-entities").any { it["code"] == "DEMO-FI" }
        if (present) {
            log.info("Demo data already present")
            return false
        }
        log.info("Creating demo data")
        DemoScenario(api, DemoCalendar(LocalDate.now(clock))).run()
        log.info("Demo data created: entities DEMO-FI and DEMO-SE; act as a persona in the UI to explore")
        return true
    }
}
