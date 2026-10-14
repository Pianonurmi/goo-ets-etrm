package com.pianonurmi.etrm.jobs

import com.pianonurmi.etrm.platform.jobs.JobRun
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.env.MockEnvironment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.ScheduledTaskObservationContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JobMetricsTest {
    class Jobs {
        @Scheduled(cron = "\${test.cron:0 30 19 * * MON-FRI}", zone = "Europe/Berlin")
        @SchedulerLock(name = "weekday-job")
        fun weekdays() = Unit

        @Scheduled(fixedDelayString = "PT5M")
        fun unlocked() = Unit
    }

    private val meters = SimpleMeterRegistry()
    private val beans = DefaultListableBeanFactory().apply { registerSingleton("jobs", Jobs()) }
    private val start = Instant.parse("2026-10-14T10:00:00Z")
    private val metrics = JobMetrics(meters, MockEnvironment(), beans, Clock.fixed(start, ZoneOffset.UTC))

    private fun context(name: String) = ScheduledTaskObservationContext(Jobs(), Jobs::class.java.getMethod(name))

    private fun gauge(name: String, job: String) = meters.find(name).tag("job_name", job).gauge()?.value()

    private fun failures(job: String) = meters.find("etrm.jobs.failures").tag("job_name", job).counter()?.count()

    @Test
    fun `every scheduled job gets its expected interval and a last success at start`() {
        metrics.afterSingletonsInstantiated()
        // A weekday job's longest gap is the weekend: Friday to Monday.
        gauge("etrm.jobs.expected.interval", "weekday-job") shouldBe 3 * 86_400.0
        gauge("etrm.jobs.expected.interval", "Jobs.unlocked") shouldBe 300.0
        gauge("etrm.jobs.last.success", "weekday-job") shouldBe start.epochSecond.toDouble()
    }

    @Test
    fun `a locked run counts failed subjects and a thrown error, and a skipped run counts nothing`() {
        val ok = context("weekdays")
        metrics.onStart(ok)
        JobRun.lockAcquired()
        JobRun.subjectFailed()
        JobRun.subjectFailed()
        metrics.onStop(ok)
        failures("weekday-job") shouldBe 2.0

        val failed = context("weekdays").apply { error = IllegalStateException("boom") }
        metrics.onStart(failed)
        JobRun.lockAcquired()
        metrics.onStop(failed)
        failures("weekday-job") shouldBe 3.0

        // Another instance held the lock: no run here.
        val skipped = context("weekdays").apply { error = IllegalStateException("not seen") }
        metrics.onStart(skipped)
        metrics.onStop(skipped)
        failures("weekday-job") shouldBe 3.0
        JobRun.end().shouldBeNull()
    }

    @Test
    fun `a job without a lock always runs`() {
        val run = context("unlocked")
        metrics.onStart(run)
        JobRun.subjectFailed()
        metrics.onStop(run)
        failures("Jobs.unlocked") shouldBe 1.0
    }
}
