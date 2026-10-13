package com.pianonurmi.etrm.ledger.adapter.workflow

import com.pianonurmi.etrm.ledger.api.HedgeDesignationRequested
import com.pianonurmi.etrm.ledger.api.HedgeDiscontinuationRequested
import com.pianonurmi.etrm.ledger.application.HedgeService
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

/** A cash flow hedge is designated only with a second accountant's approval (ADR 0045). */
@Component
class HedgeDesignationTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val hedges: HedgeService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "ledger",
        displayName = "Hedge designation",
        candidateRoles = setOf(Role.ACCOUNTANT),
        defaultSla = Duration.ofDays(2),
        escalationRoles = setOf(Role.ACCOUNTANT),
        defaultPriority = 40,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> hedges.approveDesignation(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> hedges.rejectDesignation(task.subjectRef, task.decidedBy!!)
            Decision.ACKNOWLEDGE -> throw ValidationException("A hedge designation is approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: HedgeDesignationRequested) {
        val h = event.hedge
        val item = h.item
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                h.id,
                h.legalEntityId,
                "Hedge ${h.number}: forecast ${item.direction.name.lowercase()} of ${item.quantity.stripTrailingZeros().toPlainString()} " +
                    "${item.allowanceProductCode ?: item.bucketCode} around ${item.expectedDate}, hedged by ${h.instruments.joinToString { it.tradeNumber }}",
                h.requestedBy,
                mapOf("objective" to h.objective, "checks" to h.checks.joinToString("; ") { "${it.code}: ${if (it.passed) "met" else "not met"}" }),
            ),
        )
    }

    companion object {
        const val TYPE = "HEDGE_DESIGNATION"
        const val SUBJECT = "HEDGE"
    }
}

/** Hedge accounting stops only with a second accountant's approval; the reserve is then frozen or recycled. */
@Component
class HedgeDiscontinuationTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val hedges: HedgeService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "ledger",
        displayName = "Hedge discontinuation",
        candidateRoles = setOf(Role.ACCOUNTANT),
        defaultSla = Duration.ofDays(2),
        escalationRoles = setOf(Role.ACCOUNTANT),
        defaultPriority = 40,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> hedges.approveDiscontinuation(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> hedges.rejectDiscontinuation(task.subjectRef)
            Decision.ACKNOWLEDGE -> throw ValidationException("A hedge discontinuation is approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: HedgeDiscontinuationRequested) {
        val h = event.hedge
        val d = h.discontinuation!!
        workflow.open(
            OpenTask(
                TYPE,
                HedgeDesignationTasks.SUBJECT,
                h.id,
                h.legalEntityId,
                "Discontinue hedge ${h.number}: forecast ${if (d.stillExpected) "still expected, reserve kept" else "no longer expected, reserve recycled"}",
                d.requestedBy,
                mapOf("reason" to d.reason),
            ),
        )
    }

    companion object {
        const val TYPE = "HEDGE_DISCONTINUATION"
    }
}
