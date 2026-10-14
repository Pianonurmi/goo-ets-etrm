package com.pianonurmi.etrm.inventory

import com.pianonurmi.etrm.inventory.adapter.jooq.JooqAllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotEventJournal
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqReconciliationRepository
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.BreakAction
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CancellationPurpose
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.HoldingStamp
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.LotCorrection
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakResolved
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.inventory.api.StatementStatus
import com.pianonurmi.etrm.inventory.application.AllowanceHoldingService
import com.pianonurmi.etrm.inventory.application.LotService
import com.pianonurmi.etrm.inventory.application.ReconciliationService
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.api.criteria.Criterion
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
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryServiceIT {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published += it }
    private val actor = ActorProvider { Actor("tester", "Tester", setOf(com.pianonurmi.etrm.platform.security.Role.BACK_OFFICE), allEntities = true) }
    private lateinit var lots: LotService
    private lateinit var holdings: AllowanceHoldingService
    private lateinit var reconciliation: ReconciliationService
    private lateinit var criteria: CriteriaServiceImpl
    private lateinit var entityId: UUID
    private lateinit var gooAccountId: UUID
    private lateinit var etsAccountId: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "inventory")
        val dsl = TestDatabase.dsl
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), publisher)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, publisher)
        criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        lots = LotService(JooqLotRepository(dsl), JooqLotEventJournal(dsl), referenceData, taxonomy, criteria, actor, publisher, Clock.systemUTC())
        holdings = AllowanceHoldingService(
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
        )

        entityId = referenceData.createLegalEntity(CreateLegalEntity("INV-CO", "Inventory Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        gooAccountId = referenceData.createRegistryAccount(
            CreateRegistryAccount("FINGRID", "FI-GOO-1", "GoO holding", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entityId),
        ).id
        etsAccountId = referenceData.createRegistryAccount(
            CreateRegistryAccount("UNION_REGISTRY", "EU-100-5000", "Trading account", RegistryAccountType.TRADING, HolderType.LEGAL_ENTITY, entityId),
        ).id
        referenceData.createProductionDevice(
            CreateProductionDevice(
                gsrn = "643000000000000016",
                name = "Tuulipuisto",
                country = "FI",
                capacityKw = BigDecimal("45000"),
                commissioningDate = LocalDate.of(2021, 6, 1),
                energySourceCode = "WIND_ONSHORE",
                technologyCode = "WIND_TURBINE_ONSHORE",
                labelCodes = setOf("EKOENERGY"),
                registryCode = "FINGRID",
                ownerType = OwnerType.LEGAL_ENTITY,
                ownerId = entityId,
                ownDevice = true,
            ),
        )
    }

    private fun receive(
        issuance: String,
        start: Long,
        end: Long,
        month: YearMonth = YearMonth.of(2026, 3),
        labels: Set<String> = setOf("EKOENERGY"),
        effectiveAt: Instant? = null,
    ) = lots.receive(
        ReceiveLots(
            holderEntityId = entityId,
            registryAccountId = gooAccountId,
            issuanceRef = issuance,
            range = CertificateRange.of(start, end),
            deviceGsrn = "643000000000000016",
            productionPeriod = LocalDateRange.ofMonth(month),
            issueDate = month.plusMonths(1).atDay(15),
            issuingBody = "FINEXTRA",
            issuingCountry = "FI",
            supportProduction = false,
            supportInvestment = false,
            labels = labels,
            acquisitionSource = AcquisitionSource.ISSUANCE,
            costBasisPerMwh = BigDecimal("1.25"),
            costCurrency = "EUR",
            effectiveAt = effectiveAt,
        ),
    )

    @Test
    fun `receive, split, reserve, release, transfer and cancel with a verifiable journal`() {
        val lot = receive("FI-2026-0001", 1_000, 1_999)
        lot.quantityMwh shouldBe 1000
        lot.expiryDate shouldBe LocalDate.of(2027, 4, 1)
        lot.energySourcePath shouldBe "RENEWABLE.WIND.WIND_ONSHORE"
        lot.deviceCommissioningDate shouldBe LocalDate.of(2021, 6, 1)

        val reservationRef = UUID.randomUUID()
        val reserved = lots.reserve(lot.id, 300, reservationRef)
        reserved.status shouldBe LotStatus.RESERVED
        reserved.range shouldBe CertificateRange.of(1_000, 1_299)
        lots.requireLot(lot.id).status shouldBe LotStatus.SPLIT
        val remainder = lots.lots(LotFilter(holderEntityId = entityId, statuses = setOf(LotStatus.HELD))).single { it.rootLotId == lot.id }
        remainder.range shouldBe CertificateRange.of(1_300, 1_999)

        shouldThrow<ConflictException> { lots.release(reserved.id, UUID.randomUUID()) }
        lots.release(reserved.id, reservationRef).status shouldBe LotStatus.HELD

        val instruction = UUID.randomUUID()
        lots.startTransfer(reserved.id, instruction).status shouldBe LotStatus.IN_TRANSFER
        lots.failTransfer(reserved.id, instruction, "registry rejected").status shouldBe LotStatus.HELD
        lots.startTransfer(reserved.id, instruction)
        lots.confirmTransferOut(reserved.id, instruction, "TX-1").status shouldBe LotStatus.TRANSFERRED_OUT
        shouldThrow<IllegalStateTransitionException> { lots.reserve(reserved.id, null, UUID.randomUUID()) }

        val cancelled = lots.cancel(
            remainder.id,
            200,
            CancellationDetails(CancellationPurpose.DISCLOSURE, "Asiakas Oy", "FI12345678", "fi", LocalDateRange.ofYear(2026)),
        )
        cancelled.status shouldBe LotStatus.CANCELLED
        cancelled.quantityMwh shouldBe 200
        cancelled.cancellation?.consumptionCountry shouldBe "FI"

        lots.history(reserved.id).map { it.eventType } shouldBe
            listOf("LotReceived", "LotReserved", "LotReleased", "LotTransferStarted", "LotTransferFailed", "LotTransferStarted", "LotTransferred")
        lots.verifyJournal().shouldBeEmpty()
        lots.history(reserved.id).zipWithNext().forEach { (a, b) -> b.prevHash shouldBe a.hash }
    }

    @Test
    fun `the holding stamp changes with every change to the holder's lots, and only with those`() {
        val before = lots.holdingStamp(entityId)
        val lot = receive("FI-2026-0099", 90_000, 90_099)
        val received = lots.holdingStamp(entityId)
        received.lots shouldBe before.lots + 1
        received.mwh shouldBe before.mwh + 100
        lots.holdingStamp(UUID.randomUUID()) shouldBe HoldingStamp(0, 0, 0)
        lots.reserve(lot.id, 10, UUID.randomUUID())
        // The reservation splits the lot into a reserved part and a remainder; the original's version moves on.
        lots.holdingStamp(entityId).let {
            it.lots shouldBeGreaterThan received.lots
            it.versions shouldBeGreaterThan received.versions
            it shouldNotBe received
        }
    }

    @Test
    fun `overlapping live ranges from the same issuance are rejected by the database`() {
        receive("FI-2026-0002", 5_000, 5_999)
        shouldThrow<DataAccessException> { receive("FI-2026-0002", 5_500, 6_500) }
        receive("FI-2026-0003", 5_500, 6_500) shouldNotBe null
    }

    @Test
    fun `criteria search compiles to SQL over lot columns`() {
        val target = receive("FI-2026-0010", 10_000, 10_099, month = YearMonth.of(2026, 5))
        receive("FI-2026-0011", 20_000, 20_099, month = YearMonth.of(2025, 5), labels = emptySet())
        val ctx = criteria.contextFor(LocalDate.of(2026, 12, 1))
        val nordicWind2026 = criteria.bind(Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("WIND"), Criterion.productionYear(2026)), ctx)
        val found = lots.search(entityId, nordicWind2026)
        found.map { it.id }.contains(target.id) shouldBe true
        found.all { it.productionPeriod.start.year == 2026 } shouldBe true
        val ekoOnly = criteria.bind(Criterion.label("EKOENERGY"), ctx)
        lots.search(entityId, ekoOnly).none { it.issuanceRef == "FI-2026-0011" } shouldBe true
        val hydro = criteria.bind(Criterion.energySource("HYDRO"), ctx)
        lots.search(entityId, hydro).shouldBeEmpty()
    }

    @Test
    fun `corrections are bitemporal and expiry sweep closes due lots`() {
        val lot = receive("FI-2026-0020", 30_000, 30_049, month = YearMonth.of(2025, 1), effectiveAt = Instant.parse("2025-02-15T10:00:00Z"))
        val before = Instant.now()
        Thread.sleep(5)
        val effective = Instant.parse("2026-01-01T00:00:00Z")
        val corrected = lots.correct(
            lot.id,
            LotCorrection(labels = setOf("EKOENERGY", "BRA_MILJOVAL"), reason = "label certificate received", effectiveAt = effective),
        )
        corrected.version shouldBe 2
        (lots.asOf(lot.id, Instant.now(), before)?.get("labels") as List<*>) shouldBe listOf("EKOENERGY")
        (lots.asOf(lot.id, Instant.now(), Instant.now())?.get("labels") as List<*>) shouldBe listOf("BRA_MILJOVAL", "EKOENERGY")
        (lots.asOf(lot.id, Instant.parse("2025-12-01T00:00:00Z"), Instant.now())?.get("labels") as List<*>) shouldBe listOf("EKOENERGY")
        lots.asOf(lot.id, Instant.parse("2025-01-01T00:00:00Z"), Instant.now()) shouldBe null

        lot.expiryDate shouldBe LocalDate.of(2026, 2, 1)
        lots.expireDueLots(LocalDate.of(2026, 1, 31)) shouldBe 0
        lots.expireDueLots(LocalDate.of(2026, 2, 1)) shouldBe 1
        lots.requireLot(lot.id).status shouldBe LotStatus.EXPIRED
        lots.expiryLadder(entityId, LocalDate.of(2027, 1, 1), 12).values.sum() shouldBe
            lots.lots(LotFilter(holderEntityId = entityId, statuses = setOf(LotStatus.HELD, LotStatus.RESERVED))).filter {
                it.expiryDate.year == 2027
            }.sumOf { it.quantityMwh }
    }

    @Test
    fun `allowance holdings keep a signed movement journal and never go negative`() {
        val holding = holdings.record(
            RecordAllowanceMovement(entityId, etsAccountId, "EUA", AllowanceMovementType.FREE_ALLOCATION, 10_000, LocalDate.of(2026, 6, 30), "FA-2026"),
        )
        holding.quantity shouldBe 10_000
        holdings.record(
            RecordAllowanceMovement(
                entityId,
                etsAccountId,
                "EUA",
                AllowanceMovementType.PURCHASE,
                2_500,
                LocalDate.of(2026, 7, 1),
                "TRADE-1",
                BigDecimal("71.25"),
                "EUR",
            ),
        )
            .quantity shouldBe 12_500
        shouldThrow<ConflictException> {
            holdings.record(
                RecordAllowanceMovement(
                    entityId,
                    etsAccountId,
                    "EUA",
                    AllowanceMovementType.SURRENDER,
                    20_000,
                    LocalDate.of(2026, 9, 30),
                    complianceYear = 2025,
                ),
            )
        }
        holdings.record(
            RecordAllowanceMovement(entityId, etsAccountId, "EUA", AllowanceMovementType.SURRENDER, 9_000, LocalDate.of(2026, 9, 30), complianceYear = 2025),
        ).quantity shouldBe 3_500
        holdings.movements(holding.id).map { it.quantity } shouldBe listOf(10_000L, 2_500L, -9_000L)
        holdings.reserve(holding.id, 3_000).reservedQuantity shouldBe 3_000
        shouldThrow<ConflictException> {
            holdings.record(RecordAllowanceMovement(entityId, etsAccountId, "EUA", AllowanceMovementType.SALE, 1_000, LocalDate.of(2026, 10, 1)))
        }

        // The movements are append-only and chained per holding; a change made with the triggers off is found.
        holdings.verifyMovements().shouldBeEmpty()

        fun sql(statement: String, tamper: Boolean = false) = TestDatabase.dataSource.connection.use { c ->
            c.autoCommit = !tamper
            if (tamper) c.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }
            c.createStatement().use { it.execute(statement) }
            if (tamper) c.commit()
        }
        val table = "etrm_inventory.allowance_movement"
        val ofHolding = "holding_id = '${holding.id}'"
        shouldThrow<SQLException> { sql("UPDATE $table SET quantity = 1 WHERE $ofHolding") }.message shouldContain "append-only"
        shouldThrow<SQLException> { sql("DELETE FROM $table WHERE $ofHolding") }.message shouldContain "append-only"
        sql("UPDATE etrm_inventory.allowance_movement SET quantity = 11000 WHERE holding_id = '${holding.id}' AND quantity = 10000", tamper = true)
        holdings.verifyMovements() shouldBe listOf(holding.id)
        sql("UPDATE etrm_inventory.allowance_movement SET quantity = 10000 WHERE holding_id = '${holding.id}' AND quantity = 11000", tamper = true)
        holdings.verifyMovements().shouldBeEmpty()
    }

    @Test
    fun `reconciliation reports gaps on both sides and attribute mismatches`() {
        val lot = receive("FI-2026-0030", 40_000, 40_999, month = YearMonth.of(2026, 6))
        val statement = reconciliation.importStatement(
            ImportStatement(
                "FINGRID",
                gooAccountId,
                LocalDate.of(2026, 8, 1),
                "statement.csv",
                listOf(
                    StatementLine(1, "FI-2026-0030", CertificateRange.of(40_000, 40_499), "643000000000000016", LocalDateRange.ofMonth(YearMonth.of(2026, 6))),
                    StatementLine(2, "FI-2026-0030", CertificateRange.of(40_600, 40_999), "643000000000000099", LocalDateRange.ofMonth(YearMonth.of(2026, 6))),
                    StatementLine(3, "FI-2026-0031", CertificateRange.of(50_000, 50_009), null, null),
                ),
            ),
        )
        val result = reconciliation.reconcile(statement.id)
        result.status shouldBe StatementStatus.BREAKS
        val byType = result.breaks.groupBy { it.type }
        byType[BreakType.MISSING_IN_REGISTRY]!!.filter { it.lotId == lot.id }.map { it.range } shouldBe listOf(CertificateRange.of(40_500, 40_599))
        byType[BreakType.MISSING_INTERNALLY]!!.map { it.range } shouldBe listOf(CertificateRange.of(50_000, 50_009))
        byType[BreakType.ATTRIBUTE_MISMATCH]!! shouldHaveSize 1
        reconciliation.openBreaks(gooAccountId).size shouldBe result.breaks.size
        // Reconciling the same statement again returns the recorded result and adds no breaks.
        reconciliation.reconcile(statement.id).breaks.map { it.id }.toSet() shouldBe result.breaks.map { it.id }.toSet()
        reconciliation.openBreaks(gooAccountId).size shouldBe result.breaks.size
        // Accepting a break needs a second person; the database refuses a self-accepted break as well.
        val accepted = result.breaks.single { it.type == BreakType.ATTRIBUTE_MISMATCH }.id
        reconciliation.requestAcceptance(accepted, "device re-registered, registry lags").status shouldBe BreakStatus.PENDING_ACCEPTANCE
        shouldThrow<SegregationOfDutiesException> { reconciliation.decideAcceptance(accepted, true, "tester", null) }
        reconciliation.decideAcceptance(accepted, true, "supervisor", "ok").let {
            it.status shouldBe BreakStatus.ACCEPTED
            it.resolvedBy shouldBe "supervisor"
        }
        shouldThrow<IllegalStateTransitionException> { reconciliation.resolveBreak(accepted, BreakAction.CORRECT_LOT, "again") }
        published.filterIsInstance<ReconciliationBreakResolved>().single().breakView.id shouldBe accepted

        val investigated = result.breaks.single { it.type == BreakType.MISSING_IN_REGISTRY && it.lotId == lot.id }.id
        reconciliation.assignBreak(investigated, "analyst").let {
            it.status shouldBe BreakStatus.INVESTIGATING
            it.assignee shouldBe "analyst"
        }

        // The next statement shows the same gaps again, except the missing receipt, which has been sorted out.
        val next = reconciliation.importStatement(
            ImportStatement(
                "FINGRID",
                gooAccountId,
                LocalDate.of(2026, 8, 2),
                "statement-2.csv",
                listOf(
                    StatementLine(1, "FI-2026-0030", CertificateRange.of(40_000, 40_499), "643000000000000016", LocalDateRange.ofMonth(YearMonth.of(2026, 6))),
                    StatementLine(2, "FI-2026-0030", CertificateRange.of(40_600, 40_999), "643000000000000099", LocalDateRange.ofMonth(YearMonth.of(2026, 6))),
                ),
            ),
        )
        val second = reconciliation.reconcile(next.id)
        second.breaks.map { it.id }.toSet() shouldBe result.breaks.filter { it.type != BreakType.MISSING_INTERNALLY }.map { it.id }.toSet()
        reconciliation.requireBreak(investigated).let {
            it.status shouldBe BreakStatus.INVESTIGATING
            it.lastSeenOn shouldBe LocalDate.of(2026, 8, 2)
            it.firstSeenOn shouldBe LocalDate.of(2026, 8, 1)
        }
        reconciliation.requireBreak(accepted).status shouldBe BreakStatus.ACCEPTED
        val cleared = result.breaks.single { it.type == BreakType.MISSING_INTERNALLY }.id
        reconciliation.requireBreak(cleared).let {
            it.status shouldBe BreakStatus.AUTO_CLEARED
            it.resolvedBy shouldBe "system"
        }
        reconciliation.statement(statement.id)!!.breaks.map { it.id }.toSet() shouldBe result.breaks.map { it.id }.toSet()
        reconciliation.openBreaks(gooAccountId).map { it.id }.toSet() shouldBe second.breaks.filter { it.id != accepted }.map { it.id }.toSet()
    }
}
