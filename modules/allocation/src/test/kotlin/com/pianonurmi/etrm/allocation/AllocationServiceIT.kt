package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.adapter.jooq.JooqAllocationPlanRepository
import com.pianonurmi.etrm.allocation.adapter.jooq.JooqObligationRepository
import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.HeadroomCheck
import com.pianonurmi.etrm.allocation.api.HeadroomDemand
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationStatus
import com.pianonurmi.etrm.allocation.api.OpenObligation
import com.pianonurmi.etrm.allocation.api.PlanStatus
import com.pianonurmi.etrm.allocation.api.PricingStatus
import com.pianonurmi.etrm.allocation.api.ProposeAllocation
import com.pianonurmi.etrm.allocation.application.AllocationHeadroomSettings
import com.pianonurmi.etrm.allocation.application.AllocationService
import com.pianonurmi.etrm.allocation.application.HeadroomService
import com.pianonurmi.etrm.allocation.application.ObligationService
import com.pianonurmi.etrm.allocation.application.ProblemBuilder
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqAllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotEventJournal
import com.pianonurmi.etrm.inventory.adapter.jooq.JooqLotRepository
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CancellationPurpose
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.application.AllowanceHoldingService
import com.pianonurmi.etrm.inventory.application.LotService
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.GooMarks
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqProductBucketRepository
import com.pianonurmi.etrm.product.api.BucketMatchKind
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.VintageRule
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.ProductBucketSeeder
import com.pianonurmi.etrm.product.application.ProductBucketService
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
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.application.RegistryInstructionService
import com.pianonurmi.etrm.registry.application.TransferRouteService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.adapter.jooq.JooqTradeRepository
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.application.TradeService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Currency
import java.util.UUID

/**
 * Wires the real services together (no Spring context) with a synchronous event bus that routes registry events to
 * the allocation listeners, and drives the plan lifecycle end to end against PostgreSQL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AllocationServiceIT {
    private var currentUser = "trader"
    private val actor = ActorProvider { Actor(currentUser, currentUser, setOf(Role.TRADER, Role.MIDDLE_OFFICE), allEntities = true) }
    private val published = mutableListOf<Any>()
    private lateinit var allocation: AllocationService
    private lateinit var obligations: ObligationService
    private lateinit var lots: LotService
    private lateinit var headroom: HeadroomService
    private lateinit var referenceData: ReferenceDataService
    private lateinit var entityId: UUID
    private lateinit var simAccount: UUID
    private lateinit var manualAccount: UUID

    private val bus = ApplicationEventPublisher { event ->
        published += event
        when (event) {
            is InstructionConfirmed -> allocation.on(event)
            is InstructionFailed -> allocation.on(event)
        }
    }

    @BeforeAll
    @Suppress("LongMethod") // wires the module graph by hand
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "inventory", "trade", "registry", "allocation")
        val dsl = TestDatabase.dsl
        val clock = Clock.systemUTC()
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), bus)
        referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, bus)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        val buckets = ProductBucketService(JooqProductBucketRepository(dsl), criteria)
        ProductBucketSeeder(buckets, buckets).seed()
        val catalogue = ProductCatalogueService(JooqAllowanceProductRepository(dsl))
        lots = LotService(JooqLotRepository(dsl), JooqLotEventJournal(dsl), referenceData, taxonomy, criteria, actor, bus, clock)
        val allowanceHoldings = AllowanceHoldingService(JooqAllowanceHoldingRepository(dsl), referenceData, catalogue, actor, bus)
        val routes = TransferRouteService(JooqTransferRouteRepository(dsl), referenceData, BigDecimal("0.05"), 3)
        val instructions = RegistryInstructionService(
            JooqRegistryInstructionRepository(dsl),
            listOf(SimulatorRegistryGateway(), ManualRegistryGateway()),
            lots,
            lots,
            referenceData,
            routes,
            actor,
            bus,
            clock,
            allowanceHoldings,
            allowanceHoldings,
        )
        val trades =
            TradeService(
                JooqTradeRepository(dsl),
                referenceData,
                criteria,
                buckets,
                catalogue,
                actor,
                bus,
                clock,
                BigDecimal.ZERO,
                mockk<FxRates>(relaxed = true),
                "EUR",
                mockk(relaxed = true),
            )
        val planRepository = JooqAllocationPlanRepository(dsl)
        obligations = ObligationService(JooqObligationRepository(dsl), criteria, buckets, referenceData, bus)
        val problemBuilder = ProblemBuilder(criteria, buckets, routes, GooMarks { code, _, _ -> MARKS[code]?.let(::BigDecimal) })
        headroom = HeadroomService(
            JooqObligationRepository(dsl),
            planRepository,
            problemBuilder,
            buckets,
            criteria,
            lots,
            actor,
            clock,
            settings = AllocationHeadroomSettings(warmOnMiss = false),
        )
        allocation = AllocationService(
            planRepository,
            JooqObligationRepository(dsl),
            obligations,
            problemBuilder,
            criteria,
            lots,
            lots,
            instructions,
            instructions,
            trades,
            trades,
            actor,
            bus,
            clock,
            Savepoints.NONE,
        )

        entityId = referenceData.createLegalEntity(CreateLegalEntity("ALLOC-CO", "Allocation Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        simAccount = referenceData.createRegistryAccount(
            CreateRegistryAccount("SIMULATOR", "SIM-ALLOC", "Simulator account", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entityId),
        ).id
        manualAccount = referenceData.createRegistryAccount(
            CreateRegistryAccount("UNION_REGISTRY", "EU-ALLOC", "Manual account", RegistryAccountType.TRADING, HolderType.LEGAL_ENTITY, entityId),
        ).id
        listOf("643000000000000016" to "HYDRO_RESERVOIR", "643000000000000023" to "WIND_ONSHORE").forEach { (gsrn, source) ->
            referenceData.createProductionDevice(
                CreateProductionDevice(
                    gsrn = gsrn,
                    name = "Device $gsrn",
                    country = "FI",
                    capacityKw = BigDecimal("50000"),
                    commissioningDate = LocalDate.of(2018, 1, 1),
                    energySourceCode = source,
                    technologyCode = if (source == "WIND_ONSHORE") "WIND_TURBINE_ONSHORE" else "HYDRO_TURBINE_STORAGE",
                    ownerType = OwnerType.LEGAL_ENTITY,
                    ownerId = entityId,
                    ownDevice = true,
                ),
            )
        }
    }

    private fun receive(
        gsrn: String,
        issuance: String,
        quantity: Long,
        month: YearMonth = YearMonth.of(2026, 5),
        cost: String = "1.00",
        holder: UUID = entityId,
        account: UUID = simAccount,
    ) = lots.receive(
        ReceiveLots(
            holderEntityId = holder,
            registryAccountId = account,
            issuanceRef = issuance,
            range = CertificateRange.of(1, quantity),
            deviceGsrn = gsrn,
            productionPeriod = LocalDateRange.ofMonth(month),
            issueDate = month.plusMonths(1).atDay(10),
            issuingBody = "SIM",
            issuingCountry = "FI",
            supportProduction = false,
            supportInvestment = false,
            acquisitionSource = AcquisitionSource.ISSUANCE,
            costBasisPerMwh = BigDecimal(cost),
            costCurrency = "EUR",
        ),
    )

    @Test
    fun `available to sell counts what held certificates can still serve, leaves own schedules out and sees changes without events`() {
        val holder = referenceData.createLegalEntity(CreateLegalEntity("HEADROOM-CO", "Headroom Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val account = referenceData.createRegistryAccount(
            CreateRegistryAccount("SIMULATOR", "SIM-HEAD", "Headroom account", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, holder),
        ).id
        receive("643000000000000016", "HR-H-1", 1_000, holder = holder, account = account)
        receive("643000000000000023", "HR-W-1", 1_000, holder = holder, account = account)
        val nordicHydro = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"))
        val sale = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                holder,
                sourceRef = UUID.randomUUID(),
                criterion = nordicHydro,
                quantityMwh = 600,
                deliveryWindow = LocalDateRange.ofQuarter(2026, 4),
                deliveryDate = LocalDate.of(2026, 12, 15),
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-HR",
            ),
        )
        val hydro = HeadroomDemand(nordicHydro, 500, LocalDate.of(2026, 11, 30), ref = "hydro")
        val renewable = HeadroomDemand(Criterion.energySource("RENEWABLE"), 1_500, LocalDate.of(2026, 11, 30), ref = "renewable")

        headroom.checkIfReady(HeadroomCheck(holder, listOf(hydro))) shouldBe null
        val first = headroom.check(HeadroomCheck(holder, listOf(hydro, renewable)))
        first.demands[0].let {
            it.headroomMwh shouldBe 400
            it.fits shouldBe false
            it.freeMwh shouldBe 400
            it.binding.single().productionYear shouldBe 2026
            it.binding.single().bucketCode shouldContain "HYDRO"
        }
        // The hydro sale could take the wind's place for nothing; the wind could not take the hydro's: 2 000 - 600.
        first.demands[1].headroomMwh shouldBe 1_400
        first.jointMwh shouldBe 1_400
        first.fits shouldBe false
        headroom.checkIfReady(HeadroomCheck(holder, listOf(hydro))).shouldNotBeNull().demands.single().headroomMwh shouldBe 400

        // An amendment of the sale replaces it: its own schedule is not existing demand.
        headroom.check(HeadroomCheck(holder, listOf(hydro), excludeSourceRefs = setOf(sale.sourceRef!!))).demands.single().headroomMwh shouldBe 1_000

        // A lot received without any event to listen to: the next read sees the new stamp.
        receive("643000000000000016", "HR-H-2", 300, holder = holder, account = account)
        val after = headroom.check(HeadroomCheck(holder, listOf(hydro)))
        after.stamp shouldNotBe first.stamp
        after.demands.single().let {
            it.headroomMwh shouldBe 700
            it.fits shouldBe true
            it.binding.shouldBeEmpty()
        }

        val table = headroom.table(holder)
        table.deliveryDates.size shouldBe 6
        table.rows.single { it.bucketCode == "ANY_GOO" && it.vintageYear == 2026 }.let {
            it.heldMwh shouldBe 2_300
            it.headroomMwh.toSet() shouldBe setOf(1_700L)
        }
        table.rows.filter { it.bucketCode == "NORDIC_HYDRO" }.single().headroomMwh.toSet() shouldBe setOf(700L)
    }

    @Test
    fun `constraints are stored with their type and read back`() {
        val constraints = listOf(
            AllocationConstraint.MaxSharePerDevice(60),
            AllocationConstraint.MinShare(Criterion.energySource("HYDRO"), 30),
        )
        val opened = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                entityId,
                criterion = Criterion.energySource("RENEWABLE"),
                quantityMwh = 100,
                deliveryWindow = LocalDateRange.ofQuarter(2026, 4),
                deliveryDate = LocalDate.of(2026, 12, 15),
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-9",
                constraints = constraints,
            ),
        )
        obligations.requireObligation(opened.id).constraints shouldBe constraints
        obligations.open(holderEntityId = entityId).single { it.id == opened.id }.constraints shouldBe constraints
    }

    @Test
    fun `obligations carry the bucket of their trade leg, or the bucket of their criterion`() {
        val window = LocalDateRange.ofQuarter(2026, 4)
        val scheduleId = UUID.randomUUID()
        val criterion = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"))
        val fromTrade = DeliveryObligationView(
            scheduleId = scheduleId,
            tradeId = UUID.randomUUID(),
            tradeNumber = "T-BUCKET-1",
            legId = UUID.randomUUID(),
            legalEntityId = entityId,
            bookId = UUID.randomUUID(),
            counterpartyId = UUID.randomUUID(),
            side = Side.SELL,
            productFamily = ProductFamily.GUARANTEE_OF_ORIGIN,
            energyCarrier = null,
            allowanceProductCode = null,
            criteriaId = null,
            criterion = criterion,
            quantity = BigDecimal(100),
            deliveredQuantity = BigDecimal.ZERO,
            deliveryWindow = window,
            deliveryDate = LocalDate.of(2026, 12, 15),
            deliveryAccountId = null,
            counterpartyAccountRef = "SIMULATOR:SIM-CP-9",
            price = BigDecimal("2.00"),
            priceCurrency = Currency.getInstance("EUR"),
            priceType = PriceType.FIXED,
            status = DeliveryStatus.PENDING,
            bucketCode = "NORDIC_HYDRO",
            bucketMatch = BucketMatchKind.EXACT,
        )
        obligations.on(DeliveryObligationOpened(fromTrade))
        val synced = obligations.bySource(scheduleId)!!
        synced.bucketCode shouldBe "NORDIC_HYDRO"
        synced.bucketMatch shouldBe BucketMatchKind.EXACT
        synced.vintageYear shouldBe 2026

        val manual = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                entityId,
                criterion = criterion,
                quantityMwh = 10,
                deliveryWindow = window,
                deliveryDate = LocalDate.of(2026, 12, 15),
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-9",
            ),
        )
        manual.bucketCode shouldBe "NORDIC_HYDRO"
        obligations.requireObligation(manual.id).bucketCode shouldBe "NORDIC_HYDRO"
    }

    @Test
    fun `propose, four-eyes approve, instruct through the simulator and fulfil obligations`() {
        currentUser = "trader"
        val hydro = receive("643000000000000016", "H-1", 1_000, cost = "2.50")
        val wind = receive("643000000000000023", "W-1", 1_000, cost = "0.80")
        val hydroSale = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                entityId,
                criterion = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"), Criterion.productionYear(2026)),
                quantityMwh = 600,
                deliveryWindow = LocalDateRange.ofQuarter(2026, 4),
                deliveryDate = LocalDate.of(2026, 12, 15),
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-9",
            ),
        )
        val disclosure = obligations.open(
            OpenObligation(
                ObligationKind.DISCLOSURE,
                entityId,
                criterion = Criterion.energySource("RENEWABLE"),
                quantityMwh = 700,
                deliveryWindow = LocalDateRange.ofQuarter(2027, 1),
                deliveryDate = LocalDate.of(2027, 3, 31),
                cancellation = CancellationDetails(CancellationPurpose.DISCLOSURE, "Retail customers", null, "FI", LocalDateRange.ofYear(2026)),
            ),
        )

        val scope = setOf(hydroSale.id, disclosure.id)
        val coverage = allocation.coverage(entityId, scope)
        coverage.totalDemandMwh shouldBe 1_300
        coverage.totalShortfallMwh shouldBe 0

        val whatIf = allocation.whatIf(ProposeAllocation(entityId, scope, idempotencyKey = "what-if"))
        val proposed = allocation.propose(ProposeAllocation(entityId, scope, idempotencyKey = "plan-1"))
        proposed.status shouldBe PlanStatus.PROPOSED
        // Hydro is worth 2.00 (NORDIC_HYDRO), wind 0.50 (EU_RENEWABLE); the disclosure is marked as ANY_GOO at 0.30.
        listOf(whatIf.value, proposed.value, allocation.plan(proposed.id)?.value).forEach { value ->
            value.shouldNotBeNull()
            value.currency shouldBe "EUR"
            value.deliveredValueEur shouldBe BigDecimal("1550.00")
            value.giveAwayEur shouldBe BigDecimal("140.00")
            value.unpricedMwh shouldBe 0
            value.byObligation.first().obligationId shouldBe disclosure.id
        }
        listOf(whatIf.pricing, proposed.pricing, allocation.plan(proposed.id)?.pricing).forEach { pricing ->
            pricing.shouldNotBeNull()
            pricing.status shouldBe PricingStatus.OPTIMAL
            pricing.obligations.map { it.obligationId }.toSet() shouldBe scope
            pricing.planCostEur shouldBe proposed.value!!.totalCostEur
            pricing.gapEur shouldBe BigDecimal("0.00")
        }
        proposed.allocatedMwh shouldBe 1_300
        val hydroLine = proposed.lines.single { it.obligationId == hydroSale.id }
        hydroLine.lotId shouldBe hydro.id
        val disclosureLines = proposed.lines.filter { it.obligationId == disclosure.id }
        disclosureLines.map { it.lotId }.toSet() shouldBe setOf(wind.id)
        allocation.propose(ProposeAllocation(entityId, scope, idempotencyKey = "plan-1")).id shouldBe proposed.id

        // A competing proposal only sees what the first one left (soft reservations).
        val competing = allocation.propose(ProposeAllocation(entityId, scope, idempotencyKey = "plan-2"))
        (competing.lines.filter { it.lotId == hydro.id }.sumOf { it.quantityMwh } <= 400) shouldBe true
        (competing.lines.filter { it.lotId == wind.id }.sumOf { it.quantityMwh } <= 300) shouldBe true
        allocation.reject(competing.id, "duplicate").status shouldBe PlanStatus.REJECTED

        shouldThrow<SegregationOfDutiesException> { allocation.approve(proposed.id) }
        currentUser = "middle-office"
        val approved = allocation.approve(proposed.id)
        approved.status shouldBe PlanStatus.CONFIRMED
        approved.lines.all { it.status == LineStatus.CONFIRMED } shouldBe true
        approved.lines.all { it.externalRef?.startsWith("SIM-") == true } shouldBe true

        obligations.requireObligation(hydroSale.id).status shouldBe ObligationStatus.FULFILLED
        obligations.requireObligation(disclosure.id).status shouldBe ObligationStatus.FULFILLED
        lots.lots(LotFilter(holderEntityId = entityId, statuses = setOf(LotStatus.TRANSFERRED_OUT))).filter {
            it.rootLotId == hydro.id
        }.sumOf { it.quantityMwh } shouldBe 600
        lots.lots(LotFilter(holderEntityId = entityId, statuses = setOf(LotStatus.CANCELLED))).filter {
            it.rootLotId == wind.id
        }.sumOf { it.quantityMwh } shouldBe 700
        lots.lots(LotFilter(holderEntityId = entityId, statuses = setOf(LotStatus.HELD))).filter {
            it.rootLotId in setOf(hydro.id, wind.id)
        }.sumOf { it.quantityMwh } shouldBe 700
        allocation.plans(entityId, activeOnly = true).shouldBeEmpty()
        allocation.coverage(entityId, scope).totalDemandMwh shouldBe 0
    }

    @Test
    fun `a plan goes stale when its lots were taken between proposal and approval`() {
        currentUser = "trader"
        val lot = receive("643000000000000023", "W-2", 300, month = YearMonth.of(2026, 7))
        val sale = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                entityId,
                criterion = Criterion.all(
                    Criterion.energySource("WIND"),
                    Criterion.Vintage(VintageRule.Explicit(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))),
                ),
                quantityMwh = 300,
                deliveryWindow = LocalDateRange.ofQuarter(2026, 4),
                deliveryDate = LocalDate.of(2026, 11, 30),
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-2",
            ),
        )
        val plan = allocation.propose(ProposeAllocation(entityId, setOf(sale.id), idempotencyKey = "plan-stale"))
        plan.lines.single().lotId shouldBe lot.id
        lots.reserve(lot.id, null, UUID.randomUUID())
        currentUser = "approver"
        val result = allocation.approve(plan.id)
        result.status shouldBe PlanStatus.STALE
        result.lines.all { it.status == LineStatus.CANCELLED } shouldBe true
        obligations.requireObligation(sale.id).fulfilledMwh shouldBe 0
    }

    @Test
    fun `partial reservation carves the needed quantity and confirms through the simulator`() {
        currentUser = "trader"
        val lot = receive("643000000000000016", "H-3", 500, month = YearMonth.of(2026, 8))
        val sale = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                entityId,
                criterion = Criterion.all(
                    Criterion.energySource("HYDRO"),
                    Criterion.Vintage(VintageRule.Explicit(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))),
                ),
                quantityMwh = 200,
                deliveryWindow = LocalDateRange.ofQuarter(2026, 4),
                deliveryDate = LocalDate.of(2026, 12, 1),
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-3",
            ),
        )
        val plan = allocation.propose(ProposeAllocation(entityId, setOf(sale.id), idempotencyKey = "plan-cancel"))
        plan.lines.single().quantityMwh shouldBe 200
        currentUser = "approver"
        val approved = allocation.approve(plan.id)
        approved.status shouldBe PlanStatus.CONFIRMED
        val carved = approved.lines.single().reservedLotId!!
        lots.requireLot(carved).quantityMwh shouldBe 200
        lots.requireLot(carved).status shouldBe LotStatus.TRANSFERRED_OUT
        lots.lots(LotFilter(holderEntityId = entityId, statuses = setOf(LotStatus.HELD))).filter {
            it.rootLotId == lot.id
        }.sumOf { it.quantityMwh } shouldBe 300
    }

    private companion object {
        val MARKS = mapOf("ANY_GOO" to "0.30", "EU_RENEWABLE" to "0.50", "NORDIC_HYDRO" to "2.00")
    }
}
