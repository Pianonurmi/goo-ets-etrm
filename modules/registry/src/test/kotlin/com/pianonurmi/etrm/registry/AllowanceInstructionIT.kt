package com.pianonurmi.etrm.registry

import com.pianonurmi.etrm.document.adapter.jooq.JooqDocumentRepository
import com.pianonurmi.etrm.document.adapter.pdf.OpenHtmlPdfRenderer
import com.pianonurmi.etrm.document.application.DocumentService
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqAllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqBeneficiaryRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotEventJournal
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqReconciliationRepository
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.inventory.api.StatementKind
import com.pianonurmi.etrm.inventory.api.StatementStatus
import com.pianonurmi.etrm.inventory.application.AllowanceHoldingService
import com.pianonurmi.etrm.inventory.application.BeneficiaryService
import com.pianonurmi.etrm.inventory.application.LotService
import com.pianonurmi.etrm.inventory.application.ReconciliationService
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import com.pianonurmi.etrm.product.application.TaxonomySnapshots
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.registry.adapter.gateway.InstructionPack
import com.pianonurmi.etrm.registry.adapter.gateway.ManualRegistryGateway
import com.pianonurmi.etrm.registry.adapter.gateway.SimulatorRegistryGateway
import com.pianonurmi.etrm.registry.adapter.jooq.JooqRegistryInstructionRepository
import com.pianonurmi.etrm.registry.adapter.jooq.JooqStatementFormatRepository
import com.pianonurmi.etrm.registry.adapter.jooq.JooqTransferRouteRepository
import com.pianonurmi.etrm.registry.api.AssetKind
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.SubmitAllowanceTransfer
import com.pianonurmi.etrm.registry.api.SubmitSurrender
import com.pianonurmi.etrm.registry.application.RegistryDispatchSettings
import com.pianonurmi.etrm.registry.application.RegistryInstructionService
import com.pianonurmi.etrm.registry.application.RegistryStatementService
import com.pianonurmi.etrm.registry.application.StatementFormatService
import com.pianonurmi.etrm.registry.application.TransferRouteService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Union Registry allowance transfers and surrenders through the manual gateway, and balance reconciliation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllowanceInstructionIT {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private var user = "registry-op"
    private val actor = ActorProvider { Actor(user, user, setOf(Role.REGISTRY_OPERATOR)) }
    private val now = Instant.parse("2026-10-12T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var allowances: AllowanceHoldingService
    private lateinit var instructions: RegistryInstructionService
    private lateinit var statements: RegistryStatementService
    private lateinit var entityId: UUID
    private lateinit var ourAccount: UUID
    private lateinit var ourSecondAccount: UUID
    private lateinit var untrustedBuyer: UUID
    private lateinit var trustedBuyer: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "inventory", "registry", "document")
        val dsl = TestDatabase.dsl
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        val beneficiaries = BeneficiaryService(JooqBeneficiaryRepository(dsl), actor, publisher, clock)
        val lots = LotService(JooqLotRepository(dsl), JooqLotEventJournal(dsl), referenceData, taxonomy, criteria, actor, publisher, clock, beneficiaries)
        allowances = AllowanceHoldingService(
            JooqAllowanceHoldingRepository(dsl),
            referenceData,
            ProductCatalogueService(JooqAllowanceProductRepository(dsl)),
            actor,
            publisher,
        )
        val routes = TransferRouteService(JooqTransferRouteRepository(dsl), referenceData, BigDecimal("0.05"), 3)
        val repository = JooqRegistryInstructionRepository(dsl)
        instructions = RegistryInstructionService(
            repository,
            listOf(SimulatorRegistryGateway(), ManualRegistryGateway()),
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
            RegistryDispatchSettings(unplannedNeedsApproval = true),
        )
        val formats = StatementFormatService(JooqStatementFormatRepository(dsl), referenceData, actor, clock)
        val reconciliation = ReconciliationService(
            JooqReconciliationRepository(dsl),
            JooqLotRepository(dsl),
            JooqAllowanceHoldingRepository(dsl),
            referenceData,
            actor,
            publisher,
        )
        val documents = DocumentService(JooqDocumentRepository(dsl), OpenHtmlPdfRenderer(), actor, publisher, clock)
        statements = RegistryStatementService(formats, repository, instructions, reconciliation, referenceData, documents, actor)

        entityId = referenceData.createLegalEntity(CreateLegalEntity("ETS-CO", "Allowance Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id

        fun own(number: String) = referenceData.createRegistryAccount(
            CreateRegistryAccount("UNION_REGISTRY", number, "Trading $number", RegistryAccountType.TRADING, HolderType.LEGAL_ENTITY, entityId),
        ).id
        ourAccount = own("EU-100-1001")
        ourSecondAccount = own("EU-100-1002")
        val buyer = referenceData.createCounterparty(CreateCounterparty("ETS-BUYER", "Buyer AG", "DE")).id
        // Counterparty accounts normally arrive through a four-eyes change request; inserted directly here.
        untrustedBuyer = counterpartyAccount(buyer, "EU-100-2001", trustedSince = null)
        trustedBuyer = counterpartyAccount(buyer, "EU-100-2002", trustedSince = LocalDate.of(2026, 9, 1))

        allowances.record(
            RecordAllowanceMovement(entityId, ourAccount, "EUA", AllowanceMovementType.OPENING_BALANCE, 1_000, LocalDate.of(2026, 1, 1)),
        )
    }

    private fun counterpartyAccount(counterpartyId: UUID, number: String, trustedSince: LocalDate?): UUID {
        val id = UUID.randomUUID()
        TestDatabase.dsl.execute(
            """
            INSERT INTO etrm_referencedata.registry_account
                (id, registry_id, account_number, name, account_type, holder_type, holder_id, can_export, trusted, trusted_since)
            SELECT ?, id, ?, 'Buyer trading', 'TRADING', 'COUNTERPARTY', ?, FALSE, ?, ?
            FROM etrm_referencedata.registry WHERE code = 'UNION_REGISTRY'
            """.trimIndent(),
            id,
            number,
            counterpartyId,
            trustedSince != null,
            trustedSince,
        )
        return id
    }

    private fun eua() = allowances.holding(ourAccount, "EUA")!!

    @org.junit.jupiter.api.BeforeEach
    fun operator() {
        user = "registry-op"
    }

    @Test
    fun `a sale to an untrusted account waits 26 hours, needs a second approver and books the sale on confirmation`() {
        val before = eua()
        val sale = instructions.submitAllowanceTransfer(
            SubmitAllowanceTransfer(
                fromAccountId = ourAccount,
                productCode = "eua",
                quantity = 300,
                toAccountId = untrustedBuyer,
                idempotencyKey = "eua-sale-1",
                movementType = AllowanceMovementType.SALE,
                unitPrice = BigDecimal("70.50"),
                priceCurrency = "eur",
            ),
        )
        sale.status shouldBe InstructionStatus.PENDING_APPROVAL
        sale.assetKind shouldBe AssetKind.ALLOWANCE
        sale.type shouldBe InstructionType.ALLOWANCE_TRANSFER
        sale.unit shouldBe "TCO2E"
        sale.productCode shouldBe "EUA"
        sale.toAccountRef shouldBe "EU-100-2001"
        sale.expectedEffectiveAt shouldBe now.plus(Duration.ofHours(26))
        eua().reservedQuantity shouldBe before.reservedQuantity + 300
        instructions.submitAllowanceTransfer(
            SubmitAllowanceTransfer(ourAccount, "EUA", 300, untrustedBuyer, "eua-sale-1", AllowanceMovementType.SALE, BigDecimal("70.50")),
        ).id shouldBe sale.id

        val pack = InstructionPack.csv(sale, "EU-100-1001")
        pack shouldContain "EU-100-2001"
        pack shouldContain ",EUA,300,TCO2E,"
        pack shouldContain "2026-10-13T10:00:00Z"

        shouldThrow<SegregationOfDutiesException> { instructions.approve(sale.id, "registry-op") }
        val sent = instructions.approve(sale.id, "registry-head")
        sent.status shouldBe InstructionStatus.SENT
        sent.channel shouldBe "MANUAL"

        val confirmed = instructions.confirm(sale.id, "EU-TX-0001")
        confirmed.status shouldBe InstructionStatus.CONFIRMED
        eua().quantity shouldBe before.quantity - 300
        eua().reservedQuantity shouldBe before.reservedQuantity
        val movement = allowances.movements(eua().id).last()
        movement.type shouldBe AllowanceMovementType.SALE
        movement.costPerUnit!!.compareTo(BigDecimal("70.50")) shouldBe 0
        movement.costCurrency shouldBe "EUR"
        movement.reference shouldBe "EU-TX-0001"
        published.filterIsInstance<InstructionConfirmed>().single { it.instruction.id == sale.id }.let {
            it.lot shouldBe null
            it.holding!!.quantity shouldBe before.quantity - 300
        }
    }

    @Test
    fun `trusted and own accounts settle at once and an internal transfer lands on the other account`() {
        val trusted = instructions.submitAllowanceTransfer(SubmitAllowanceTransfer(ourAccount, "EUA", 10, trustedBuyer, "eua-trusted"))
        trusted.expectedEffectiveAt shouldBe now
        instructions.cancel(trusted.id, "not needed")

        val internal = instructions.submitAllowanceTransfer(SubmitAllowanceTransfer(ourAccount, "EUA", 50, ourSecondAccount, "eua-internal"))
        internal.expectedEffectiveAt shouldBe now
        instructions.approve(internal.id, "registry-head")
        instructions.confirm(internal.id, "EU-TX-0002")
        val moved = allowances.holding(ourSecondAccount, "EUA")!!
        moved.quantity shouldBe 50
        allowances.movements(moved.id).single().type shouldBe AllowanceMovementType.TRANSFER_IN
    }

    @Test
    fun `a failed surrender releases the reservation and a confirmed one records the compliance year`() {
        val before = eua()
        val first = instructions.submitSurrender(SubmitSurrender(ourAccount, "EUA", 40, 2025, "surrender-2025-a"))
        first.type shouldBe InstructionType.SURRENDER
        eua().reservedQuantity shouldBe before.reservedQuantity + 40
        instructions.approve(first.id, "registry-head")
        instructions.fail(first.id, "Registry rejected: account blocked")
        eua().reservedQuantity shouldBe before.reservedQuantity

        val second = instructions.submitSurrender(SubmitSurrender(ourAccount, "EUA", 40, 2025, "surrender-2025-b"))
        instructions.approve(second.id, "registry-head")
        instructions.confirm(second.id, "EU-SUR-1")
        eua().quantity shouldBe before.quantity - 40
        allowances.movements(eua().id).last().let {
            it.type shouldBe AllowanceMovementType.SURRENDER
            it.complianceYear shouldBe 2025
        }
    }

    @Test
    fun `invalid allowance instructions are refused before anything is reserved`() {
        val reserved = eua().reservedQuantity
        shouldThrow<ValidationException> {
            instructions.submitAllowanceTransfer(
                SubmitAllowanceTransfer(ourAccount, "EUA", 5, untrustedBuyer, "no-price", AllowanceMovementType.SALE),
            )
        }
        shouldThrow<ValidationException> {
            instructions.submitAllowanceTransfer(SubmitAllowanceTransfer(untrustedBuyer, "EUA", 5, ourAccount, "not-ours"))
        }
        shouldThrow<ValidationException> {
            instructions.submitAllowanceTransfer(SubmitAllowanceTransfer(ourAccount, "EUA", 5, ourAccount, "same-account"))
        }
        shouldThrow<ConflictException> {
            instructions.submitAllowanceTransfer(SubmitAllowanceTransfer(ourAccount, "EUA", 1_000_000, untrustedBuyer, "too-many"))
        }
        eua().reservedQuantity shouldBe reserved
    }

    @Test
    fun `allowance holdings statements reconcile balances per product`() {
        user = "back-office"
        val held = eua()
        val agreed = statements.importHoldings(ourAccount, LocalDate.of(2026, 10, 11), "ur-1.csv", "product_code,quantity\nEUA,${held.quantity}\n")
        agreed.kind shouldBe StatementKind.ALLOWANCE
        agreed.status shouldBe StatementStatus.RECONCILED

        val off = statements.importHoldings(
            ourAccount,
            LocalDate.of(2026, 10, 12),
            "ur-2.csv",
            "product_code,quantity\nEUA,${held.quantity - 100}\nEUAA,25\n",
        )
        off.status shouldBe StatementStatus.BREAKS
        off.breaks shouldHaveSize 2
        off.breaks.first { it.productCode == "EUA" }.let {
            it.type shouldBe BreakType.BALANCE_MISMATCH
            it.registryQuantity shouldBe held.quantity - 100
            it.internalQuantity shouldBe held.quantity
            it.range shouldBe null
        }
        off.breaks.first { it.productCode == "EUAA" }.internalQuantity shouldBe 0
    }
}
