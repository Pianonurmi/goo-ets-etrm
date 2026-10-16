package com.pianonurmi.etrm.reporting.application

import com.pianonurmi.etrm.reporting.domain.ReportingRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Duration

/** How long report export content is kept (`etrm.reporting.retention`, ISO-8601, 400 days by default). */
@ConfigurationProperties("etrm.reporting")
data class ReportRetentionProperties(val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS)) {
    init {
        require(retention >= Duration.ofDays(MIN_RETENTION_DAYS)) { "etrm.reporting.retention must be at least $MIN_RETENTION_DAYS days" }
    }

    private companion object {
        const val DEFAULT_RETENTION_DAYS = 400L
        const val MIN_RETENTION_DAYS = 30L
    }
}

/**
 * Drops the content of report exports older than the retention period, in batches of one transaction each. The
 * export's number, parameters, row count, hash and audit event stay, so the log still proves what was produced; the
 * report can be run again for new content.
 */
@Component
@EnableConfigurationProperties(ReportRetentionProperties::class)
class ReportRetention(
    private val repository: ReportingRepository,
    private val properties: ReportRetentionProperties,
    private val transactions: TransactionOperations,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ReportRetention::class.java)

    @Scheduled(cron = "\${etrm.reporting.retention-cron:0 40 2 * * *}", zone = "UTC")
    @SchedulerLock(name = "report-retention", lockAtMostFor = "PT1H")
    fun run() {
        val dropped = expire()
        if (dropped > 0) log.info("Dropped the content of {} report exports older than {}", dropped, properties.retention)
    }

    /** Returns how many exports lost their content. */
    fun expire(): Int {
        val now = clock.instant()
        val before = now.minus(properties.retention)
        var total = 0
        do {
            val batch = transactions.execute { repository.expireContent(before, now, BATCH) }
            total += batch
        } while (batch == BATCH)
        return total
    }

    private companion object {
        const val BATCH = 500
    }
}
