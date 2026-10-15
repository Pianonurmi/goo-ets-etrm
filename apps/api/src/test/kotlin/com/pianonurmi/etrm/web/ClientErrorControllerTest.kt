package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ClientErrorControllerTest {
    private val meters = SimpleMeterRegistry()
    private var user = "alice"
    private val start = Instant.parse("2026-10-15T12:00:05Z")
    private var clock = Clock.fixed(start, ZoneOffset.UTC)
    private val controller = ClientErrorController(
        ActorProvider { Actor(user, user, emptySet()) },
        meters,
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId) = this

            override fun instant(): Instant = clock.instant()
        },
        perUserPerMinute = 2,
        globalPerMinute = 3,
    )

    private fun report() = controller.report(
        ClientErrorReport(ClientErrorKind.ERROR, "boom\nsecond line", "at x (app.js:1:2)", "/trades?x=1", "4bf92f3577b34da6a3ce929d0e0e4736"),
    )

    private fun count(outcome: String) = meters.find("etrm.web.client.errors").tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `reports are logged up to the limit per user and overall, then dropped until the next minute`() {
        repeat(3) { report() }
        count("logged") shouldBe 2.0
        count("dropped") shouldBe 1.0

        user = "bob"
        report()
        report()
        count("logged") shouldBe 3.0
        count("dropped") shouldBe 2.0

        clock = Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC)
        report()
        count("logged") shouldBe 4.0
    }

    @Test
    fun `the limiter starts every minute afresh`() {
        val limiter = ClientErrorLimiter(perUser = 1, global = 10)
        limiter.tryAcquire("a", start) shouldBe true
        limiter.tryAcquire("a", start.plusSeconds(10)) shouldBe false
        limiter.tryAcquire("b", start.plusSeconds(10)) shouldBe true
        limiter.tryAcquire("a", start.plusSeconds(60)) shouldBe true
    }
}
