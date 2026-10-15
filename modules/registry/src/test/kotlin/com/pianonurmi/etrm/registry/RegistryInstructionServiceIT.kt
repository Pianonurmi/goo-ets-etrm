package com.pianonurmi.etrm.registry

import com.pianonurmi.etrm.document.adapter.jooq.JooqDocumentRepository
import com.pianonurmi.etrm.document.adapter.pdf.OpenHtmlPdfRenderer
import com.pianonurmi.etrm.document.application.DocumentService
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqAllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqBeneficiaryRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotEventJournal
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqReconciliationRepository
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CancellationPurpose
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.application.AllowanceHoldingService
import com.pianonurmi.etrm.inventory.application.BeneficiaryService
import com.pianonurmi.etrm.inventory.application.LotService
import com.pianonurmi.etrm.inventory.application.ReconciliationService
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
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
import com.pianonurmi.etrm.registry.adapter.gateway.FileRegistryGateway
import com.pianonurmi.etrm.registry.adapter.gateway.ManualRegistryGateway
import com.pianonurmi.etrm.registry.adapter.gateway.SimulatorRegistryGateway
import com.pianonurmi.etrm.registry.adapter.jooq.JooqRegistryInstructionRepository
import com.pianonurmi.etrm.registry.adapter.jooq.JooqStatementFormatRepository
import com.pianonurmi.etrm.registry.adapter.jooq.JooqTransferRouteRepository
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionOutcomeUnknown
import com.pianonurmi.etrm.registry.api.InstructionOverdue
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.StatementKind
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.registry.api.TransactionLineStatus
import com.pianonurmi.etrm.registry.api.UpsertStatementFormat
import com.pianonurmi.etrm.registry.api.UpsertTransferRoute
import com.pianonurmi.etrm.registry.application.RegistryDispatchSettings
import com.pianonurmi.etrm.registry.application.RegistryDispatcher
import com.pianonurmi.etrm.registry.application.RegistryStatementService
import com.pianonurmi.etrm.registry.application.StatementFormatService
import com.pianonurmi.etrm.registry.application.TransferRouteService
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.InstructionStatusSource
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import com.pianonurmi.etrm.registry.fixtures.RegistryWiring
import com.pianonurmi.etrm.registry.fixtures.WiredInstructions
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistryInstructionServiceIT {
    /** An API-style gateway whose answers are scripted by the test. */
    class ScriptedApiGateway : RegistryGateway, InstructionStatusSource {
        val results = ArrayDeque<GatewayResult>()
        var statusAnswer: GatewayResult? = null
        val calls = mutableListOf<String>()

        override val channel = "API"

        override val inline = false

        override fun execute(instruction: RegistryInstructionView): GatewayResult {
            calls += instruction.instructionKey
            return results.removeFirstOrNull() ?: GatewayResult.Sent("API-${instruction.instructionKey}")
        }

        override fun status(instruction: RegistryInstructionView): GatewayResult? = statusAnswer
    }

    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private val actor = ActorProvider { Actor("registry-op", "Registry operator", setOf(Role.REGISTRY_OPERATOR)) }
    private var now = Instant.parse("2026-10-12T08:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }
    private val api = ScriptedApiGateway()
    private val outbox = Path.of("build/test-outbox")
    private val settings = RegistryDispatchSettings(unplannedNeedsApproval = false, retryBackoff = Duration.ofMinutes(2))
    private lateinit var lots: LotService
    private lateinit var allowances: AllowanceHoldingService
    private lateinit var routes: TransferRouteService
    private lateinit var instructions: WiredInstructions
    private lateinit var fourEyes: WiredInstructions
    private lateinit var dispatcher: RegistryDispatcher
    private lateinit var statements: RegistryStatementService
    private lateinit var formats: StatementFormatService
    private lateinit var entityId: UUID
    private lateinit var simulatorAccount: UUID
    private lateinit var fingridAccount: UUID
    private lateinit var cesarAccount: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "inventory", "registry", "document")
        val dsl = TestDatabase.dsl
        dsl.execute("UPDATE etrm_referencedata.registry SET integration = 'API' WHERE code = 'CESAR'")
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        val beneficiaries = BeneficiaryService(JooqBeneficiaryRepository(dsl), actor, publisher, clock)
        lots = LotService(JooqLotRepository(dsl), JooqLotEventJournal(dsl), referenceData, taxonomy, criteria, actor, publisher, clock, beneficiaries)
        routes = TransferRouteService(JooqTransferRouteRepository(dsl), referenceData, BigDecimal("0.05"), 3)
        val gateways = listOf(SimulatorRegistryGateway(), ManualRegistryGateway(), FileRegistryGateway(outbox.toString(), referenceData), api)
        val repository = JooqRegistryInstructionRepository(dsl)
        allowances = AllowanceHoldingService(
            JooqAllowanceHoldingRepository(dsl),
            referenceData,
            ProductCatalogueService(JooqAllowanceProductRepository(dsl)),
            actor,
            publisher,
        )

        fun instructionService(dispatch: RegistryDispatchSettings) = RegistryWiring.instructions(
            dsl,
            gateways,
            lots,
            lots,
            referenceData,
            routes,
            actor,
            publisher,
            clock,
            allowances,
            allowances,
            beneficiaries,
            dispatch,
        )
        instructions = instructionService(settings)
        fourEyes = instructionService(settings.copy(unplannedNeedsApproval = true))
        dispatcher = RegistryDispatcher(repository, gateways, instructions.outcomes, DataSourceTransactionManager(TestDatabase.dataSource), settings, clock)
        formats = StatementFormatService(JooqStatementFormatRepository(dsl), referenceData, actor, clock)
        val documents = DocumentService(JooqDocumentRepository(dsl), OpenHtmlPdfRenderer(), actor, publisher, clock)
        val reconciliation =
            ReconciliationService(
                JooqReconciliationRepository(dsl),
                JooqLotRepository(dsl),
                JooqAllowanceHoldingRepository(dsl),
                referenceData,
                actor,
                publisher,
                Clock.systemUTC(),
            )
        statements = RegistryStatementService(formats, repository, instructions.outcomes, reconciliation, referenceData, documents, actor)

        entityId = referenceData.createLegalEntity(CreateLegalEntity("REG-CO", "Registry Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id

        fun account(registry: String, number: String) = referenceData.createRegistryAccount(
            CreateRegistryAccount(registry, number, "$registry holding", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entityId),
        ).id
        simulatorAccount = account("SIMULATOR", "SIM-1")
        fingridAccount = account("FINGRID", "FI-REG-1")
        cesarAccount = account("CESAR", "SE-REG-1")
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

    private fun receive(account: UUID, issuance: String, start: Long, end: Long) = lots.receive(
        ReceiveLots(
            holderEntityId = entityId,
            registryAccountId = account,
            issuanceRef = issuance,
            range = CertificateRange.of(start, end),
            deviceGsrn = "643000000000000016",
            productionPeriod = LocalDateRange.ofMonth(YearMonth.of(2026, 4)),
            issueDate = LocalDate.of(2026, 5, 10),
            issuingBody = "SIM",
            issuingCountry = "FI",
            acquisitionSource = AcquisitionSource.ISSUANCE,
        ),
    )

    @Test
    fun `default routes - same registry free, hub members via hub, others infeasible, overrides win`() {
        routes.route("FINGRID", "FINGRID").feePerMwh shouldBe BigDecimal.ZERO
        routes.route("FINGRID", "HKNR").let {
            it.feasible shouldBe true
            it.feePerMwh shouldBe BigDecimal("0.05")
            it.expectedDays shouldBe 3
        }
        routes.route("FINGRID", "UNION_REGISTRY").feasible shouldBe false
        routes.upsert(UpsertTransferRoute("FINGRID", "HKNR", feasible = true, feePerMwh = BigDecimal("0.10"), expectedDays = 5))
        routes.route("fingrid", "hknr").feePerMwh shouldBe BigDecimal("0.1000")
        routes.routesFrom("FINGRID")["CESAR"]?.feasible shouldBe true
    }

    @Test
    fun `simulator gateway confirms a partial transfer synchronously and the lot leaves inventory`() {
        val lot = receive(simulatorAccount, "SIM-0001", 1, 1_000)
        val instruction = instructions.submitTransfer(SubmitTransfer(lot.id, 400, "SIMULATOR", "SIM-CP-1", idempotencyKey = "transfer-1"))
        instruction.status shouldBe InstructionStatus.CONFIRMED
        instruction.channel shouldBe "SIMULATOR"
        instruction.quantity shouldBe 400
        instruction.externalRef?.startsWith("SIM-") shouldBe true
        lots.requireLot(instruction.requireLot()).status shouldBe LotStatus.TRANSFERRED_OUT
        lots.requireLot(lot.id).status shouldBe LotStatus.SPLIT
        published.filterIsInstance<InstructionConfirmed>().single { it.instruction.id == instruction.id }.lot!!.quantityMwh shouldBe 400
        instructions.submitTransfer(SubmitTransfer(lot.id, 400, "SIMULATOR", "SIM-CP-1", idempotencyKey = "transfer-1")).id shouldBe instruction.id
        val another = receive(simulatorAccount, "SIM-0002", 1, 10)
        shouldThrow<ValidationException> {
            instructions.submitTransfer(SubmitTransfer(another.id, null, "UNION_REGISTRY", "EU-1", idempotencyKey = "bad-route"))
        }
    }

    @Test
    fun `file channel goes through the outbox - dispatched after commit, failure returns the lot, retry and confirmation`() {
        val lot = receive(fingridAccount, "FI-0001", 1, 500)
        val pending = instructions.submitTransfer(SubmitTransfer(lot.id, null, "HKNR", "DE-4711", idempotencyKey = "transfer-2"))
        pending.status shouldBe InstructionStatus.PENDING
        lots.requireLot(lot.id).status shouldBe LotStatus.IN_TRANSFER
        dispatcher.dispatchDue() shouldBe 1
        val sent = instructions.requireInstruction(pending.id)
        sent.status shouldBe InstructionStatus.SENT
        val pack = Files.list(outbox.resolve("FINGRID")).use { files -> files.filter { it.toString().contains("transfer-2") }.findFirst().get() }
        Files.readString(pack).lines()[1].split(",").let { cells ->
            cells[3] shouldBe "FI-REG-1"
            cells[5] shouldBe "DE-4711"
        }
        instructions.open("FINGRID").map { it.id } shouldBe listOf(sent.id)
        instructions.fail(sent.id, "rejected by registry").status shouldBe InstructionStatus.FAILED
        lots.requireLot(lot.id).status shouldBe LotStatus.HELD
        instructions.retry(sent.id).status shouldBe InstructionStatus.PENDING
        dispatcher.dispatchDue() shouldBe 1
        instructions.requireInstruction(sent.id).let {
            it.status shouldBe InstructionStatus.SENT
            it.attempts shouldBe 2
        }
        val confirmed = instructions.confirm(sent.id, "HUB-TX-99")
        confirmed.status shouldBe InstructionStatus.CONFIRMED
        confirmed.confirmedBy shouldBe "registry-op"
        lots.requireLot(lot.id).status shouldBe LotStatus.TRANSFERRED_OUT

        val other = receive(fingridAccount, "FI-0002", 1, 100)
        val details = CancellationDetails(CancellationPurpose.DISCLOSURE, "Kuluttaja, Oy", null, "FI", LocalDateRange.ofYear(2026))
        val cancellation = instructions.submitCancellation(SubmitCancellation(other.id, 40, details, idempotencyKey = "cancel-1"))
        dispatcher.dispatchDue() shouldBe 1
        instructions.requireInstruction(cancellation.id).status shouldBe InstructionStatus.SENT
        instructions.confirm(cancellation.id, "CXL-1")
        val cancelledLot = lots.requireLot(cancellation.requireLot())
        cancelledLot.status shouldBe LotStatus.CANCELLED
        cancelledLot.cancellation?.registryCancellationRef shouldBe "CXL-1"
        cancelledLot.quantityMwh shouldBe 40
    }

    @Test
    fun `api channel - transient failures back off, unknown outcomes are resolved by status query, lost leases become unknown`() {
        val lot = receive(cesarAccount, "SE-0001", 1, 300)
        api.results += GatewayResult.Retry("connection refused")
        api.results += GatewayResult.Unknown("read timed out")
        val instruction = instructions.submitTransfer(SubmitTransfer(lot.id, null, "CESAR", "SE-CP-1", idempotencyKey = "api-1"))
        dispatcher.dispatchDue() shouldBe 1
        instructions.requireInstruction(instruction.id).let {
            it.status shouldBe InstructionStatus.PENDING
            it.attempts shouldBe 1
            it.nextAttemptAt shouldBe now.plus(Duration.ofMinutes(2))
        }
        dispatcher.dispatchDue() shouldBe 0
        now = now.plus(Duration.ofMinutes(3))
        dispatcher.dispatchDue() shouldBe 1
        instructions.requireInstruction(instruction.id).status shouldBe InstructionStatus.UNKNOWN
        published.filterIsInstance<InstructionOutcomeUnknown>().single { it.instruction.id == instruction.id }.reason shouldBe "read timed out"
        dispatcher.resolveUnknown() shouldBe 0
        api.statusAnswer = GatewayResult.Confirmed("CESAR-TX-1")
        dispatcher.resolveUnknown() shouldBe 1
        instructions.requireInstruction(instruction.id).let {
            it.status shouldBe InstructionStatus.CONFIRMED
            it.externalRef shouldBe "CESAR-TX-1"
        }
        api.calls.count { it == "api-1" } shouldBe 2
        api.statusAnswer = null

        val second = instructions.submitTransfer(SubmitTransfer(receive(cesarAccount, "SE-0002", 1, 50).id, null, "CESAR", "SE-CP-1", idempotencyKey = "api-2"))
        // A dispatcher claims the instruction and dies before reporting back.
        JooqRegistryInstructionRepository(TestDatabase.dsl).claimDue(setOf("API"), now, now.plus(Duration.ofMinutes(5)), 10) shouldBe listOf(second.id)
        dispatcher.recoverExpiredLeases() shouldBe 0
        now = now.plus(Duration.ofMinutes(6))
        dispatcher.recoverExpiredLeases() shouldBe 1
        instructions.requireInstruction(second.id).status shouldBe InstructionStatus.UNKNOWN
        lots.requireLot(second.requireLot()).status shouldBe LotStatus.IN_TRANSFER
    }

    @Test
    fun `manual instructions need a second person and overdue ones are reported once`() {
        val lot = receive(simulatorAccount, "SIM-0100", 1, 100)
        val waiting = fourEyes.submitTransfer(SubmitTransfer(lot.id, null, "SIMULATOR", "SIM-CP-2", idempotencyKey = "four-eyes-1"))
        waiting.status shouldBe InstructionStatus.PENDING_APPROVAL
        lots.requireLot(lot.id).status shouldBe LotStatus.IN_TRANSFER
        shouldThrow<SegregationOfDutiesException> { fourEyes.approve(waiting.id, "registry-op") }
        fourEyes.approve(waiting.id, "supervisor").let {
            it.status shouldBe InstructionStatus.CONFIRMED
            it.approvedBy shouldBe "supervisor"
        }
        val rejected = fourEyes.submitTransfer(
            SubmitTransfer(receive(simulatorAccount, "SIM-0101", 1, 10).id, null, "SIMULATOR", "X", idempotencyKey = "four-eyes-2"),
        )
        fourEyes.cancel(rejected.id, "rejected: wrong account").status shouldBe InstructionStatus.CANCELLED
        lots.requireLot(rejected.requireLot()).status shouldBe LotStatus.HELD

        val manual = instructions.submitTransfer(
            SubmitTransfer(receive(fingridAccount, "FI-0200", 1, 20).id, null, "FINGRID", "FI-CP-9", idempotencyKey = "overdue-1"),
        )
        dispatcher.dispatchDue()
        instructions.requireInstruction(manual.id).status shouldBe InstructionStatus.SENT

        fun overdueReports() = published.filterIsInstance<InstructionOverdue>().count { it.instruction.id == manual.id }
        instructions.flagOverdue(now.plus(Duration.ofHours(10)))
        overdueReports() shouldBe 0 // the FILE channel allows 72 hours
        instructions.flagOverdue(now.plus(Duration.ofHours(80)))
        overdueReports() shouldBe 1
        instructions.flagOverdue(now.plus(Duration.ofHours(90)))
        overdueReports() shouldBe 1
    }

    @Test
    fun `statement formats are data, and statements confirm instructions`() {
        val generic = statements.parseHoldings(
            fingridAccount,
            LocalDate.of(2026, 6, 1),
            "fingrid.csv",
            """
            # Fingrid export
            issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end,label
            FI-0001,1,500,643000000000000016,2026-04-01,2026-05-01,EKO
            "FI-0003",10,19,,,,
            """.trimIndent(),
        )
        generic.lines shouldHaveSize 2
        generic.lines[0].productionPeriod shouldBe LocalDateRange.ofMonth(YearMonth.of(2026, 4))
        generic.lines[0].raw shouldBe mapOf("label" to "EKO")
        generic.lines[1].deviceGsrn shouldBe null
        shouldThrow<ValidationException> { statements.parseHoldings(fingridAccount, LocalDate.of(2026, 6, 1), "x.csv", "foo,bar\n1,2") }

        shouldThrow<ValidationException> {
            formats.upsert(UpsertStatementFormat("FINGRID", "BROKEN", StatementKind.GOO_HOLDINGS, columnMapping = mapOf("deviceGsrn" to "GSRN")))
        }
        formats.upsert(
            UpsertStatementFormat(
                registryCode = "FINGRID",
                profileCode = "FINEXTRA_EXPORT",
                kind = StatementKind.GOO_HOLDINGS,
                delimiter = ';',
                skipLines = 2,
                dateFormat = "dd.MM.yyyy",
                columnMapping = mapOf(
                    "issuanceRef" to "Myöntötunnus",
                    "certRange" to "Sertifikaatit",
                    "deviceGsrn" to "Laitos",
                    "productionStart" to "Alku",
                    "productionEnd" to "Loppu",
                ),
            ),
        )
        formats.resolve("FINGRID", StatementKind.GOO_HOLDINGS).profileCode shouldBe "FINEXTRA_EXPORT"
        formats.resolve("HKNR", StatementKind.GOO_HOLDINGS).profileCode shouldBe "GENERIC"

        // A FILE instruction is out with the registry; the next holdings statement no longer shows its certificates.
        val leaving = receive(fingridAccount, "FI-0300", 1, 100)
        val staying = receive(fingridAccount, "FI-0301", 1, 60)
        val instruction = instructions.submitTransfer(SubmitTransfer(leaving.id, null, "HKNR", "DE-1", idempotencyKey = "stmt-confirm-1"))
        dispatcher.dispatchDue()
        val statement = statements.importHoldings(
            fingridAccount,
            LocalDate.of(2026, 10, 12),
            "finextra.csv",
            "Finextra holdings\nGenerated 12.10.2026\nMyöntötunnus;Sertifikaatit;Laitos;Alku;Loppu\nFI-0301;1-60;643000000000000016;01.04.2026;01.05.2026\n",
        )
        statement.breaks.filter { it.lotId == leaving.id } shouldHaveSize 0
        val left = published.filterIsInstance<InTransferLotLeftAccount>().single { it.lotId == leaving.id }
        left.instructionRef shouldBe instruction.id
        instructions.on(left)
        instructions.requireInstruction(instruction.id).let {
            it.status shouldBe InstructionStatus.CONFIRMED
            it.confirmedBy shouldBe "statement:2026-10-12"
        }
        lots.requireLot(staying.id).status shouldBe LotStatus.HELD

        // A transaction export confirms by our client reference and reports what it could not match.
        val viaExport = instructions.submitTransfer(
            SubmitTransfer(receive(fingridAccount, "FI-0400", 1, 30).id, null, "HKNR", "DE-2", idempotencyKey = "tx-export-1"),
        )
        dispatcher.dispatchDue()
        val report = statements.importTransactions(
            "FINGRID",
            "fingrid-transactions.csv",
            "transaction_id,type,issuance_ref,cert_no_start,cert_no_end,status,date,client_reference\n" +
                "FG-777,Transfer,FI-0400,1,30,Completed,2026-10-12,tx-export-1\n" +
                "FG-778,Transfer,FI-9999,1,5,Completed,2026-10-12,\n",
        )
        report.confirmed shouldBe listOf(viaExport.id)
        report.unmatched.map { it.externalRef } shouldBe listOf("FG-778")
        report.unmatched.single().status shouldBe TransactionLineStatus.COMPLETED
        report.documentId shouldNotBe null
        instructions.requireInstruction(viaExport.id).externalRef shouldBe "FG-777"
    }
}
