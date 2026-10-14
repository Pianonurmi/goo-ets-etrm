package com.pianonurmi.etrm.platform.test

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TestClockTest {
    @Test
    fun `stands still, moves when told, and keeps moving when seen in another zone`() {
        val clock = TestClock(Instant.parse("2026-12-31T22:30:00Z"))
        val berlin = clock.withZone(ZoneId.of("Europe/Berlin"))
        LocalDate.now(clock) shouldBe LocalDate.parse("2026-12-31")
        LocalDate.now(berlin) shouldBe LocalDate.parse("2026-12-31")
        clock.advance(Duration.ofMinutes(45))
        LocalDate.now(clock) shouldBe LocalDate.parse("2026-12-31")
        LocalDate.now(berlin) shouldBe LocalDate.parse("2027-01-01")
        clock.set(LocalDate.parse("2027-03-01"))
        berlin.instant() shouldBe Instant.parse("2027-03-01T12:00:00Z")
    }
}
