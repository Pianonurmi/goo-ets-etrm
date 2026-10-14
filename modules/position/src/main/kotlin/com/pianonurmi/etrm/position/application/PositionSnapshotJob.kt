package com.pianonurmi.etrm.position.application

import com.pianonurmi.etrm.platform.jobs.JobRun
import com.pianonurmi.etrm.position.api.PositionCommand
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import java.time.Clock
import java.time.LocalDate
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** End-of-day position snapshots for every active legal entity. */
@Component
class PositionSnapshotJob(private val positions: PositionCommand, private val referenceData: ReferenceDataQuery, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(PositionSnapshotJob::class.java)

    @Scheduled(cron = "\${etrm.position.snapshot-cron:0 50 23 * * *}")
    @SchedulerLock(name = "position-daily-snapshot", lockAtMostFor = "PT30M")
    fun run() {
        val today = LocalDate.now(clock)
        referenceData.legalEntities().filter { it.active }.forEach { entity ->
            runCatching { positions.takeSnapshots(entity.id, today) }
                .onFailure {
                    JobRun.subjectFailed()
                    log.warn("Position snapshot failed for {}: {}", entity.code, it.message, it)
                }
        }
    }
}
