package com.pianonurmi.etrm.risk.adapter.workflow

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.risk.api.CreditSupportRequested
import com.pianonurmi.etrm.risk.api.LimitBreachResolved
import com.pianonurmi.etrm.risk.api.LimitBreached
import com.pianonurmi.etrm.risk.api.LimitChangeRequested
import com.pianonurmi.etrm.risk.api.UtilisationStatus
import com.pianonurmi.etrm.risk.application.RiskService
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

/** A new or changed risk limit takes effect once a second person in risk approves it. */
@Component
class LimitChangeTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val risk: RiskService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "risk",
        displayName = "Risk limit change",
        candidateRoles = setOf(Role.RISK),
        defaultSla = Duration.ofDays(1),
        escalationRoles = setOf(Role.RISK),
        defaultPriority = 60,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> risk.approveLimit(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> risk.rejectLimit(task.subjectRef)
            Decision.ACKNOWLEDGE -> throw ValidationException("Limit changes are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: LimitChangeRequested) {
        val l = event.limit
        val unit = l.currency ?: ""
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                l.id,
                l.legalEntityId,
                "${if (l.replaces == null) "New" else "Changed"} ${l.type} limit ${l.amount.toPlainString()} $unit: ${l.reason}".trim(),
                l.requestedBy,
                mapOf("type" to l.type.name, "amount" to l.amount.toPlainString(), "currency" to l.currency, "scope" to l.scope.toString()),
            ),
        )
    }

    companion object {
        const val TYPE = "LIMIT_CHANGE"
        private const val SUBJECT = "RISK_LIMIT"
    }
}

/** Collateral and guarantees count towards credit exposure once a second person approves them. */
@Component
class CreditSupportTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val risk: RiskService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "risk",
        displayName = "Credit support",
        candidateRoles = setOf(Role.RISK),
        defaultSla = Duration.ofDays(1),
        escalationRoles = setOf(Role.RISK),
        defaultPriority = 50,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> risk.approveCreditSupport(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> risk.rejectCreditSupport(task.subjectRef)
            Decision.ACKNOWLEDGE -> throw ValidationException("Credit support is approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: CreditSupportRequested) {
        val s = event.support
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                s.id,
                s.legalEntityId,
                "${s.kind} ${s.amount.toPlainString()} ${s.currency} from ${s.validFrom}" + (s.reference?.let { " ($it)" } ?: ""),
                s.requestedBy,
                mapOf("counterpartyId" to s.counterpartyId.toString(), "kind" to s.kind.name),
            ),
        )
    }

    companion object {
        const val TYPE = "CREDIT_SUPPORT_CHANGE"
        private const val SUBJECT = "CREDIT_SUPPORT"
    }
}

/** A limit breach is acknowledged with what is being done about it; the task closes itself when the breach ends. */
@Component
class LimitBreachTasks(private val workflow: WorkflowCommand) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "risk",
        displayName = "Limit breach",
        candidateRoles = setOf(Role.RISK),
        fourEyes = false,
        allowedDecisions = setOf(Decision.ACKNOWLEDGE),
        reasonRequiredFor = setOf(Decision.ACKNOWLEDGE),
        defaultSla = Duration.ofHours(4),
        escalationRoles = setOf(Role.RISK),
        defaultPriority = 90,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome = DecisionOutcome.Applied

    @EventListener
    fun on(event: LimitBreached) {
        val l = event.limit
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                event.breach.id,
                l.legalEntityId,
                if (event.utilisation.status == UtilisationStatus.NOT_MEASURED) {
                    val last = event.utilisation.value.toPlainString()
                    "${l.type} limit could not be measured (last value $last against ${l.amount.toPlainString()} ${l.currency ?: ""})".trim()
                } else {
                    "${l.type} limit breached: ${event.utilisation.value.toPlainString()} against ${l.amount.toPlainString()} ${l.currency ?: ""}".trim()
                },
                "system",
                mapOf("limitId" to l.id.toString(), "utilisation" to event.utilisation.utilisation.toPlainString(), "detail" to event.utilisation.detail),
            ),
        )
    }

    @EventListener
    fun on(event: LimitBreachResolved) {
        workflow.closeFor(TYPE, SUBJECT, event.breach.id, TaskStatus.COMPLETED, "back within the limit")
    }

    companion object {
        const val TYPE = "LIMIT_BREACH"
        private const val SUBJECT = "LIMIT_BREACH"
    }
}
