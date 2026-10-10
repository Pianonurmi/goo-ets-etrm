package com.pianonurmi.etrm.registry

import com.pianonurmi.etrm.inventory.adapter.jooq.JooqAllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotEventJournal
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqReconciliationRepository
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.AllowanceStatementLine
import com.pianonurmi.etrm.inventory.api.BreakAction
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ImportAllowanceStatement
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.inventory.api.StatementStatus
import com.pianonurmi.etrm.inventory.application.AllowanceHoldingService
import com.pianonurmi.etrm.inventory.application.LotService
import com.pianonurmi.etrm.inventory.application.ReconciliationService
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import com.pianonurmi.etrm.product.application.TaxonomySnapshots
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateProductionDevice
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.OwnerType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.registry.adapter.gateway.ManualRegistryGateway
import com.pianonurmi.etrm.registry.adapter.gateway.SimulatorRegistryGateway
import com.pianonurmi.etrm.registry.adapter.jooq.JooqRegistryInstructionRepository
import com.pianonurmi.etrm.registry.adapter.jooq.JooqTransferRouteRepository
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.registry.application.BreakResolutionService
import com.pianonurmi.etrm.registry.application.RegistryDispatchSettings
import com.pianonurmi.etrm.registry.application.RegistryInstructionService
import com.pianonurmi.etrm.registry.application.TransferRouteService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Each resolution action corrects our records to what the registry shows and closes the break in one step. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BreakResolutionIT {
    private val publisher = ApplicationEventPublisher { }
    private var roles = setOf(Role.REGISTRY_OPERATOR)
    private val actor = ActorProvider { Actor("ops", "Ops", roles) }
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneOffset.UTC)
    private val accounts = AtomicInteger()
    private lateinit var referenceData: ReferenceDataService
    private lateinit var lots: LotService
    private lateinit var allowances: AllowanceHoldingService
    private lateinit var reconciliation: ReconciliationService
    private lateinit var instructions: RegistryInstructionService
    private lateinit var breaks: BreakResolutionService
    private lateinit var entityId: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "inventory", "registry")
        val dsl = TestDatabase.dsl
        dsl.execute("UPDATE etrm_referencedata.registry SET integration = 'MANUAL' WHERE code = 'HKNR'")
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        lots = LotService(JooqLotRepository(dsl), JooqLotEventJournal(dsl), referenceData, taxonomy, criteria, actor, publisher, clock)
        allowances = AllowanceHoldingService(
            JooqAllowanceHoldingRepository(dsl),
            referenceData,
            ProductCatalogueService(JooqAllowanceProductRepository(dsl)),
            actor,
            publisher,
        )
        reconciliation = ReconciliationService(
            JooqReconciliationRepository(dsl),
            JooqLotRepository(dsl),
            JooqAllowanceHoldingRepository(dsl),
            referenceData,
            actor,
            publisher,
            clock,
        )
        instructions = RegistryInstructionService(
            JooqRegistryInstructionRepository(dsl),
            listOf(SimulatorRegistryGateway(), ManualRegistryGateway()),
            lots,
            lots,
            referenceData,
            TransferRouteService(JooqTransferRouteRepository(dsl), referenceData, BigDecimal("0.05"), 3),
            actor,
            publisher,
            clock,
            allowances,
            allowances,
            RegistryDispatchSettings(unplannedNeedsApproval = false),
        )
        breaks = BreakResolutionService(reconciliation, reconciliation, lots, lots, allowances, instructions, referenceData, actor, clock)
        entityId = referenceData.createLegalEntity(CreateLegalEntity("BRK-CO", "Break Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        referenceData.createProductionDevice(
            CreateProductionDevice(
                gsrn = "643000000000000016",
                name = "Vesivoima",
                country = "FI",
                capacityKw = BigDecimal("12000"),
                commissioningDate = LocalDate.of(2010, 1, 1),
                energySourceCode = "HYDRO_RESERVOIR",
                technologyCode = "HYDRO_TURBINE_STORAGE",
                ownerType = OwnerType.LEGAL_ENTITY,
                ownerId = entityId,
            ),
        )
    }

    /** A fresh account per test, so statements of one test do not clear the breaks of another. */
    private fun account(registry: String = "HKNR"): UUID = referenceData.createRegistryAccount(
        CreateRegistryAccount(
            registry,
            "BRK-${accounts.incrementAndGet()}",
            "Breaks",
            if (registry == "UNION_REGISTRY") RegistryAccountType.TRADING else RegistryAccountType.HOLDING,
            HolderType.LEGAL_ENTITY,
            entityId,
        ),
    ).id

    private fun receipt(account: UUID, issuance: String, start: Long, end: Long, month: YearMonth = YearMonth.of(2026, 4)) = ReceiveLots(
        holderEntityId = entityId,
        registryAccountId = account,
        issuanceRef = issuance,
        range = CertificateRange.of(start, end),
        deviceGsrn = "643000000000000016",
        productionPeriod = LocalDateRange.ofMonth(month),
        issueDate = LocalDate.of(2026, 6, 10),
        issuingBody = "HKN",
        issuingCountry = "FI",
        acquisitionSource = AcquisitionSource.ISSUANCE,
    )

    private fun reconcile(account: UUID, vararg lines: Triple<String, Long, Long>, month: YearMonth = YearMonth.of(2026, 4)): List<ReconciliationBreakView> {
        val statement = reconciliation.importStatement(
            ImportStatement(
                "HKNR",
                account,
                LocalDate.of(2026, 10, 11),
                "statement.csv",
                lines.mapIndexed { i, (issuance, start, end) ->
                    StatementLine(i + 1, issuance, CertificateRange.of(start, end), "643000000000000016", LocalDateRange.ofMonth(month))
                },
            ),
        )
        return reconciliation.reconcile(statement.id).breaks
    }

    @Test
    fun `a missing receipt is recorded exactly as the registry shows it`() {
        val acc = account()
        val brk = reconcile(acc, Triple("HK-1", 1, 100)).single()
        brk.type shouldBe BreakType.MISSING_INTERNALLY
        shouldThrow<ValidationException> { breaks.recordReceipt(brk.id, receipt(acc, "HK-1", 1, 99), "partial") }
        shouldThrow<ValidationException> { breaks.withdrawLot(brk.id, "wrong action") }
        roles = setOf(Role.TRADER)
        shouldThrow<ForbiddenException> { breaks.recordReceipt(brk.id, receipt(acc, "HK-1", 1, 100), "not mine") }
        roles = setOf(Role.REGISTRY_OPERATOR)

        val resolved = breaks.recordReceipt(brk.id, receipt(acc, "HK-1", 1, 100), "issuance booked late")
        resolved.status shouldBe BreakStatus.RESOLVED
        resolved.resolutionAction shouldBe BreakAction.RECORD_RECEIPT
        lots.lots(LotFilter(holderEntityId = entityId)).count { it.registryAccountId == acc && it.status == LotStatus.HELD } shouldBe 1
        shouldThrow<IllegalStateTransitionException> { breaks.recordReceipt(brk.id, receipt(acc, "HK-1", 1, 100), "twice") }
        reconcile(acc, Triple("HK-1", 1, 100)).shouldBeEmpty()
    }

    @Test
    fun `certificates gone from the registry are withdrawn, splitting the lot when only part is gone`() {
        val acc = account()
        val lot = lots.receive(receipt(acc, "HK-2", 1_001, 1_100))
        val brk = reconcile(acc, Triple("HK-2", 1_001, 1_020), Triple("HK-2", 1_081, 1_100)).single()
        brk.range shouldBe CertificateRange.of(1_021, 1_080)
        breaks.assign(brk.id, null).let {
            it.status shouldBe BreakStatus.INVESTIGATING
            it.assignee shouldBe "ops"
        }
        breaks.withdrawLot(brk.id, "transferred outside the system").status shouldBe BreakStatus.RESOLVED
        val pieces = lots.lots(LotFilter(holderEntityId = entityId)).filter { it.registryAccountId == acc && it.rootLotId == lot.id }
        pieces.single { it.status == LotStatus.WITHDRAWN }.range shouldBe CertificateRange.of(1_021, 1_080)
        pieces.filter {
            it.status == LotStatus.HELD
        }.map { it.range }.toSet() shouldBe setOf(CertificateRange.of(1_001, 1_020), CertificateRange.of(1_081, 1_100))
        reconcile(acc, Triple("HK-2", 1_001, 1_020), Triple("HK-2", 1_081, 1_100)).shouldBeEmpty()
    }

    @Test
    fun `a lot with wrong attributes is replaced by one with the registry's attributes`() {
        val acc = account()
        val lot = lots.receive(receipt(acc, "HK-3", 2_001, 2_100, month = YearMonth.of(2026, 4)))
        val brk = reconcile(acc, Triple("HK-3", 2_001, 2_100), month = YearMonth.of(2026, 5)).single()
        brk.type shouldBe BreakType.ATTRIBUTE_MISMATCH
        shouldThrow<ValidationException> { breaks.replaceLot(brk.id, receipt(acc, "HK-3", 2_001, 2_050, YearMonth.of(2026, 5)), "short") }
        val resolved = breaks.replaceLot(brk.id, receipt(acc, "HK-3", 2_001, 2_100, YearMonth.of(2026, 5)), "registry corrected the period")
        resolved.resolutionAction shouldBe BreakAction.REPLACE_LOT
        lots.requireLot(lot.id).status shouldBe LotStatus.WITHDRAWN
        lots.lots(LotFilter(holderEntityId = entityId)).single { it.registryAccountId == acc && it.status == LotStatus.HELD }
            .productionPeriod shouldBe LocalDateRange.ofMonth(YearMonth.of(2026, 5))
        reconcile(acc, Triple("HK-3", 2_001, 2_100), month = YearMonth.of(2026, 5)).shouldBeEmpty()
    }

    @Test
    fun `an instruction the registry executed is confirmed from its break`() {
        val acc = account()
        val lot = lots.receive(receipt(acc, "HK-4", 3_001, 3_100))
        val instruction = instructions.submitTransfer(SubmitTransfer(lot.id, null, "HKNR", "HK-EXT-1", idempotencyKey = "brk-transfer-$acc"))
        instruction.status shouldBe InstructionStatus.SENT
        val brk = reconcile(acc, Triple("HK-4", 3_001, 3_050)).single()
        brk.type shouldBe BreakType.MISSING_IN_REGISTRY
        brk.lotId shouldBe lot.id
        breaks.confirmInstruction(brk.id, "HK-TX-77", "executed in two parts, checked in the registry").resolutionAction shouldBe
            BreakAction.CONFIRM_INSTRUCTION
        instructions.requireInstruction(instruction.id).let {
            it.status shouldBe InstructionStatus.CONFIRMED
            it.externalRef shouldBe "HK-TX-77"
        }
        lots.requireLot(lot.id).status shouldBe LotStatus.TRANSFERRED_OUT
    }

    @Test
    fun `a balance difference is booked as a correction and the next statement agrees`() {
        val acc = account("UNION_REGISTRY")
        allowances.record(RecordAllowanceMovement(entityId, acc, "EUA", AllowanceMovementType.OPENING_BALANCE, 100, LocalDate.of(2026, 1, 1)))

        fun statement(quantity: Long): List<ReconciliationBreakView> {
            val s = reconciliation.importAllowanceStatement(
                ImportAllowanceStatement("UNION_REGISTRY", acc, LocalDate.of(2026, 10, 11), "ur.csv", listOf(AllowanceStatementLine(1, "EUA", quantity))),
            )
            return reconciliation.reconcile(s.id).also { if (it.breaks.isEmpty()) it.status shouldBe StatementStatus.RECONCILED }.breaks
        }
        val brk = statement(90).single()
        breaks.correctHolding(brk.id, "registry deducted a transfer we missed").status shouldBe BreakStatus.RESOLVED
        allowances.holding(acc, "EUA")!!.quantity shouldBe 90
        allowances.movements(allowances.holding(acc, "EUA")!!.id).last().let {
            it.type shouldBe AllowanceMovementType.CORRECTION
            it.quantity shouldBe -10
        }
        statement(90).shouldBeEmpty()
    }
}
