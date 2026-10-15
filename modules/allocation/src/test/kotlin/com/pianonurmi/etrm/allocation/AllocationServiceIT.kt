package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.adapter.jooq.JooqAllocationPlanRepository
import com.pianonurmi.etrm.allocation.adapter.jooq.JooqObligationRepository
import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.FutureSupplyKind
import com.pianonurmi.etrm.allocation.api.HeadroomCheck
import com.pianonurmi.etrm.allocation.api.HeadroomDemand
import com.pianonurmi.etrm.allocation.api.LineStatus
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationStatus
import com.pianonurmi.etrm.allocation.api.OpenObligation
import com.pianonurmi.etrm.allocation.api.PlanStatus
import com.pianonurmi.etrm.allocation.api.PricingStatus
import com.pianonurmi.etrm.allocation.api.ProposeAllocation
import com.pianonurmi.etrm.allocation.api.SupplyTier
import com.pianonurmi.etrm.allocation.api.VirtualLineStatus
import com.pianonurmi.etrm.allocation.application.AllocationHeadroomSettings
import com.pianonurmi.etrm.allocation.application.AllocationService
import com.pianonurmi.etrm.allocation.application.FutureSupplyProvider
import com.pianonurmi.etrm.allocation.application.FutureSupplyService
import com.pianonurmi.etrm.allocation.application.GooRiskService
import com.pianonurmi.etrm.allocation.application.HeadroomService
import com.pianonurmi.etrm.allocation.application.ObligationService
import com.pianonurmi.etrm.allocation.application.PortfolioFlowCache
import com.pianonurmi.etrm.allocation.application.PortfolioService
import com.pianonurmi.etrm.allocation.application.PortfolioValueService
import com.pianonurmi.etrm.allocation.application.PreparedFlow
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
import com.pianonurmi.etrm.platform.test.Providers
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
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqProductionForecastRepository
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateProductionDevice
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.OwnerType
import com.pianonurmi.etrm.referencedata.api.ProductionForecastChanged
import com.pianonurmi.etrm.referencedata.api.RecordForecast
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.application.ProductionForecastService
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
import com.pianonurmi.etrm.trade.api.DeliveryObligationChanged
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.application.TradeService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionOperations
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
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
    private lateinit var planning: AllocationService
    private lateinit var criteria: CriteriaServiceImpl
    private lateinit var futureView: FutureSupplyService
    private lateinit var forecasts: ProductionForecastService
    private val purchases = mutableListOf<DeliveryObligationView>()
    private lateinit var portfolio: PortfolioService
    private lateinit var flows: PortfolioFlowCache
    private lateinit var gooRisk: GooRiskService
    private lateinit var portfolioValues: PortfolioValueService
    private lateinit var newFlows: () -> PortfolioFlowCache
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
        criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
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
                Providers.none(),
            )
        val planRepository = JooqAllocationPlanRepository(dsl)
        obligations = ObligationService(JooqObligationRepository(dsl), criteria, buckets, referenceData, bus)
        val problemBuilder = ProblemBuilder(criteria, buckets, routes, GooMarks { code, _, _ -> MARKS[code]?.let(::BigDecimal) })
        forecasts = ProductionForecastService(JooqProductionForecastRepository(dsl), JooqReferenceDataRepository(dsl), taxonomy, bus, actor)
        val purchaseQuery = mockk<TradeQuery> {
            every { openObligations(any(), Side.BUY, ProductFamily.GUARANTEE_OF_ORIGIN) } answers { purchases.filter { it.legalEntityId == firstArg() } }
        }
        val futureSupply = FutureSupplyProvider(purchaseQuery, referenceData, forecasts, lots, criteria, clock)
        newFlows = {
            PortfolioFlowCache(
                JooqObligationRepository(dsl),
                planRepository,
                problemBuilder,
                buckets,
                lots,
                clock,
                TransactionOperations.withoutTransaction(),
                AllocationHeadroomSettings(warmOnMiss = false),
                futureSupply,
            )
        }
        flows = newFlows()
        gooRisk = GooRiskService(
            flows,
            problemBuilder,
            buckets,
            criteria,
            GooMarks { code, _, _ -> MARKS[code]?.let(::BigDecimal) },
            PortfolioValueService(flows, planRepository, actor, clock, JooqObligationRepository(dsl)),
            clock,
        )
        portfolioValues = PortfolioValueService(flows, planRepository, actor, clock, JooqObligationRepository(dsl))
        headroom = HeadroomService(flows, problemBuilder, buckets, criteria, actor, clock)
        futureView = FutureSupplyService(flows, actor, clock)
        portfolio = PortfolioService(flows, problemBuilder, buckets, criteria, GooMarks { code, _, _ -> MARKS[code]?.let(::BigDecimal) }, actor, clock)
        // The same service, counting on purchases and expected issuance (ADR 0038); `allocation` keeps to held lots.
        planning = AllocationService(
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
            futureSupply = futureSupply,
            referenceData = referenceData,
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
            // Held lots only unless a command asks for future supply, which this service's tests never do.
            futureSupply = futureSupply,
            referenceData = referenceData,
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
        // The new lot was patched into the prepared figures (ADR 0043); they are the figures a fresh build makes.
        val patched = flows.prepare(holder)
        patched.patched shouldBe true
        val fresh = newFlows().prepare(holder)
        fresh.patched shouldBe false
        patched.state.totalFlow shouldBe fresh.state.totalFlow
        freeByKey(patched) shouldBe freeByKey(fresh)
        // A pre-trade check after the next change patches at once instead of reporting "not checked".
        receive("643000000000000023", "HR-W-2", 200, holder = holder, account = account)
        headroom.checkIfReady(HeadroomCheck(holder, listOf(renewable))).shouldNotBeNull().demands.single().headroomMwh shouldBe 1_900

        val table = headroom.table(holder)
        table.deliveryDates.size shouldBe 6
        table.rows.single { it.bucketCode == "ANY_GOO" && it.vintageYear == 2026 }.let {
            it.heldMwh shouldBe 2_500
            it.headroomMwh.toSet() shouldBe setOf(1_900L)
        }
        table.rows.filter { it.bucketCode == "NORDIC_HYDRO" }.single().headroomMwh.toSet() shouldBe setOf(700L)
    }

    @Test
    fun `GoO risk measures one vintage and projects the give-away of a sale on the free certificates, cheapest first`() {
        val holder = referenceData.createLegalEntity(CreateLegalEntity("BASIS-CO", "Basis Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val account = referenceData.createRegistryAccount(
            CreateRegistryAccount("SIMULATOR", "SIM-BASIS", "Basis account", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, holder),
        ).id
        receive("643000000000000016", "BS-H-1", 1_000, holder = holder, account = account)
        receive("643000000000000023", "BS-W-1", 1_000, holder = holder, account = account)

        gooRisk.concentration(holder, "NORDIC_HYDRO", 2026)!!.freeMwh shouldBe 1_000
        gooRisk.concentration(holder, "NORDIC_HYDRO", 2025)!!.freeMwh shouldBe 0
        gooRisk.unusedExpiringMwh(holder, 730, productionYear = 2026) shouldBe 2_000

        // 1 500 MWh of EU renewable (0.50): the wind (worth 0.50) first, then 500 of hydro worth 2.00 hand over 1.50 each.
        val delivery = mockk<DeliveryScheduleView> {
            every { id } returns UUID.randomUUID()
            every { scheduleNo } returns 1
            every { deliveryDate } returns LocalDate.of(2026, 12, 15)
            every { remainingQuantity } returns BigDecimal(1_500)
        }
        val leg = mockk<TradeLegView> {
            every { legNo } returns 1
            every { side } returns Side.SELL
            every { criterion } returns Criterion.energySource("RENEWABLE")
            every { bucketCode } returns "EU_RENEWABLE"
            every { counterpartyAccountRef } returns "SIMULATOR:SIM-CP-BASIS"
            every { deliveries } returns listOf(delivery)
        }
        val trade = mockk<TradeView> {
            every { productFamily } returns ProductFamily.GUARANTEE_OF_ORIGIN
            every { legalEntityId } returns holder
            every { legs } returns listOf(leg)
        }
        gooRisk.vintages(trade) shouldBe mapOf(delivery.id to 2026)
        flows.prepare(holder)
        gooRisk.projectSale(trade).shouldNotBeNull().deliveries.single().let {
            it.coveredMwh shouldBe 1_500
            it.giveAwayMwh shouldBe 500
            it.giveAwayEur shouldBe BigDecimal("750.00")
            it.vintageYear shouldBe 2026
        }
    }

    /** The give-away report's lines: one per sale of an approved plan, as valued when it was proposed; none tomorrow. */
    private fun giveAwayLinesOf(planId: UUID, sales: Set<UUID>) {
        val today = LocalDate.now(ZoneOffset.UTC)
        portfolioValues.giveAwayLines(entityId, today.minusDays(1), today.plusDays(1)).filter { it.planId == planId }.let { lines ->
            lines.map { it.obligationId }.toSet() shouldBe sales
            lines.sumOf { it.mwh } shouldBe 1_300
            lines.mapNotNull { it.giveAwayEur }.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal("140.00")) shouldBe 0
        }
        portfolioValues.giveAwayLines(entityId, today.plusDays(1), today.plusDays(2)).shouldBeEmpty()
    }

    private fun freeByKey(prepared: PreparedFlow): Map<String, Long> =
        prepared.state.unitResidual().withIndex().filter { prepared.problem.classes[it.index].quantity > 0 }
            .associate { prepared.problem.classes[it.index].key to it.value }

    @Test
    fun `the portfolio nets long and short only where certificates qualify`() {
        val holder = referenceData.createLegalEntity(CreateLegalEntity("PORTFOLIO-CO", "Portfolio Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val account = referenceData.createRegistryAccount(
            CreateRegistryAccount("SIMULATOR", "SIM-PORT", "Portfolio account", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, holder),
        ).id
        receive("643000000000000023", "PF-W-1", 1_000, holder = holder, account = account)
        obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                holder,
                criterion = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO")),
                quantityMwh = 600,
                deliveryWindow = LocalDateRange.ofQuarter(2026, 4),
                deliveryDate = LocalDate.of(2026, 12, 15),
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-PF",
            ),
        )

        val view = portfolio.portfolio(holder)
        val nodes = view.nodes.associateBy { it.bucketCode }
        // Nordic renewable looks 400 long, but its certificates are wind and its obligation wants hydro.
        nodes.getValue("NORDIC_RENEWABLE").let {
            it.longMwh shouldBe 1_000
            it.shortMwh shouldBe 600
            it.naiveNetMwh shouldBe 400
            it.coveredWithinMwh shouldBe 0
            it.shortAfterNettingMwh shouldBe 600
            it.freeMwh shouldBe 1_000
        }
        nodes.getValue("NORDIC_HYDRO").let {
            it.longMwh shouldBe 0
            it.shortAfterNettingMwh shouldBe 600
            it.availableToSellMwh shouldBe 0
        }
        nodes.getValue("FI_WIND").let {
            it.shortMwh shouldBe 0
            it.availableToSellMwh shouldBe 1_000
            it.parents.toSet() shouldBe setOf("NORDIC_WIND", "FI_RENEWABLE")
        }
        // Wind is worth its best price: EU renewable at 0.50 (no wind curve). Sold as EU renewable, nothing is given away.
        nodes.getValue("EU_RENEWABLE").let {
            it.markEur shouldBe BigDecimal("0.5000")
            it.cheapestToDeliverEur shouldBe BigDecimal("0.5000")
            it.giveAwayPerMwhEur shouldBe BigDecimal("0.0000")
        }
        nodes.getValue("ANY_GOO").giveAwayPerMwhEur shouldBe BigDecimal("0.2000")
        view.nodes.first().bucketCode shouldBe "ANY_GOO"

        // Hydro arrives: now the bucket covers part of its own obligation.
        receive("643000000000000016", "PF-H-1", 300, holder = holder, account = account)
        portfolio.portfolio(holder).nodes.single { it.bucketCode == "NORDIC_HYDRO" }.let {
            it.coveredWithinMwh shouldBe 300
            it.shortAfterNettingMwh shouldBe 300
        }
    }

    @Test
    fun `plans count on a purchase for the later sale and keep held hydro for the earlier one`() {
        val holder = referenceData.createLegalEntity(CreateLegalEntity("FUTURE-CO", "Future Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        val account = referenceData.createRegistryAccount(
            CreateRegistryAccount("SIMULATOR", "SIM-FUT", "Future account", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, holder),
        ).id
        receive("643000000000000016", "FU-H-1", 100, holder = holder, account = account)
        val nordicHydro = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"))
        val scheduleId = UUID.randomUUID()
        val purchase = mockk<DeliveryObligationView> {
            every { this@mockk.scheduleId } returns scheduleId
            every { legalEntityId } returns holder
            every { tradeNumber } returns "T-BUY-1"
            every { side } returns Side.BUY
            // As read in bulk: the criteria by id only.
            every { criterion } returns null
            every { criteriaId } returns criteria.store(nordicHydro).id
            every { bucketCode } returns "NORDIC_HYDRO"
            every { status } returns DeliveryStatus.PENDING
            every { quantity } returns BigDecimal(100)
            every { deliveredQuantity } returns BigDecimal.ZERO
            every { deliveryDate } returns LocalDate.of(2026, 11, 15)
            every { deliveryWindow } returns LocalDateRange.ofMonth(YearMonth.of(2026, 11))
            every { deliveryAccountId } returns account
        }
        purchases += purchase
        // Before any sale: the purchase adds to what can be sold from its delivery date, at the contracted tier.
        val nordicSale = HeadroomDemand(nordicHydro, 300, LocalDate.of(2026, 12, 1))
        headroom.check(HeadroomCheck(holder, listOf(nordicSale))).demands.single().headroomMwh shouldBe 100
        headroom.check(HeadroomCheck(holder, listOf(nordicSale), tier = SupplyTier.CONTRACTED)).demands.single().headroomMwh shouldBe 200
        headroom.check(HeadroomCheck(holder, listOf(nordicSale.copy(deliveryDate = LocalDate.of(2026, 11, 1))), tier = SupplyTier.CONTRACTED))
            .demands.single().headroomMwh shouldBe 100
        portfolio.portfolio(holder, SupplyTier.CONTRACTED).nodes.single { it.bucketCode == "NORDIC_HYDRO" }.let {
            it.longMwh shouldBe 100
            it.futureMwh shouldBe 100
        }
        // A Finnish hydro bucket cannot count a Nordic purchase: it may be Norwegian.
        portfolio.portfolio(holder, SupplyTier.CONTRACTED).nodes.single { it.bucketCode == "FI_HYDRO" }.futureMwh shouldBe 0

        fun sale(date: LocalDate, ref: String) = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                holder,
                criterion = nordicHydro,
                quantityMwh = 100,
                deliveryWindow = LocalDateRange.inclusive(date.minusDays(10), date),
                deliveryDate = date,
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-$ref",
            ),
        )
        val early = sale(LocalDate.of(2026, 11, 1), "EARLY")
        val late = sale(LocalDate.of(2026, 12, 15), "LATE")

        val plan = planning.propose(ProposeAllocation(holder, setOf(early.id, late.id), idempotencyKey = "future-1"))
        plan.lines.single().let {
            it.obligationId shouldBe early.id
            it.quantityMwh shouldBe 100
        }
        plan.virtualLines.single().let {
            it.obligationId shouldBe late.id
            it.kind shouldBe FutureSupplyKind.CONTRACTED
            it.ref shouldBe "purchase:$scheduleId"
            it.quantityMwh shouldBe 100
            it.availableFrom shouldBe LocalDate.of(2026, 11, 15)
        }
        // Held lots cover one sale; the purchase the other.
        val coverage = plan.coverage.shouldNotBeNull()
        coverage.totalCoveredMwh shouldBe 100
        coverage.obligations.sumOf { it.coveredWithContractedMwh.shouldNotBeNull() } shouldBe 200
        plan.value.shouldNotBeNull().byObligation.sumOf { it.mwh } shouldBe 200
        planning.plan(plan.id)!!.virtualLines.single().status shouldBe VirtualLineStatus.PLANNED
        futureView.futureSupply(holder).let { view ->
            view.items.single().let {
                it.kind shouldBe FutureSupplyKind.CONTRACTED
                it.tradeNumber shouldBe "T-BUY-1"
                it.quantityMwh shouldBe 100
            }
            view.months.map { it.month } shouldBe listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12))
            view.coveredHeldMwh shouldBe 100
            view.coveredWithContractedMwh shouldBe 200
        }

        // The purchase changes: the proposal is no longer good and goes stale.
        planning.on(DeliveryObligationChanged(purchase))
        planning.plan(plan.id)!!.status shouldBe PlanStatus.STALE

        // Without future supply the same request keeps to held lots: one sale stays short.
        allocation.whatIf(ProposeAllocation(holder, setOf(early.id, late.id), idempotencyKey = "held-only")).lines.sumOf { it.quantityMwh } shouldBe 100
        purchases.clear()
    }

    @Test
    fun `an own device's expected issuance serves sales after it is issued, and a new forecast makes the proposal stale`() {
        val holder = referenceData.createLegalEntity(CreateLegalEntity("ISSUE-CO", "Issue Co", "FI", validFrom = LocalDate.of(2026, 1, 1))).id
        referenceData.createRegistryAccount(
            CreateRegistryAccount("SIMULATOR", "SIM-ISS", "Issue account", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, holder),
        )
        val device = referenceData.createProductionDevice(
            CreateProductionDevice(
                gsrn = "643000000000000047",
                name = "Issue hydro",
                country = "FI",
                capacityKw = BigDecimal("1000"),
                commissioningDate = LocalDate.of(2018, 1, 1),
                energySourceCode = "HYDRO_RESERVOIR",
                technologyCode = "HYDRO_TURBINE_STORAGE",
                registryCode = "SIMULATOR",
                ownerType = OwnerType.LEGAL_ENTITY,
                ownerId = holder,
                ownDevice = true,
                issuanceLagDays = 20,
            ),
        )
        val month = YearMonth.now().plusMonths(1)
        forecasts.record(RecordForecast(device.id, month, expectedMwh = 500, p90Mwh = 400))
        val delivery = month.atEndOfMonth().plusDays(40)
        val sale = obligations.open(
            OpenObligation(
                ObligationKind.SALE,
                holder,
                criterion = Criterion.all(Criterion.country("FI"), Criterion.energySource("HYDRO")),
                quantityMwh = 300,
                deliveryWindow = LocalDateRange.inclusive(delivery.minusDays(5), delivery),
                deliveryDate = delivery,
                targetRegistryCode = "SIMULATOR",
                targetAccountRef = "SIM-CP-ISS",
            ),
        )

        val plan = planning.propose(ProposeAllocation(holder, setOf(sale.id), idempotencyKey = "issue-1"))
        plan.lines.shouldBeEmpty()
        val expected = plan.virtualLines.filter { it.ref == "issuance:643000000000000047:$month" }.single()
        expected.kind shouldBe FutureSupplyKind.EXPECTED
        expected.availableFrom shouldBe month.atEndOfMonth().plusDays(20)
        // Plans take the cautious figure: at most the P90 of 400 from this month.
        (expected.quantityMwh <= 300) shouldBe true
        expected.breakdown.reliabilityPremium.shouldNotBeNull()

        planning.on(ProductionForecastChanged(device.id, listOf(month)))
        planning.plan(plan.id)!!.status shouldBe PlanStatus.STALE
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
        approved.lines.all { it.status == LineStatus.CONFIRMED && it.externalRef?.startsWith("SIM-") == true } shouldBe true

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
        giveAwayLinesOf(proposed.id, setOf(hydroSale.id, disclosure.id))
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
