package com.pianonurmi.etrm.etscompliance

import com.pianonurmi.etrm.etscompliance.adapter.jooq.JooqEtsComplianceRepository
import com.pianonurmi.etrm.etscompliance.adapter.workflow.ComplianceDeadlineTasks
import com.pianonurmi.etrm.etscompliance.api.ComplianceStatus
import com.pianonurmi.etrm.etscompliance.api.DeadlineKind
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.EmissionsBasis
import com.pianonurmi.etrm.etscompliance.api.InstallationKind
import com.pianonurmi.etrm.etscompliance.api.InstallationView
import com.pianonurmi.etrm.etscompliance.api.RecordEmissions
import com.pianonurmi.etrm.etscompliance.api.RecordVerifiedEmissions
import com.pianonurmi.etrm.etscompliance.api.RegisterInstallation
import com.pianonurmi.etrm.etscompliance.api.RequestSurrender
import com.pianonurmi.etrm.etscompliance.api.SetFreeAllocation
import com.pianonurmi.etrm.etscompliance.api.SurrenderAttributed
import com.pianonurmi.etrm.etscompliance.api.SurrenderRequestStatus
import com.pianonurmi.etrm.etscompliance.api.VerifiedEmissionsApproved
import com.pianonurmi.etrm.etscompliance.application.EtsComplianceService
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.HoldingChanged
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.SubmitSurrender
import com.pianonurmi.etrm.workflow.api.OpenTask
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Installations, emissions approval, surrender attribution and requests, and deadline reminders against the database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtsComplianceIT {
    private var user = "middle1"
    private var roles = setOf(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
    private var today = LocalDate.of(2026, 10, 10)
    private val entity = UUID.randomUUID()
    private val operatorAccount = account(RegistryAccountType.OPERATOR_HOLDING)
    private val tradingAccount = account(RegistryAccountType.HOLDING)
    private val published = mutableListOf<Any>()
    private val holdingsByAccount = mutableMapOf<UUID, AllowanceHoldingView>()
    private val movements = mutableListOf<AllowanceMovementView>()
    private val opened = mutableListOf<OpenTask>()
    private val closed = mutableListOf<UUID>()
    private val submitted = slot<SubmitSurrender>()
    private lateinit var service: EtsComplianceService
    private lateinit var deadlineTasks: ComplianceDeadlineTasks
    private var seq = 0L

    private fun account(type: RegistryAccountType) = RegistryAccountView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "UNION_REGISTRY",
        "EU-$type",
        "account",
        type,
        HolderType.LEGAL_ENTITY,
        entity,
        true,
        true,
        true,
        true,
        1,
    )

    private val holdings = object : AllowanceHoldingQuery {
        override fun holdings(holderEntityId: UUID) = holdingsByAccount.values.toList()

        override fun holding(registryAccountId: UUID, productCode: String) = holdingsByAccount[registryAccountId]?.takeIf { it.productCode == productCode }

        override fun holdingById(holdingId: UUID) = holdingsByAccount.values.firstOrNull { it.id == holdingId }

        override fun movements(holdingId: UUID) = movements.filter { it.holdingId == holdingId }

        override fun movementTotal(holderEntityId: UUID, type: AllowanceMovementType, complianceYear: Int, productCodes: Set<String>?) = 0L
    }

    /** Records a movement on the account's EUA holding and lets the service see it, as inventory would. */
    private fun move(account: UUID, type: AllowanceMovementType, quantity: Long, year: Int?, on: LocalDate = today): AllowanceMovementView {
        val current = holdingsByAccount[account] ?: AllowanceHoldingView(UUID.randomUUID(), entity, account, "EUA", 0, 0, 1)
        val holding = current.copy(quantity = current.quantity + type.sign * quantity)
        holdingsByAccount[account] = holding
        val movement = AllowanceMovementView(++seq, holding.id, type, type.sign * quantity, on, null, null, null, year, "test", Instant.now())
        movements += movement
        service.on(HoldingChanged(holding, movement))
        return movement
    }

    @BeforeEach
    fun setUp() {
        TestDatabase.migrate("etscompliance")
        user = "middle1"
        roles = setOf(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
        today = LocalDate.of(2026, 10, 10)
        published.clear()
        holdingsByAccount.clear()
        movements.clear()
        opened.clear()
        closed.clear()
        val referenceData = mockk<ReferenceDataQuery>(relaxed = true)
        every { referenceData.requireRegistryAccount(operatorAccount.id) } returns operatorAccount
        every { referenceData.requireRegistryAccount(tradingAccount.id) } returns tradingAccount
        val catalogue = mockk<ProductCatalogueQuery>(relaxed = true)
        val registry = mockk<RegistryInstructionCommand>()
        every { registry.submitSurrender(capture(submitted)) } answers {
            mockk<RegistryInstructionView>(relaxed = true) { every { id } returns UUID.nameUUIDFromBytes(submitted.captured.idempotencyKey.toByteArray()) }
        }
        val workflow = mockk<WorkflowCommand>(relaxed = true)
        every { workflow.open(any()) } answers {
            opened += firstArg<OpenTask>()
            mockk(relaxed = true)
        }
        every { workflow.closeFor(any(), any(), any(), TaskStatus.COMPLETED, any()) } answers {
            closed += thirdArg<UUID>()
            null
        }
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = today.atStartOfDay().toInstant(ZoneOffset.UTC)
        }
        val actors = ActorProvider { Actor(user, user, roles, allEntities = true) }
        val repository = JooqEtsComplianceRepository(TestDatabase.dsl)
        lateinit var tasks: ComplianceDeadlineTasks
        val events = ApplicationEventPublisher { event ->
            published += event
            when (event) {
                is VerifiedEmissionsApproved -> tasks.on(event)
                is SurrenderAttributed -> tasks.on(event)
            }
        }
        service = EtsComplianceService(repository, referenceData, catalogue, holdings, registry, actors, events, clock)
        tasks = ComplianceDeadlineTasks(workflow, repository, service)
        deadlineTasks = tasks
    }

    private fun register(validFrom: LocalDate = LocalDate.of(2025, 1, 1)): InstallationView = service.register(
        RegisterInstallation(entity, InstallationKind.INSTALLATION, "FI-0001", "CHP plant", "fi", operatorAccount.id, validFrom = validFrom),
    )

    @Test
    fun `an installation surrenders from its operator holding account, held by its own entity`() {
        shouldThrow<ValidationException> {
            service.register(RegisterInstallation(entity, InstallationKind.INSTALLATION, "FI-X", "x", "FI", tradingAccount.id, validFrom = today))
        }
        shouldThrow<ValidationException> {
            service.register(RegisterInstallation(entity, InstallationKind.AIRCRAFT_OPERATOR, "FI-Y", "y", "FI", operatorAccount.id, validFrom = today))
        }
        val installation = register()
        installation.country shouldBe "FI"
        installation.eligibleProducts shouldBe listOf("EUA")
        roles = setOf(Role.TRADER)
        shouldThrow<ForbiddenException> { service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.FORECAST, 1)) }
    }

    @Test
    fun `verified emissions replace the estimates only once a second person approves them`() {
        val installation = register()
        service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.FORECAST, 12_000))
        service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.FORECAST, 13_000))
        service.emissions(installation.id, 2026).map { it.status } shouldBe listOf(EmissionStatus.SUPERSEDED, EmissionStatus.APPROVED)
        shouldThrow<ValidationException> { service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.MONTHLY_ESTIMATE, 900)) }

        val verified = service.recordVerifiedEmissions(installation.id, RecordVerifiedEmissions(2026, 12_400, "Verifier Oy", LocalDate.of(2027, 2, 20)))
        service.compliance(installation.id, 2026).emissionsBasis shouldBe EmissionsBasis.FORECAST
        shouldThrow<SegregationOfDutiesException> { service.approveVerified(verified.id, "middle1") }
        service.approveVerified(verified.id, "risk1").status shouldBe EmissionStatus.APPROVED
        val compliance = service.compliance(installation.id, 2026)
        compliance.emissions shouldBe 12_400
        compliance.emissionsBasis shouldBe EmissionsBasis.VERIFIED
        compliance.status shouldBe ComplianceStatus.VERIFIED
        published.filterIsInstance<VerifiedEmissionsApproved>() shouldHaveSize 1
    }

    @Test
    fun `surrenders are attributed to their compliance year, else the earliest year still owed, once per movement`() {
        val installation = register()
        service.recordEmissions(installation.id, RecordEmissions(2025, EmissionKind.FORECAST, 5_000))
        service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.FORECAST, 8_000))
        move(operatorAccount.id, AllowanceMovementType.PURCHASE, 20_000, null)
        move(operatorAccount.id, AllowanceMovementType.SURRENDER, 3_000, 2026)
        val unnamed = move(operatorAccount.id, AllowanceMovementType.SURRENDER, 5_000, null)
        // A surrender from another account is no installation's.
        move(tradingAccount.id, AllowanceMovementType.SURRENDER, 1_000, 2026)
        service.compliance(installation.id, 2025).surrendered shouldBe 5_000
        service.compliance(installation.id, 2026).surrendered shouldBe 3_000
        service.on(HoldingChanged(holdingsByAccount.getValue(operatorAccount.id), unnamed))
        service.compliance(installation.id, 2025).surrendered shouldBe 5_000
        published.filterIsInstance<SurrenderAttributed>() shouldHaveSize 2
        service.compliance(installation.id, 2026).let {
            it.outstanding shouldBe 5_000
            it.held shouldBe 12_000
        }
    }

    @Test
    fun `free allocation received comes from the installation account's movements for the year`() {
        val installation = register()
        service.setFreeAllocation(installation.id, 2026, SetFreeAllocation(10_000)).outstanding shouldBe 10_000
        move(operatorAccount.id, AllowanceMovementType.FREE_ALLOCATION, 6_000, 2026)
        move(operatorAccount.id, AllowanceMovementType.FREE_ALLOCATION, 1_000, 2025)
        service.freeAllocation(installation.id, 2026)!!.let {
            it.received shouldBe 6_000
            it.outstanding shouldBe 4_000
        }
        service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.FORECAST, 9_000))
        service.compliance(installation.id, 2026).net shouldBe 7_000 + 4_000 - 9_000
        service.entityCompliance(entity, 2026).let {
            it.freeAllocationExpected shouldBe 10_000
            it.freeAllocationReceived shouldBe 6_000
            it.verifiedEmissions shouldBe null
            it.estimatedEmissions shouldBe 9_000
            it.surrenderDeadline shouldBe LocalDate.of(2027, 9, 30)
        }
    }

    @Test
    fun `a surrender request goes to the registry for what is owed and follows the instruction`() {
        val installation = register()
        service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.FORECAST, 4_000))
        user = "operator1"
        val request = service.requestSurrender(installation.id, RequestSurrender(2026))
        request.quantity shouldBe 4_000
        submitted.captured.let {
            it.fromAccountId shouldBe operatorAccount.id
            it.productCode shouldBe "EUA"
            it.complianceYear shouldBe 2026
            it.idempotencyKey shouldBe "surrender:${installation.id}:2026:1"
        }
        shouldThrow<ValidationException> { service.requestSurrender(installation.id, RequestSurrender(2026, productCode = "EUAA")) }
        val instruction = mockk<RegistryInstructionView>(relaxed = true) { every { id } returns request.instructionId }
        service.on(InstructionConfirmed(instruction, null))
        service.surrenderRequests(installation.id).single().status shouldBe SurrenderRequestStatus.CONFIRMED
    }

    @Test
    fun `deadline reminders are raised once per stage and closed when the deadline is met`() {
        val installation = register(LocalDate.of(2026, 1, 1))
        service.setFreeAllocation(installation.id, 2026, SetFreeAllocation(0))
        service.recordEmissions(installation.id, RecordEmissions(2026, EmissionKind.FORECAST, 1_000))
        today = LocalDate.of(2027, 3, 5)
        deadlineTasks.review(today) shouldBe 1
        deadlineTasks.review(today) shouldBe 0
        opened.single().payload["deadline"] shouldBe DeadlineKind.VERIFIED_EMISSIONS.name
        today = LocalDate.of(2027, 3, 20)
        deadlineTasks.review(today) shouldBe 1
        today = LocalDate.of(2027, 3, 26)
        deadlineTasks.review(today) shouldBe 1
        today = LocalDate.of(2027, 4, 1)
        deadlineTasks.review(today) shouldBe 1
        opened.last().summary.startsWith("Overdue") shouldBe true
        val verified = service.recordVerifiedEmissions(installation.id, RecordVerifiedEmissions(2026, 1_000, "Verifier Oy", today))
        service.approveVerified(verified.id, "risk1")
        closed shouldHaveSize 4
    }

    @Test
    fun `reading another entity's installations is refused`() {
        val installation = register()
        val outsider = EtsComplianceService(
            JooqEtsComplianceRepository(TestDatabase.dsl),
            mockk(relaxed = true),
            mockk(relaxed = true),
            holdings,
            mockk(),
            { Actor("other", "o", setOf(Role.MIDDLE_OFFICE), legalEntityScope = setOf(UUID.randomUUID())) },
            { },
            Clock.systemUTC(),
        )
        shouldThrow<ForbiddenException> { outsider.compliance(installation.id, 2026) }
        outsider.installations(null, true) shouldBe emptyList()
    }
}
