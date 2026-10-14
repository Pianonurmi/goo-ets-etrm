package com.pianonurmi.etrm.clearing.application

import com.pianonurmi.etrm.clearing.domain.ClearingRepository
import com.pianonurmi.etrm.platform.jobs.JobRun
import java.time.Clock
import java.time.LocalDate
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Variation margin of the day for every active clearing account, after the exchange's settlement prices are in. */
@Component
class MarginJob(private val clearing: ClearingService, private val repository: ClearingRepository, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(MarginJob::class.java)

    @Scheduled(cron = "\${etrm.clearing.margin-cron:0 30 19 * * MON-FRI}", zone = "Europe/Berlin")
    @SchedulerLock(name = "clearing-margin", lockAtMostFor = "PT30M")
    fun run() {
        val today = LocalDate.now(clock)
        repository.accounts(null).filter { it.active }.forEach { account ->
            runCatching { clearing.computeMargin(account.id, today) }
                .onFailure {
                    JobRun.subjectFailed()
                    log.warn("Variation margin of {} on {} not computed: {}", account.code, today, it.message, it)
                }
        }
    }
}
