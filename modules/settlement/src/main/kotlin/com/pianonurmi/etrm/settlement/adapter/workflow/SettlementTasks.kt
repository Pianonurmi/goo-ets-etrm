package com.pianonurmi.etrm.settlement.adapter.workflow

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.settlement.api.InvoiceDiscarded
import com.pianonurmi.etrm.settlement.api.InvoiceSubmitted
import com.pianonurmi.etrm.settlement.api.VatRuleProposed
import com.pianonurmi.etrm.settlement.application.InvoiceService
import com.pianonurmi.etrm.settlement.application.VatRuleService
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

/** Four eyes on every invoice: approving issues it (number, frozen details, PDF); rejecting returns it to draft. */
@Component
class InvoiceApprovalTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val invoices: InvoiceService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "settlement",
        displayName = "Invoice approval",
        candidateRoles = setOf(Role.BACK_OFFICE, Role.ACCOUNTANT),
        defaultSla = Duration.ofHours(24),
        escalationRoles = setOf(Role.ACCOUNTANT),
        defaultPriority = 60,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> invoices.approve(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> invoices.reject(task.subjectRef)
            Decision.ACKNOWLEDGE -> throw ValidationException("Invoices are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: InvoiceSubmitted) {
        val i = event.invoice
        workflow.open(
            OpenTask(
                TYPE,
                SUBJECT,
                i.id,
                i.legalEntityId,
                "Approve ${i.kind.name.lowercase().replace('_', ' ')} ${i.currency} ${i.grossTotal} (${i.lines.size} lines)" +
                    (i.counterpartyInvoiceRef?.let { ", supplier ref $it" } ?: ""),
                i.submittedBy!!,
                mapOf("kind" to i.kind.name, "gross" to i.grossTotal.toPlainString(), "currency" to i.currency),
            ),
        )
    }

    @EventListener
    fun on(event: InvoiceDiscarded) {
        workflow.closeFor(TYPE, SUBJECT, event.invoice.id, TaskStatus.CANCELLED, event.reason)
    }

    companion object {
        const val TYPE = "INVOICE_APPROVAL"
        private const val SUBJECT = "INVOICE"
    }
}

/** A new VAT rule (or version) takes effect only after a second person approves it. */
@Component
class VatRuleApprovalTasks(
    private val workflow: WorkflowCommand,
    @Lazy private val rules: VatRuleService,
) : TaskHandler {
    override val definition = TaskTypeDefinition(
        taskType = TYPE,
        ownerModule = "settlement",
        displayName = "VAT rule approval",
        candidateRoles = setOf(Role.ACCOUNTANT),
        defaultSla = Duration.ofDays(3),
        escalationRoles = setOf(Role.ACCOUNTANT),
        defaultPriority = 50,
    )

    override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
        when (decision) {
            Decision.APPROVE -> rules.approve(task.subjectRef, task.decidedBy!!)
            Decision.REJECT -> rules.retire(task.subjectRef, reason ?: "rejected")
            Decision.ACKNOWLEDGE -> throw ValidationException("VAT rules are approved or rejected")
        }
        return DecisionOutcome.Applied
    }

    @EventListener
    fun on(event: VatRuleProposed) {
        val r = event.rule
        workflow.open(
            OpenTask(
                TYPE,
                "VAT_RULE",
                r.id,
                null,
                "VAT rule ${r.code} v${r.version}: ${r.description} (${r.treatment} ${r.rate} %, from ${r.validFrom})",
                r.createdBy,
                mapOf("code" to r.code, "version" to r.version),
            ),
        )
    }

    companion object {
        const val TYPE = "VAT_RULE_APPROVAL"
    }
}
