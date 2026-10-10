package com.pianonurmi.etrm.registry

import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotEventJournal
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotRepository
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CancellationPurpose
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.application.LotService
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
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
import com.pianonurmi.etrm.registry.adapter.jooq.JooqTransferRouteRepository
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.registry.api.UpsertTransferRoute
import com.pianonurmi.etrm.registry.application.CsvStatementParser
import com.pianonurmi.etrm.registry.application.RegistryInstructionService
import com.pianonurmi.etrm.registry.application.TransferRouteService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistryInstructionServiceIT {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private val actor = ActorProvider { Actor("registry-op", "Registry operator", setOf(Role.REGISTRY_OPERATOR)) }
    private lateinit var lots: LotService
    private lateinit var routes: TransferRouteService
    private lateinit var instructions: RegistryInstructionService
    private lateinit var entityId: UUID
    private lateinit var simulatorAccount: UUID
    private lateinit var fingridAccount: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "inventory", "registry")
        val dsl = TestDatabase.dsl
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        lots = LotService(JooqLotRepository(dsl), JooqLotEventJournal(dsl), referenceData, taxonomy, criteria, actor, publisher, Clock.systemUTC())
        routes = TransferRouteService(JooqTransferRouteRepository(dsl), referenceData, BigDecimal("0.05"), 3)
        instructions = RegistryInstructionService(
            JooqRegistryInstructionRepository(dsl),
            listOf(SimulatorRegistryGateway(), ManualRegistryGateway(), FileRegistryGateway("build/test-outbox")),
            lots,
            lots,
            referenceData,
            routes,
            actor,
            publisher,
            Clock.systemUTC(),
        )
        entityId = referenceData.createLegalEntity(CreateLegalEntity("REG-CO", "Registry Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        simulatorAccount = referenceData.createRegistryAccount(
            CreateRegistryAccount("SIMULATOR", "SIM-1", "Simulator holding", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entityId),
        ).id
        fingridAccount = referenceData.createRegistryAccount(
            CreateRegistryAccount("FINGRID", "FI-REG-1", "Fingrid holding", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entityId),
        ).id
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
        instruction.quantityMwh shouldBe 400
        instruction.externalRef?.startsWith("SIM-") shouldBe true
        lots.requireLot(instruction.lotId).status shouldBe LotStatus.TRANSFERRED_OUT
        lots.requireLot(lot.id).status shouldBe LotStatus.SPLIT
        published.filterIsInstance<InstructionConfirmed>().single { it.instruction.id == instruction.id }.lot.quantityMwh shouldBe 400
        instructions.submitTransfer(SubmitTransfer(lot.id, 400, "SIMULATOR", "SIM-CP-1", idempotencyKey = "transfer-1")).id shouldBe instruction.id
        val another = receive(simulatorAccount, "SIM-0002", 1, 10)
        shouldThrow<ValidationException> {
            instructions.submitTransfer(SubmitTransfer(another.id, null, "UNION_REGISTRY", "EU-1", idempotencyKey = "bad-route"))
        }
    }

    @Test
    fun `file gateway sends and waits for confirmation, failure returns the lot, cancellation retires it`() {
        val lot = receive(fingridAccount, "FI-0001", 1, 500)
        val sent = instructions.submitTransfer(SubmitTransfer(lot.id, null, "HKNR", "DE-4711", idempotencyKey = "transfer-2"))
        sent.status shouldBe InstructionStatus.SENT
        lots.requireLot(lot.id).status shouldBe LotStatus.IN_TRANSFER
        instructions.open("FINGRID").map { it.id } shouldBe listOf(sent.id)
        val failed = instructions.fail(sent.id, "rejected by registry")
        failed.status shouldBe InstructionStatus.FAILED
        lots.requireLot(lot.id).status shouldBe LotStatus.HELD
        val retried = instructions.retry(sent.id)
        retried.status shouldBe InstructionStatus.SENT
        retried.attempts shouldBe 2
        val confirmed = instructions.confirm(sent.id, "HUB-TX-99")
        confirmed.status shouldBe InstructionStatus.CONFIRMED
        confirmed.confirmedBy shouldBe "registry-op"
        lots.requireLot(lot.id).status shouldBe LotStatus.TRANSFERRED_OUT

        val other = receive(fingridAccount, "FI-0002", 1, 100)
        val details = CancellationDetails(CancellationPurpose.DISCLOSURE, "Kuluttaja Oy", null, "FI", LocalDateRange.ofYear(2026))
        val cancellation = instructions.submitCancellation(SubmitCancellation(other.id, 40, details, idempotencyKey = "cancel-1"))
        cancellation.status shouldBe InstructionStatus.SENT
        instructions.confirm(cancellation.id, "CXL-1")
        val cancelledLot = lots.requireLot(cancellation.lotId)
        cancelledLot.status shouldBe LotStatus.CANCELLED
        cancelledLot.cancellation?.registryCancellationRef shouldBe "CXL-1"
        cancelledLot.quantityMwh shouldBe 40
    }

    @Test
    fun `csv statements parse into structured lines`() {
        val parser = CsvStatementParser()
        val statement = parser.parse(
            fingridAccount,
            LocalDate.of(2026, 6, 1),
            "fingrid.csv",
            """
            # Fingrid export
            issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end,label
            FI-0001,1,500,643000000000000016,2026-04-01,2026-05-01,EKO
            FI-0003,10,19,,,,
            """.trimIndent(),
        )
        statement.lines shouldHaveSize 2
        statement.lines[0].productionPeriod shouldBe LocalDateRange.ofMonth(YearMonth.of(2026, 4))
        statement.lines[0].raw shouldBe mapOf("label" to "EKO")
        statement.lines[1].deviceGsrn shouldBe null
        shouldThrow<ValidationException> { parser.parse(fingridAccount, LocalDate.of(2026, 6, 1), "x.csv", "foo,bar\n1,2") }
    }
}
