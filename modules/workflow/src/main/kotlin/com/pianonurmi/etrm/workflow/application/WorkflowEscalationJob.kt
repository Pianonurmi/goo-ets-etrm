package com.pianonurmi.etrm.workflow.application

import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Escalates overdue tasks every quarter of an hour; locked across instances with ShedLock. */
@Component
class WorkflowEscalationJob(private val workflow: WorkflowCommand, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(WorkflowEscalationJob::class.java)

    @Scheduled(cron = "\${etrm.workflow.escalation-cron:0 */15 * * * *}")
    @SchedulerLock(name = "workflow-escalation", lockAtMostFor = "PT10M")
    fun run() {
        val escalated = workflow.escalateOverdue(clock.instant())
        if (escalated > 0) log.info("Escalated {} overdue tasks", escalated)
    }
}
