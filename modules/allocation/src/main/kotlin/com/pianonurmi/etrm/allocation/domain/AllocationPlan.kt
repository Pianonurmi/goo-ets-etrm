package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.AllocationLineView
import com.pianonurmi.etrm.allocation.api.AllocationPlanView
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.PlanStatus
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import java.time.Instant
import java.util.UUID

/** Plan lifecycle rules. */
class AllocationPlan(state: AllocationPlanView) {
    var state: AllocationPlanView = state
        private set

    fun approve(approver: String, at: Instant): AllocationPlanView {
        require(PlanStatus.PROPOSED)
        SecondPerson.require(approver, state.proposedBy) { "Plan ${state.id} cannot be approved by the user who proposed it ($approver)" }
        return transition(state.copy(status = PlanStatus.APPROVED, approvedBy = approver, approvedAt = at))
    }

    fun reserved(lines: List<AllocationLineView>): AllocationPlanView {
        require(PlanStatus.APPROVED)
        return transition(state.copy(status = PlanStatus.RESERVED, lines = lines))
    }

    fun instructed(lines: List<AllocationLineView>): AllocationPlanView {
        require(PlanStatus.RESERVED, PlanStatus.INSTRUCTED)
        return transition(state.copy(status = PlanStatus.INSTRUCTED, lines = lines)).let(::settleIfDone)
    }

    fun lineUpdated(line: AllocationLineView): AllocationPlanView {
        require(PlanStatus.INSTRUCTED, PlanStatus.RESERVED)
        return transition(state.copy(lines = state.lines.map { if (it.id == line.id) line else it })).let(::settleIfDone)
    }

    fun stale(reason: String, by: String): AllocationPlanView {
        require(PlanStatus.PROPOSED, PlanStatus.APPROVED, PlanStatus.RESERVED)
        return transition(
            state.copy(
                status = PlanStatus.STALE,
                decidedBy = by,
                decisionReason = reason,
                lines = state.lines.map {
                    it.copy(status = LineStatus.CANCELLED)
                },
            ),
        )
    }

    fun reject(by: String, reason: String): AllocationPlanView {
        require(PlanStatus.PROPOSED)
        return transition(
            state.copy(
                status = PlanStatus.REJECTED,
                decidedBy = by,
                decisionReason = reason,
                lines = state.lines.map {
                    it.copy(status = LineStatus.CANCELLED)
                },
            ),
        )
    }

    fun expire(at: Instant): AllocationPlanView {
        require(PlanStatus.PROPOSED)
        return transition(
            state.copy(
                status = PlanStatus.EXPIRED,
                decisionReason = "soft reservation expired at $at",
                lines = state.lines.map {
                    it.copy(status = LineStatus.CANCELLED)
                },
            ),
        )
    }

    fun cancel(by: String, reason: String): AllocationPlanView {
        require(PlanStatus.PROPOSED, PlanStatus.APPROVED, PlanStatus.RESERVED, PlanStatus.INSTRUCTED)
        val lines = state.lines.map { if (it.status == LineStatus.CONFIRMED) it else it.copy(status = LineStatus.CANCELLED) }
        return transition(state.copy(status = PlanStatus.CANCELLED, decidedBy = by, decisionReason = reason, lines = lines))
    }

    fun close(): AllocationPlanView {
        require(PlanStatus.CONFIRMED, PlanStatus.PARTIALLY_FAILED)
        return transition(state.copy(status = PlanStatus.CLOSED))
    }

    private fun settleIfDone(plan: AllocationPlanView): AllocationPlanView {
        val terminal = plan.lines.all { it.status == LineStatus.CONFIRMED || it.status == LineStatus.FAILED || it.status == LineStatus.CANCELLED }
        if (!terminal || plan.lines.isEmpty()) return plan
        val status = if (plan.lines.all {
                it.status == LineStatus.CONFIRMED || it.status == LineStatus.CANCELLED
            } && plan.lines.any { it.status == LineStatus.CONFIRMED }
        ) {
            PlanStatus.CONFIRMED
        } else {
            PlanStatus.PARTIALLY_FAILED
        }
        state = plan.copy(status = status)
        return state
    }

    private fun transition(next: AllocationPlanView): AllocationPlanView {
        state = next.copy(version = state.version + 1)
        return state
    }

    private fun require(vararg allowed: PlanStatus) {
        if (state.status !in allowed) throw IllegalStateTransitionException("AllocationPlan ${state.id}", state.status.name, "one of ${allowed.joinToString()}")
    }

    companion object {
        fun lineBy(plan: AllocationPlanView, id: UUID): AllocationLineView? = plan.lines.firstOrNull { it.id == id }
    }
}
