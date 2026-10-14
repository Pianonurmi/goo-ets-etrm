package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.platform.jobs.JobRun
import java.time.Clock
import java.time.LocalDate
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Options expiring today and earlier, decided after the exchange's settlement prices and before the margin job. */
@Component
class OptionExpiryJob(private val expiry: OptionExpiryService, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(OptionExpiryJob::class.java)

    @Scheduled(cron = "\${etrm.trade.option-expiry-cron:0 0 19 * * MON-FRI}", zone = "Europe/Berlin")
    @SchedulerLock(name = "option-expiry", lockAtMostFor = "PT30M")
    fun run() {
        val today = LocalDate.now(clock)
        expiry.due(today).forEach { tradeId ->
            runCatching { expiry.expire(tradeId, today) }
                .onFailure {
                    JobRun.subjectFailed()
                    log.warn("Option trade {} not decided on {}: {}", tradeId, today, it.message, it)
                }
        }
    }
}
