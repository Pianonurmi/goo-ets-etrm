package com.pianonurmi.etrm.marketdata.adapter.workflow

import com.pianonurmi.etrm.marketdata.api.CurveSnapshotSubmitted
import com.pianonurmi.etrm.marketdata.application.CurveService
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

/** Curves are official only once a second person (risk or middle office) has checked and signed them off. */
@Component
class CurveSignOffTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val curves: CurveService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "marketdata",
        displayName = "Curve sign-off",
        candidateRoles = setOf(Role.RISK, Role.MIDDLE_OFFICE),
        defaultSla = Duration.ofHours(2),
        escalationRoles = setOf(Role.RISK),
        defaultPriority = 70,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> curves.signOff(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> curves.reject(task.subjectRef, reason)
            Decision.ACKNOWLEDGE -> throw ValidationException("Curves are signed off or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: CurveSnapshotSubmitted) {
        val s = event.snapshot
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                s.id,
                null,
                "Sign off curve ${s.curveCode} of ${s.asOfDate} (version ${s.version}, ${s.points.size} points" +
                    (if (s.gaps.isEmpty()) ")" else ", missing ${s.gaps.joinToString()})"),
                s.builtBy,
                mapOf("curve" to s.curveCode, "asOf" to s.asOfDate.toString(), "version" to s.version.toString(), "gaps" to s.gaps.joinToString()),
            ),
        )
    }

    companion object {
        const val TYPE = "CURVE_SIGN_OFF"
        private const val SUBJECT = "CURVE_SNAPSHOT"
    }
}
