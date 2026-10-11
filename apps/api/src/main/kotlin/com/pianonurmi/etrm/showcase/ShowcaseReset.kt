package com.pianonurmi.etrm.showcase

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.web.ShowcaseStatus
import com.pianonurmi.etrm.web.ShowcaseStatusView
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@ConfigurationProperties(prefix = "etrm.showcase")
data class ShowcaseResetProperties(
    /** The database the showcase may wipe on start; must equal `current_database()`. */
    val database: String = "",
    val wipeOnStart: Boolean = true,
    /** Nightly reset, in the zone below. */
    val resetCron: String = "0 15 4 * * *",
    val resetZone: String = "Europe/Helsinki",
    /** No reset this soon after a start, so a reset cannot be repeated back to back. */
    val resetCooldown: Duration = Duration.ofMinutes(10),
)

/**
 * Resets the showcase by ending the process with exit code 75 (ADR 0033): the container restarts, and the start wipes
 * the database and seeds it again. Wiping inside a running application is not safe (caches, jobs and event
 * publications in flight), a restart is.
 */
class ShowcaseResetService(
    private val context: ConfigurableApplicationContext,
    private val actors: ActorProvider,
    private val properties: ShowcaseResetProperties,
    private val clock: Clock,
    private val startedAt: Instant = clock.instant(),
    private val exit: (Int) -> Unit = { code -> Runtime.getRuntime().halt(code) },
) : ShowcaseStatus {
    private val log = LoggerFactory.getLogger(ShowcaseResetService::class.java)

    @Volatile
    private var resetRequestedAt: Instant? = null

    fun nextAllowedAt(): Instant = startedAt.plus(properties.resetCooldown)

    override fun current(): ShowcaseStatusView = ShowcaseStatusView(
        nextResetAt = CronExpression.parse(properties.resetCron).next(ZonedDateTime.now(clock.withZone(ZoneId.of(properties.resetZone))))?.toInstant(),
        resetAllowedAt = nextAllowedAt(),
        resetRequestedAt = resetRequestedAt,
    )

    /** An administrator's reset: needs ADMIN and a reason, and not within the cool-down after a start. */
    fun request(reason: String): Instant {
        val actor = actors.current()
        if (!actor.has(Role.ADMIN)) throw ForbiddenException("Only an administrator can reset the demo")
        require(reason.isNotBlank()) { "A reason is required" }
        val now = clock.instant()
        if (now.isBefore(nextAllowedAt())) {
            throw ConflictException("The demo was reset or started recently; try again after ${nextAllowedAt()}")
        }
        log.warn("Showcase reset requested by {}: {}", actor.userId, reason)
        return restartSoon()
    }

    fun nightly() {
        log.warn("Nightly showcase reset")
        restartSoon()
    }

    private fun restartSoon(): Instant {
        val now = clock.instant()
        resetRequestedAt = now
        // Let the response go out first, then close the context cleanly and leave with "temporary failure".
        Thread.ofVirtual().name("showcase-reset").start {
            Thread.sleep(EXIT_DELAY_MS)
            val code = SpringApplication.exit(context, { EXIT_CODE })
            exit(code)
        }
        return now
    }

    companion object {
        /** EX_TEMPFAIL: Docker's `restart: unless-stopped` starts the container again. */
        const val EXIT_CODE = 75
        private const val EXIT_DELAY_MS = 1500L
    }
}

/** The nightly reset (ADR 0033), under a lock so that only one instance restarts. */
@Component
@Profile("showcase")
class ShowcaseNightlyReset(private val reset: ShowcaseResetService) {
    @Scheduled(cron = "\${etrm.showcase.reset-cron:0 15 4 * * *}", zone = "\${etrm.showcase.reset-zone:Europe/Helsinki}")
    @SchedulerLock(name = "showcase-nightly-reset", lockAtMostFor = "PT5M")
    fun run() = reset.nightly()
}
