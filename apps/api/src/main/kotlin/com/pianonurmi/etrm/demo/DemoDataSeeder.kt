package com.pianonurmi.etrm.demo

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Demo data for trying the UI (`demo` profile together with `dev`). Runs once the server is up, through the REST
 * API as the dev personas, and only into a database without the `DEMO-FI` entity; delete the database to start over.
 */
@Component
@Profile("demo")
class DemoDataSeeder(private val environment: Environment, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!environment.matchesProfiles("dev | test")) {
            log.warn("The demo profile needs the dev profile (persona headers); no demo data was created")
            return
        }
        seed()
    }

    /** Returns false when the demo data is already there. */
    fun seed(): Boolean {
        val port = environment.getProperty("local.server.port") ?: environment.getProperty("server.port", "8080")
        val api = DemoClient("http://localhost:$port")
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
