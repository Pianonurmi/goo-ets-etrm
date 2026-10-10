package com.pianonurmi.etrm.etscompliance.adapter.workflow

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/** Daily review of ETS deadlines: reminders before each one, closed once met. */
@Component
class ComplianceDeadlineJob(private val tasks: ComplianceDeadlineTasks, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(ComplianceDeadlineJob::class.java)

    @Scheduled(cron = "\${etrm.etscompliance.deadline-cron:0 15 6 * * *}", zone = "Europe/Berlin")
    @SchedulerLock(name = "etscompliance-deadlines", lockAtMostFor = "PT30M")
    fun run() {
        val raised = tasks.review(LocalDate.now(clock))
        if (raised > 0) log.info("Raised {} ETS deadline reminders", raised)
    }
}
