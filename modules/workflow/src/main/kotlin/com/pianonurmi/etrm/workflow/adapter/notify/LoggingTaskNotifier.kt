package com.pianonurmi.etrm.workflow.adapter.notify

import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.domain.TaskNotifier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Notification adapter until an e-mail or chat integration is configured: writes structured log lines. */
@Component
class LoggingTaskNotifier : TaskNotifier {
    private val log = LoggerFactory.getLogger(LoggingTaskNotifier::class.java)

    override fun opened(task: ApprovalTaskView) {
        log.info("Task opened: {} {} for {} (due {}, roles {})", task.type, task.id, task.subjectRef, task.dueAt, task.candidateRoles)
    }

    override fun assigned(task: ApprovalTaskView) {
        log.info("Task assigned: {} {} to {}", task.type, task.id, task.assignee)
    }

    override fun escalated(task: ApprovalTaskView, notifyRoles: Set<Role>) {
        log.warn("Task escalated to level {}: {} {} overdue since {}; notify {}", task.escalationLevel, task.type, task.id, task.dueAt, notifyRoles)
    }
}
