package com.pianonurmi.etrm.valuation.adapter.workflow

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.valuation.api.ValuationRunSubmitted
import com.pianonurmi.etrm.valuation.application.ValuationRunService
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

/** Risk signs off each valuation run; a run with exceptions is signed off only with a reason. */
@Component
class ValuationSignOffTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val valuations: ValuationRunService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "valuation",
        displayName = "Valuation sign-off",
        candidateRoles = setOf(Role.RISK),
        defaultSla = Duration.ofHours(4),
        escalationRoles = setOf(Role.RISK),
        defaultPriority = 70,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> valuations.signOff(task.subjectRef, task.decidedBy!!, reason)
            Decision.REJECT -> valuations.reject(task.subjectRef, reason)
            Decision.ACKNOWLEDGE -> throw ValidationException("Valuations are signed off or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: ValuationRunSubmitted) {
        val r = event.run
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                r.id,
                r.legalEntityId,
                "Sign off ${r.kind.name.lowercase().replace('_', ' ')} valuation of ${r.valuationDate}: MtM ${r.baseCurrency} ${r.totalMtmBase}" +
                    if (r.exceptions > 0) ", ${r.exceptions} exceptions" else "",
                r.createdBy,
                mapOf("date" to r.valuationDate.toString(), "mtm" to r.totalMtmBase.toPlainString(), "exceptions" to r.exceptions.toString()),
            ),
        )
    }

    companion object {
        const val TYPE = "VALUATION_SIGN_OFF"
        private const val SUBJECT = "VALUATION_RUN"
    }
}
