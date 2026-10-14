package com.pianonurmi.etrm.ledger.adapter.workflow

import com.pianonurmi.etrm.ledger.api.LedgerAttentionNeeded
import com.pianonurmi.etrm.ledger.application.UnpostedReplay
import com.pianonurmi.etrm.ledger.domain.UnpostedEventRepository
import com.pianonurmi.etrm.ledger.domain.UnpostedStatus
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * An event the ledger could not post waits for an accountant (review findings F4 and F5). Approve retries the
 * posting (after fixing the cause: a missing FX rate or fixing, a posting rule); acknowledge dismisses it with a
 * reason, after the accountant booked it by a manual journal. The hourly replay closes the task when a retry posts.
 */
@Component
class LedgerAttentionTasks(
    private val workflow: WorkflowCommand,
    private val repository: UnpostedEventRepository,
    @Lazy private val replay: UnpostedReplay,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = UnpostedReplay.LEDGER_ATTENTION,
        ownerModule = "ledger",
        displayName = "Event not posted",
        candidateRoles = setOf(Role.ACCOUNTANT),
        fourEyes = false,
        allowedDecisions = setOf(Decision.APPROVE, Decision.ACKNOWLEDGE),
        reasonRequiredFor = setOf(Decision.ACKNOWLEDGE),
        defaultSla = Duration.ofDays(2),
        escalationRoles = setOf(Role.ACCOUNTANT),
        defaultPriority = 70,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        val record = repository.find(task.subjectRef) ?: throw NotFoundException("UnpostedEvent", task.subjectRef)
        if (record.status != UnpostedStatus.OPEN) return DecisionOutcome.Superseded("Already ${record.status.name.lowercase()}")
        when (decision) {
            Decision.APPROVE -> {
                val posted = runCatching { replay.retry(record, task.decidedBy!!) }
                    .getOrElse { throw ValidationException(it.message ?: "The event cannot be retried") }
                if (!posted) {
                    throw ValidationException("Still not posted: ${repository.find(record.id)?.reason ?: record.reason}")
                }
            }
            Decision.ACKNOWLEDGE -> repository.resolve(record.id, UnpostedStatus.DISMISSED, task.decidedBy!!, reason)
            Decision.REJECT -> throw ValidationException("Retry the posting or dismiss it with a reason")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: LedgerAttentionNeeded) {
        val u = event.unposted
        workflow.open(
            OpenTask(
                UnpostedReplay.LEDGER_ATTENTION,
                UnpostedReplay.SUBJECT,
                u.id,
                u.legalEntityId,
                "${u.eventType} not posted" + (u.ruleCode?.let { " by $it" } ?: "") + ": ${u.reason}".take(MAX_SUMMARY),
                "system",
                mapOf(
                    "eventType" to u.eventType,
                    "eventId" to u.eventId?.toString(),
                    "ruleCode" to u.ruleCode,
                    "kind" to u.kind,
                    "reason" to u.reason,
                    "retryable" to u.replayable,
                ),
            ),
        )
    }

    private companion object {
        const val MAX_SUMMARY = 300
    }
}
