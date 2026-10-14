package com.pianonurmi.etrm.events

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** An incomplete publication as an operator sees it. */
data class EventPublicationView(
    val id: UUID,
    val listenerId: String,
    val eventType: String,
    val publishedAt: Instant,
    val status: String?,
    val attempts: Int,
    val lastResubmittedAt: Instant?,
)

/**
 * Keeps the event publication registry healthy. Publications still incomplete after [resubmitAfter] (Kafka was down,
 * a listener failed) are sent again, up to [maxAttempts] times; after that they are parked: left incomplete, counted
 * in the gauge `etrm.events.publications.parked` and resubmitted only by an administrator once the cause is fixed
 * (review finding O7), so one poison event does not retry forever. Completed ones older than [retention] are deleted.
 */
@Component
class EventPublicationMaintenance(
    private val incomplete: IncompleteEventPublications,
    private val completed: CompletedEventPublications,
    private val jdbc: JdbcTemplate,
    meters: MeterRegistry,
    @Value("\${etrm.events.resubmit-after:PT15M}") private val resubmitAfter: Duration,
    @Value("\${etrm.events.retention:P7D}") private val retention: Duration,
    @Value("\${etrm.events.max-attempts:10}") private val maxAttempts: Int,
) {
    private val log = LoggerFactory.getLogger(EventPublicationMaintenance::class.java)

    init {
        Gauge.builder("etrm.events.publications.incomplete") { countIncomplete().toDouble() }
            .description("Event publications not yet completed (externalisation or listener pending or failed)")
            .register(meters)
        Gauge.builder("etrm.events.publications.parked") { countParked().toDouble() }
            .description("Incomplete event publications no longer resubmitted automatically: they need an administrator")
            .register(meters)
    }

    @Scheduled(cron = "\${etrm.events.maintenance-cron:0 */10 * * * *}")
    @SchedulerLock(name = "event-publication-maintenance", lockAtMostFor = "PT9M")
    fun maintain() {
        val pending = countIncomplete()
        if (pending > 0) log.info("Resubmitting event publications incomplete for more than {} ({} incomplete)", resubmitAfter, pending)
        incomplete.resubmitIncompletePublications(
            ResubmissionOptions.defaults().withMinAge(resubmitAfter).withFilter { it.completionAttempts < maxAttempts },
        )
        val parked = countParked()
        if (parked > 0) log.error("{} event publications failed {} times and are parked; see the runbook event-publications", parked, maxAttempts)
        completed.deletePublicationsOlderThan(retention)
    }

    fun countIncomplete(): Long =
        jdbc.queryForObject("SELECT count(*) FROM etrm_platform.event_publication WHERE completion_date IS NULL", Long::class.java) ?: 0

    fun countParked(): Long = jdbc.queryForObject(
        "SELECT count(*) FROM etrm_platform.event_publication WHERE completion_date IS NULL AND coalesce(completion_attempts, 0) >= ?",
        Long::class.java,
        maxAttempts,
    ) ?: 0

    fun parked(limit: Int): List<EventPublicationView> = jdbc.query(
        """
        SELECT id, listener_id, event_type, publication_date, status, coalesce(completion_attempts, 0) AS attempts, last_resubmission_date
        FROM etrm_platform.event_publication
        WHERE completion_date IS NULL AND coalesce(completion_attempts, 0) >= ?
        ORDER BY publication_date
        LIMIT ?
        """.trimIndent(),
        { rs, _ ->
            EventPublicationView(
                id = rs.getObject("id", UUID::class.java),
                listenerId = rs.getString("listener_id"),
                eventType = rs.getString("event_type"),
                publishedAt = rs.getTimestamp("publication_date").toInstant(),
                status = rs.getString("status"),
                attempts = rs.getInt("attempts"),
                lastResubmittedAt = rs.getTimestamp("last_resubmission_date")?.toInstant(),
            )
        },
        maxAttempts,
        limit,
    )

    /** Sends one publication again, whatever its attempts; false when there is no incomplete publication with this id. */
    fun resubmit(id: UUID): Boolean {
        val open = jdbc.queryForObject(
            "SELECT count(*) FROM etrm_platform.event_publication WHERE id = ? AND completion_date IS NULL",
            Long::class.java,
            id,
        ) ?: 0
        if (open == 0L) return false
        log.warn("Resubmitting parked event publication {}", id)
        incomplete.resubmitIncompletePublications { it.identifier == id }
        return true
    }
}
