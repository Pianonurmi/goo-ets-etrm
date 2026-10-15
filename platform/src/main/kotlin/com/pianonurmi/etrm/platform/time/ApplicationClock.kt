package com.pianonurmi.etrm.platform.time

import java.time.Clock
import java.time.Instant

/**
 * The application's clock where it cannot be injected: the time stamped on domain events and [AsOf.now] (ADR 0050).
 * The application installs its `Clock` bean at start-up, so events carry the same time as everything else; a test
 * that moves time may install its own clock and [reset] it afterwards.
 */
object ApplicationClock {
    @Volatile
    private var clock: Clock = Clock.systemUTC()

    val current: Clock get() = clock

    fun instant(): Instant = clock.instant()

    fun install(clock: Clock) {
        this.clock = clock
    }

    fun reset() = install(Clock.systemUTC())
}
