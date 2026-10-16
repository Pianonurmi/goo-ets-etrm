package com.pianonurmi.etrm.etscompliance.adapter.workflow

import com.pianonurmi.etrm.etscompliance.api.DeadlineKind
import com.pianonurmi.etrm.etscompliance.api.DeadlineView
import com.pianonurmi.etrm.etscompliance.api.InstallationView
import com.pianonurmi.etrm.etscompliance.api.SurrenderAttributed
import com.pianonurmi.etrm.etscompliance.api.VerifiedEmissionsApproved
import com.pianonurmi.etrm.etscompliance.api.VerifiedEmissionsSubmitted
import com.pianonurmi.etrm.etscompliance.application.EtsComplianceService
import com.pianonurmi.etrm.etscompliance.domain.EtsComplianceRepository
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.security.Roles
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
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Verified emissions count only once a second person approves them. */
@Component
class VerifiedEmissionsTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val compliance: EtsComplianceService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "etscompliance",
        displayName = "Verified emissions approval",
        candidateRoles = setOf(Role.MIDDLE_OFFICE, Role.RISK),
        defaultSla = Duration.ofDays(2),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 60,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> compliance.approveVerified(task.subjectRef, task.decider())
            Decision.REJECT -> compliance.rejectVerified(task.subjectRef)
            Decision.ACKNOWLEDGE -> throw ValidationException("Verified emissions are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: VerifiedEmissionsSubmitted) {
        val r = event.record
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                r.id,
                event.installation.legalEntityId,
                "Approve verified emissions of ${event.installation.name} for ${r.complianceYear}: ${r.quantityTco2e} tCO2e (verifier ${r.verifier})",
                r.recordedBy,
                mapOf("installation" to event.installation.eutlId, "year" to r.complianceYear, "tco2e" to r.quantityTco2e, "document" to r.documentRef),
            ),
        )
    }

    companion object {
        const val TYPE = "VERIFIED_EMISSIONS_APPROVAL"
        private const val SUBJECT = "EMISSION_RECORD"
    }
}

/**
 * Reminders of regulatory deadlines: a task 30, 14 and 7 days before each deadline not yet met, and another once it
 * is overdue. The tasks close themselves when the deadline is met.
 */
@Component
class ComplianceDeadlineTasks(
    private val workflow: WorkflowCommand,
    private val repository: EtsComplianceRepository,
    @Lazy private val compliance: EtsComplianceService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "etscompliance",
        displayName = "ETS compliance deadline",
        candidateRoles = Roles.OPERATIONS,
        fourEyes = false,
        allowedDecisions = setOf(Decision.ACKNOWLEDGE),
        reasonRequiredFor = emptySet(),
        defaultSla = Duration.ofDays(7),
        escalationRoles = setOf(Role.MIDDLE_OFFICE),
        defaultPriority = 50,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome = DecisionOutcome.Applied

    /** Raises the reminders due on [today] and closes those whose deadline was met; returns the number raised. */
    @Transactional
    fun review(today: LocalDate): Int {
        var raised = 0
        repository.installations(null, activeOnly = true).forEach { installation ->
            compliance.deadlinesOf(installation, today.minusDays(LOOK_BACK_DAYS), today.plusDays(STAGES.first().toLong())).forEach { deadline ->
                if (deadline.met) {
                    close(deadline)
                } else if (raise(installation, deadline, today)) {
                    raised++
                }
            }
        }
        return raised
    }

    private fun raise(installation: InstallationView, deadline: DeadlineView, today: LocalDate): Boolean {
        val daysLeft = ChronoUnit.DAYS.between(today, deadline.dueOn).toInt()
        val stage = if (daysLeft < 0) OVERDUE else STAGES.filter { it >= daysLeft }.min()
        val subject = UUID.nameUUIDFromBytes("${installation.id}:${deadline.kind}:${deadline.complianceYear}:$stage".toByteArray())
        if (!repository.recordAlert(installation.id, deadline.kind, deadline.complianceYear, stage, subject)) return false
        val what = when (deadline.kind) {
            DeadlineKind.VERIFIED_EMISSIONS -> "verified emissions report"
            DeadlineKind.FREE_ALLOCATION -> "free allocation issue"
            DeadlineKind.SURRENDER -> "surrender"
        }
        val summary = if (stage == OVERDUE) {
            "Overdue: ${installation.name} ${deadline.complianceYear} $what was due ${deadline.dueOn}"
        } else {
            "${installation.name} ${deadline.complianceYear} $what due ${deadline.dueOn} ($daysLeft days)"
        }
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                subject,
                installation.legalEntityId,
                summary,
                "system",
                mapOf(
                    "installation" to installation.id,
                    "deadline" to deadline.kind.name,
                    "year" to deadline.complianceYear,
                    "dueOn" to deadline.dueOn.toString(),
                ),
                priority = if (stage == OVERDUE) 100 else 100 - stage,
            ),
        )
        return true
    }

    private fun close(deadline: DeadlineView) {
        repository.alertSubjects(deadline.installationId, deadline.kind, deadline.complianceYear).forEach {
            workflow.closeFor(TYPE, SUBJECT, it, TaskStatus.COMPLETED, "deadline met")
        }
    }

    private fun closeMet(installationId: UUID, year: Int) {
        val installation = repository.installation(installationId) ?: return
        compliance.deadlinesOf(installation, LocalDate.of(year, 1, 1), LocalDate.of(year + 2, 1, 1))
            .filter { it.complianceYear == year && it.met }
            .forEach(::close)
    }

    @EventListener
    fun on(event: VerifiedEmissionsApproved) = closeMet(event.installation.id, event.record.complianceYear)

    @EventListener
    fun on(event: SurrenderAttributed) = closeMet(event.installationId, event.complianceYear)

    companion object {
        const val TYPE = "COMPLIANCE_DEADLINE"
        private const val SUBJECT = "COMPLIANCE_DEADLINE"
        private val STAGES = listOf(30, 14, 7)
        private const val OVERDUE = -1
        private const val LOOK_BACK_DAYS = 400L
    }
}
