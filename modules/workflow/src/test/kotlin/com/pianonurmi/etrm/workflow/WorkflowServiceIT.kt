package com.pianonurmi.etrm.workflow

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.workflow.adapter.jooq.JooqApprovalTaskRepository
import com.pianonurmi.etrm.workflow.adapter.notify.LoggingTaskNotifier
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.Decision
import com.pianonurmi.etrm.workflow.api.DecisionOutcome
import com.pianonurmi.etrm.workflow.api.InboxFilter
import com.pianonurmi.etrm.workflow.api.InboxSort
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskDecided
import com.pianonurmi.etrm.workflow.api.TaskEscalated
import com.pianonurmi.etrm.workflow.api.TaskHandler
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.TaskTypeDefinition
import com.pianonurmi.etrm.workflow.application.WorkflowService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowServiceIT {
    /** Records decisions; can be told to supersede the next one. */
    class RecordingHandler(override val definition: TaskTypeDefinition) : TaskHandler {
        val decisions = mutableListOf<Pair<UUID, Decision>>()
        var supersedeNext: String? = null
        var withdrawable = false
        val withdrawn = mutableListOf<UUID>()

        override fun onDecision(task: ApprovalTaskView, decision: Decision, reason: String?): DecisionOutcome {
            decisions += task.subjectRef to decision
            return supersedeNext?.let {
                supersedeNext = null
                DecisionOutcome.Superseded(it)
            } ?: DecisionOutcome.Applied
        }

        override fun onWithdraw(task: ApprovalTaskView, reason: String): Boolean {
            if (withdrawable) withdrawn += task.subjectRef
            return withdrawable
        }
    }

    private val entityA = UUID.randomUUID()
    private val entityB = UUID.randomUUID()
    private var actor = Actor("trader", "trader", setOf(Role.TRADER), allEntities = true)
    private val published = mutableListOf<Any>()
    private var now = Instant.parse("2026-10-12T08:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }
    private val approvals = RecordingHandler(
        TaskTypeDefinition("TEST_APPROVAL", "test", "Test approval", setOf(Role.MIDDLE_OFFICE), defaultSla = Duration.ofHours(4)),
    )
    private val notices = RecordingHandler(
        TaskTypeDefinition(
            "TEST_NOTICE",
            "test",
            "Test notice",
            setOf(Role.BACK_OFFICE),
            fourEyes = false,
            allowedDecisions = setOf(Decision.ACKNOWLEDGE),
            reasonRequiredFor = emptySet(),
            escalationRoles = setOf(Role.MIDDLE_OFFICE),
        ),
    )
    private lateinit var workflow: WorkflowService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("workflow")
        val handlers = StaticListableBeanFactory().apply {
            addBean("approvals", approvals)
            addBean("notices", notices)
        }.getBeanProvider(TaskHandler::class.java)
        workflow = WorkflowService(
            JooqApprovalTaskRepository(TestDatabase.dsl),
            handlers,
            Savepoints.NONE,
            LoggingTaskNotifier(),
            ActorProvider { actor },
            ApplicationEventPublisher { published += it },
            clock,
        )
        workflow.registerDefinitions()
    }

    private fun asUser(id: String, vararg roles: Role, entities: Set<UUID>? = null) {
        actor = Actor(id, id, roles.toSet(), legalEntityScope = entities.orEmpty(), allEntities = entities == null)
    }

    private fun approval(subject: UUID = UUID.randomUUID(), entity: UUID? = entityA, requester: String = "trader", priority: Int? = null) =
        workflow.open(OpenTask("TEST_APPROVAL", "THING", subject, entity, "Approve $subject", requester, priority = priority))

    @Test
    fun `decisions follow the type rules and are applied by the owning module`() {
        val subject = UUID.randomUUID()
        val task = approval(subject)
        approval(subject).id shouldBe task.id
        task.dueAt shouldBe now.plus(Duration.ofHours(4))
        shouldThrow<ValidationException> { workflow.open(OpenTask("NO_SUCH_TYPE", "THING", subject, null, "x", "trader")) }

        asUser("trader", Role.TRADER, Role.MIDDLE_OFFICE)
        shouldThrow<SegregationOfDutiesException> { workflow.decide(task.id, Decision.APPROVE, null) }
        asUser("bo", Role.BACK_OFFICE)
        shouldThrow<ForbiddenException> { workflow.decide(task.id, Decision.APPROVE, null) }
        asUser("mo", Role.MIDDLE_OFFICE, entities = setOf(entityB))
        shouldThrow<ForbiddenException> { workflow.decide(task.id, Decision.APPROVE, null) }
        asUser("mo", Role.MIDDLE_OFFICE)
        shouldThrow<ValidationException> { workflow.decide(task.id, Decision.REJECT, " ") }
        shouldThrow<ValidationException> { workflow.decide(task.id, Decision.ACKNOWLEDGE, null) }

        val decided = workflow.decide(task.id, Decision.APPROVE, "fine")
        decided.status shouldBe TaskStatus.APPROVED
        decided.decidedBy shouldBe "mo"
        approvals.decisions.last() shouldBe (subject to Decision.APPROVE)
        published.filterIsInstance<TaskDecided>().last().task.id shouldBe task.id
        shouldThrow<ConflictException> { workflow.decide(task.id, Decision.APPROVE, null) }
        // The owner's own event arriving afterwards finds nothing open.
        workflow.closeFor("TEST_APPROVAL", "THING", subject, TaskStatus.APPROVED, null) shouldBe null

        val history = workflow.details(task.id).history
        history.map { it.eventType } shouldContainExactly listOf("OPENED", "DECIDED")
        history.map { it.hash }.toSet() shouldHaveSize 2

        val stale = approval()
        approvals.supersedeNext = "subject changed"
        workflow.decide(stale.id, Decision.APPROVE, null).let {
            it.status shouldBe TaskStatus.CANCELLED
            it.reason shouldBe "superseded: subject changed"
        }

        val notice = workflow.open(OpenTask("TEST_NOTICE", "THING", UUID.randomUUID(), entityA, "Look at this", "system"))
        asUser("system-ish", Role.BACK_OFFICE)
        workflow.decide(notice.id, Decision.ACKNOWLEDGE, null).status shouldBe TaskStatus.ACKNOWLEDGED
    }

    @Test
    fun `inbox filters by scope, role, assignment and pages with a cursor`() {
        val subjectType = "PAGED-${UUID.randomUUID()}"
        val tasks = (1..5).map { i ->
            now = now.plusSeconds(60)
            workflow.open(OpenTask("TEST_APPROVAL", subjectType, UUID.randomUUID(), if (i == 5) entityB else entityA, "Task $i", "trader", priority = i * 10))
        }
        asUser("mo", Role.MIDDLE_OFFICE, entities = setOf(entityA))
        val first = workflow.inbox(InboxFilter(subjectType = subjectType, limit = 2))
        first.items.map { it.summary } shouldContainExactly listOf("Task 1", "Task 2")
        first.nextCursor shouldNotBe null
        val second = workflow.inbox(InboxFilter(subjectType = subjectType, limit = 2, cursor = first.nextCursor))
        second.items.map { it.summary } shouldContainExactly listOf("Task 3", "Task 4")
        second.nextCursor shouldBe null // task 5 belongs to entity B and is invisible
        workflow.inbox(InboxFilter(subjectType = subjectType, sort = InboxSort.PRIORITY)).items.first().summary shouldBe "Task 4"
        shouldThrow<ForbiddenException> { workflow.inbox(InboxFilter(legalEntityId = entityB)) }

        asUser("bo", Role.BACK_OFFICE, entities = setOf(entityA, entityB))
        workflow.inbox(InboxFilter(subjectType = subjectType, actionable = true)).items shouldHaveSize 0
        workflow.inbox(InboxFilter(subjectType = subjectType)).items shouldHaveSize 5

        asUser("mo", Role.MIDDLE_OFFICE)
        workflow.claim(tasks[0].id).assignee shouldBe "mo"
        asUser("mo2", Role.MIDDLE_OFFICE)
        shouldThrow<ConflictException> { workflow.claim(tasks[0].id) }
        workflow.inbox(InboxFilter(subjectType = subjectType, mine = true)).items shouldHaveSize 0
        asUser("bo", Role.BACK_OFFICE)
        shouldThrow<ForbiddenException> { workflow.assign(tasks[0].id, "bo") }
        asUser("mo", Role.MIDDLE_OFFICE)
        workflow.assign(tasks[0].id, "mo2").assignee shouldBe "mo2"
        workflow.comment(tasks[0].id, "Handing over, check the counterparty limit").author shouldBe "mo"
        asUser("mo2", Role.MIDDLE_OFFICE)
        workflow.inbox(InboxFilter(subjectType = subjectType, mine = true)).items.single().id shouldBe tasks[0].id
        workflow.release(tasks[0].id).assignee shouldBe null
        workflow.details(tasks[0].id).let { d ->
            d.comments.single().body shouldBe "Handing over, check the counterparty limit"
            d.history.map { it.eventType } shouldContainExactly listOf("OPENED", "CLAIMED", "ASSIGNED", "COMMENTED", "RELEASED")
        }
        (workflow.counts(actionable = true)["TEST_APPROVAL"] ?: 0) shouldNotBe 0

        val results = workflow.decideAll(tasks.take(2).map { it.id } + UUID.randomUUID(), Decision.APPROVE, null)
        results.map { it.ok } shouldContainExactly listOf(true, true, false)
        asUser("someone-else", Role.TRADER)
        shouldThrow<ForbiddenException> { workflow.cancel(tasks[2].id, "not mine to cancel") }
        asUser("trader", Role.TRADER)
        // A type that cannot be withdrawn is decided, never cancelled: its subject would wait for nothing.
        shouldThrow<ConflictException> { workflow.cancel(tasks[2].id, "withdrawn by requester") }
        approvals.withdrawable = true
        workflow.cancel(tasks[2].id, "withdrawn by requester").status shouldBe TaskStatus.CANCELLED
        approvals.withdrawn shouldContainExactly listOf(tasks[2].subjectRef)
    }

    @Test
    fun `overdue tasks escalate one level per SLA period, up to three`() {
        val task = approval()
        now = task.dueAt.plusSeconds(1)
        (workflow.escalateOverdue(now) >= 1) shouldBe true
        workflow.task(task.id)!!.escalationLevel shouldBe 1
        workflow.escalateOverdue(now)
        workflow.task(task.id)!!.escalationLevel shouldBe 1
        now = task.dueAt.plus(Duration.ofHours(30)).truncatedTo(ChronoUnit.MINUTES)
        workflow.escalateOverdue(now)
        workflow.task(task.id)!!.escalationLevel shouldBe 3
        published.filterIsInstance<TaskEscalated>().count { it.task.id == task.id } shouldBe 2
        workflow.inbox(InboxFilter(overdue = true, subjectType = "THING")).items.any { it.id == task.id } shouldBe true
    }
}
