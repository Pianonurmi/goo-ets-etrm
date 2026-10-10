package com.pianonurmi.etrm.marketdata.application

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Builds and submits today's snapshot of every active curve after the day's prices are in. A person signs them off
 * in the inbox; curves without prices are skipped and logged.
 */
@Component
class CurveBuildJob(private val curves: CurveService, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(CurveBuildJob::class.java)

    @Scheduled(cron = "\${etrm.marketdata.curve-build-cron:0 15 18 * * MON-FRI}", zone = "Europe/Berlin")
    @SchedulerLock(name = "marketdata-curve-build", lockAtMostFor = "PT20M")
    fun run() {
        val today = LocalDate.now(clock)
        // Proxies are built after the curves they follow, which only matters once those are signed off.
        curves.curves(activeOnly = true).sortedBy { it.proxyCurveCode != null }.forEach { curve ->
            runCatching { curves.submit(curves.build(curve.code, today, "end-of-day build").id) }
                .onFailure { log.info("Curve {} not built for {}: {}", curve.code, today, it.message) }
        }
    }
}
