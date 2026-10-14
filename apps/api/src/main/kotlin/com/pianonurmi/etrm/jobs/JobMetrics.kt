package com.pianonurmi.etrm.jobs

import com.pianonurmi.etrm.platform.jobs.JobRun
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.scheduling.support.ScheduledTaskObservationContext
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Metrics for every scheduled job (review finding O2), named by the job's lock (`@SchedulerLock`), else
 * `Class.method`:
 *
 * - `etrm.jobs.failures{job_name}`: runs that threw, plus subjects a run skipped after a failure ([JobRun.subjectFailed]);
 * - `etrm.jobs.last.success{job_name}`: when a run last completed on this instance (epoch seconds; the start of the
 *   application until then), so a job that stops running or keeps failing is noticed;
 * - `etrm.jobs.expected.interval{job_name}`: the longest gap between two runs in its schedule (a weekday job's
 *   weekend), the yardstick of the alert `EtrmJobNotRun`.
 *
 * A replica that skipped a run because another holds the job's lock records nothing for it.
 */
@Component
class JobMetrics(
    private val meters: MeterRegistry,
    private val environment: Environment,
    private val beans: ListableBeanFactory,
    private val clock: Clock,
) : ObservationHandler<ScheduledTaskObservationContext>, SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(JobMetrics::class.java)
    private val lastSuccess = ConcurrentHashMap<String, AtomicLong>()

    override fun supportsContext(context: Observation.Context): Boolean = context is ScheduledTaskObservationContext

    override fun onStart(context: ScheduledTaskObservationContext) {
        JobRun.begin(locked = context.method.isAnnotationPresent(SchedulerLock::class.java))
    }

    override fun onStop(context: ScheduledTaskObservationContext) {
        val outcome = JobRun.end() ?: return
        if (!outcome.ran) return
        val name = jobName(context.targetClass, context.method)
        val failures = outcome.subjectFailures + if (context.error != null) 1 else 0
        if (failures > 0) failures(name).increment(failures.toDouble())
        if (context.error == null) lastSuccess(name).set(clock.instant().epochSecond)
    }

    /** Registers the gauges of every scheduled job at start, so one that never completes is noticed too. */
    override fun afterSingletonsInstantiated() {
        beans.beanDefinitionNames.forEach { bean ->
            val type = beans.getType(bean, false)?.let(ClassUtils::getUserClass) ?: return@forEach
            if (!type.name.startsWith(PACKAGE)) return@forEach
            ReflectionUtils.doWithMethods(type) { method ->
                AnnotatedElementUtils.getMergedRepeatableAnnotations(method, Scheduled::class.java).forEach { scheduled ->
                    register(jobName(type, method), scheduled)
                }
            }
        }
    }

    private fun register(name: String, scheduled: Scheduled) {
        lastSuccess(name)
        val interval = runCatching { expectedInterval(scheduled) }
            .onFailure { log.warn("No expected interval for job {}: {}", name, it.message) }
            .getOrNull() ?: return
        Gauge.builder("etrm.jobs.expected.interval") { interval.seconds.toDouble() }
            .tag(TAG, name)
            .baseUnit("seconds")
            .description("Longest gap between two scheduled runs of the job")
            .register(meters)
    }

    private fun lastSuccess(name: String): AtomicLong = lastSuccess.computeIfAbsent(name) {
        AtomicLong(clock.instant().epochSecond).also { value ->
            Gauge.builder("etrm.jobs.last.success", value) { it.get().toDouble() }
                .tag(TAG, name)
                .baseUnit("seconds")
                .description("When the job last completed on this instance, in seconds since the epoch")
                .register(meters)
        }
    }

    private fun failures(name: String): Counter = Counter.builder("etrm.jobs.failures")
        .tag(TAG, name)
        .description("Scheduled job runs that failed, and subjects a run skipped after a failure")
        .register(meters)

    /** The longest gap among the next runs of a cron schedule, or the fixed delay or rate; null when disabled. */
    private fun expectedInterval(scheduled: Scheduled): Duration? {
        val cron = environment.resolvePlaceholders(scheduled.cron)
        if (cron == Scheduled.CRON_DISABLED) return null
        if (cron.isNotEmpty()) {
            val zone = environment.resolvePlaceholders(scheduled.zone).takeIf { it.isNotEmpty() }?.let(ZoneId::of) ?: ZoneId.systemDefault()
            val expression = CronExpression.parse(cron)
            val runs = generateSequence(expression.next(ZonedDateTime.now(clock.withZone(zone)))) { expression.next(it) }.take(CRON_SAMPLE).toList()
            return runs.zipWithNext { a, b -> Duration.between(a, b) }.maxOrNull()
        }
        val fixed = listOf(scheduled.fixedRate, scheduled.fixedDelay).firstOrNull { it > 0 }
        if (fixed != null) return Duration.of(fixed, scheduled.timeUnit.toChronoUnit())
        val text = listOf(scheduled.fixedRateString, scheduled.fixedDelayString).map(environment::resolvePlaceholders).firstOrNull { it.isNotEmpty() }
            ?: return null
        return text.toLongOrNull()?.let { Duration.of(it, scheduled.timeUnit.toChronoUnit()) } ?: Duration.parse(text)
    }

    private fun jobName(type: Class<*>, method: Method): String =
        method.getAnnotation(SchedulerLock::class.java)?.name?.takeIf { it.isNotEmpty() } ?: "${ClassUtils.getUserClass(type).simpleName}.${method.name}"

    private companion object {
        const val TAG = "job_name"
        const val PACKAGE = "com.pianonurmi.etrm."

        /** Runs looked at for the longest gap: covers a weekend and a month end of a daily or monthly schedule. */
        const val CRON_SAMPLE = 40
    }
}
