package com.pianonurmi.etrm.workflow

import com.pianonurmi.etrm.allocation.api.AllocationCommand
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.workflow.adapter.jooq.JooqApprovalTaskRepository
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskType
import com.pianonurmi.etrm.workflow.application.WorkflowService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowServiceIT {
    private var currentUser = "trader"
    private val trades = mockk<TradeCommand>(relaxed = true)
    private val allocation = mockk<AllocationCommand>(relaxed = true)
    private lateinit var workflow: WorkflowService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("workflow")
        workflow = WorkflowService(
            JooqApprovalTaskRepository(TestDatabase.dsl),
            trades,
            allocation,
            ActorProvider { Actor(currentUser, currentUser, setOf(Role.MIDDLE_OFFICE)) },
            Clock.systemUTC(),
        )
    }

    @Test
    fun `tasks enforce four eyes and execute the subject command on decision`() {
        val tradeId = UUID.randomUUID()
        val task = workflow.open(OpenTask(TaskType.TRADE_APPROVAL, "TRADE", tradeId, null, "Trade T-1", requestedBy = "trader"))
        workflow.open(OpenTask(TaskType.TRADE_APPROVAL, "TRADE", tradeId, null, "Trade T-1 again", requestedBy = "trader")).id shouldBe task.id
        workflow.inbox() shouldHaveSize 1

        currentUser = "trader"
        shouldThrow<SegregationOfDutiesException> { workflow.decide(task.id, Decision.APPROVE, null) }
        currentUser = "approver"
        shouldThrow<ValidationException> { workflow.decide(task.id, Decision.REJECT, null) }
        val decided = workflow.decide(task.id, Decision.APPROVE, null)
        decided.status shouldBe TaskStatus.APPROVED
        decided.decidedBy shouldBe "approver"
        verify(exactly = 1) { trades.approve(tradeId) }
        shouldThrow<ValidationException> { workflow.decide(task.id, Decision.APPROVE, null) }
        workflow.inbox().shouldHaveSize(0)

        val planId = UUID.randomUUID()
        val planTask = workflow.open(OpenTask(TaskType.ALLOCATION_APPROVAL, "ALLOCATION_PLAN", planId, null, "Plan", requestedBy = "trader"))
        workflow.decide(planTask.id, Decision.REJECT, "wrong lots").status shouldBe TaskStatus.REJECTED
        verify(exactly = 1) { allocation.reject(planId, "wrong lots") }
    }
}
