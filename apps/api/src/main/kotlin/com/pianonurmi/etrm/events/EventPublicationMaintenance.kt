package com.pianonurmi.etrm.events

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Keeps the event publication registry healthy. Publications still incomplete after [resubmitAfter] (Kafka was down,
 * a listener failed) are sent again; completed ones older than [retention] are deleted. The number of incomplete
 * publications is a gauge for alerting.
 */
@Component
class EventPublicationMaintenance(
    private val incomplete: IncompleteEventPublications,
    private val completed: CompletedEventPublications,
    private val jdbc: JdbcTemplate,
    meters: MeterRegistry,
    @Value("\${etrm.events.resubmit-after:PT15M}") private val resubmitAfter: Duration,
    @Value("\${etrm.events.retention:P7D}") private val retention: Duration,
) {
    private val log = LoggerFactory.getLogger(EventPublicationMaintenance::class.java)

    init {
        Gauge.builder("etrm.events.publications.incomplete") { countIncomplete().toDouble() }
            .description("Event publications not yet completed (externalisation or listener pending or failed)")
            .register(meters)
    }

    @Scheduled(cron = "\${etrm.events.maintenance-cron:0 */10 * * * *}")
    @SchedulerLock(name = "event-publication-maintenance", lockAtMostFor = "PT9M")
    fun maintain() {
        val pending = countIncomplete()
        if (pending > 0) log.info("Resubmitting event publications incomplete for more than {} ({} incomplete)", resubmitAfter, pending)
        incomplete.resubmitIncompletePublicationsOlderThan(resubmitAfter)
        completed.deletePublicationsOlderThan(retention)
    }

    fun countIncomplete(): Long =
        jdbc.queryForObject("SELECT count(*) FROM etrm_platform.event_publication WHERE completion_date IS NULL", Long::class.java) ?: 0
}
