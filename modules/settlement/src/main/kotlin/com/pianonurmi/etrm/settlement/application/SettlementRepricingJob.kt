package com.pianonurmi.etrm.settlement.application

import com.pianonurmi.etrm.platform.jobs.JobRun
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Prices the deliveries still waiting for a fixing, every morning. Most fixings arrive with a quote import, which
 * re-evaluates them at once; a month average does not: it becomes a fixing when its month has ended, which no import
 * announces. Each item is re-evaluated in its own transaction, so one that fails leaves the others priced.
 */
@Component
class SettlementRepricingJob(private val items: SettlementItemService) {
    private val log = LoggerFactory.getLogger(SettlementRepricingJob::class.java)

    @Scheduled(cron = "\${etrm.settlement.reprice-cron:0 0 6 * * *}", zone = "Europe/Berlin")
    @SchedulerLock(name = "settlement-reprice", lockAtMostFor = "PT30M")
    fun run() {
        items.waitingForPrice().forEach { id ->
            runCatching { items.reevaluate(id) }
                .onFailure {
                    JobRun.subjectFailed()
                    log.warn("Settlement item {} not re-evaluated: {}", id, it.message, it)
                }
        }
    }
}
