package com.pianonurmi.etrm.platform.test

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * A clock that stands still until a test moves it (ADR 0050): pass it where production code takes the `Clock` bean,
 * then [advance] or [set] it to cross a cut-off, a month end or an expiry without waiting or depending on today.
 */
class TestClock(start: Instant = DEFAULT_START, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    private val now = AtomicReference(start)

    override fun instant(): Instant = now.get()

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = ZonedView(this, zone)

    fun advance(by: Duration): Instant = now.updateAndGet { it.plus(by) }

    fun set(instant: Instant) = now.set(instant)

    /** Noon UTC on [date]: safely inside the same business day in every European zone. */
    fun set(date: LocalDate) = set(date.atTime(12, 0).toInstant(ZoneOffset.UTC))

    /** The same moving instant seen in another zone, as `Clock.withZone` does for a real clock. */
    private class ZonedView(private val base: TestClock, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = base.instant()

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = ZonedView(base, zone)
    }

    companion object {
        /** A Wednesday in the middle of a month, away from month and year ends. */
        val DEFAULT_START: Instant = Instant.parse("2026-04-15T10:00:00Z")
    }
}
