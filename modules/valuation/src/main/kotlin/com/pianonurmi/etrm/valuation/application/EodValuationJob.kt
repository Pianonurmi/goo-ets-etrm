package com.pianonurmi.etrm.valuation.application

import com.pianonurmi.etrm.platform.jobs.JobRun
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.valuation.api.RunKind
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/** End-of-day valuation of every active legal entity after the curves are built; risk signs the runs off. */
@Component
class EodValuationJob(private val valuations: ValuationRunService, private val referenceData: ReferenceDataQuery, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(EodValuationJob::class.java)

    @Scheduled(cron = "\${etrm.valuation.eod-cron:0 45 19 * * MON-FRI}", zone = "Europe/Berlin")
    @SchedulerLock(name = "valuation-eod", lockAtMostFor = "PT1H")
    fun run() {
        val today = LocalDate.now(clock)
        referenceData.legalEntities().filter { it.active }.forEach { entity ->
            runCatching { valuations.submit(valuations.run(entity.id, today, RunKind.EOD, "end-of-day run").id) }
                .onFailure {
                    JobRun.subjectFailed()
                    log.warn("End-of-day valuation failed for {}: {}", entity.code, it.message, it)
                }
        }
    }
}
