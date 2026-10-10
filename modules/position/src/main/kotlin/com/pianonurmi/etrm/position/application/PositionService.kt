package com.pianonurmi.etrm.position.application

import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.position.api.BucketPosition
import com.pianonurmi.etrm.position.api.ComplianceLadder
import com.pianonurmi.etrm.position.api.GooPositionReport
import com.pianonurmi.etrm.position.api.PositionCommand
import com.pianonurmi.etrm.position.api.PositionQuery
import com.pianonurmi.etrm.position.api.PositionSnapshotView
import com.pianonurmi.etrm.position.api.ProductHolding
import com.pianonurmi.etrm.position.api.SnapshotKind
import com.pianonurmi.etrm.position.api.UpsertComplianceInput
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeQuery
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional(readOnly = true)
class PositionService(
    private val lots: LotQuery,
    private val holdings: AllowanceHoldingQuery,
    private val trades: TradeQuery,
    private val allocation: AllocationQuery,
    private val buckets: ProductBucketQuery,
    private val criteria: CriteriaService,
    private val referenceData: ReferenceDataQuery,
    private val repository: PositionRepository,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : PositionQuery, PositionCommand {
    override fun gooPosition(legalEntityId: UUID): GooPositionReport {
        referenceData.requireLegalEntity(legalEntityId)
        val today = LocalDate.now(clock)
        val bound = buckets.bind(criteria.contextFor(today))
        val live = lots.lots(LotFilter(holderEntityId = legalEntityId, statuses = LotStatus.LIVE, limit = Int.MAX_VALUE))
        val inventory = live.groupBy { (bound.best(it)?.code ?: "UNBUCKETED") to it.productionPeriod.start.year }
            .map { (key, group) ->
                BucketPosition(
                    bucketCode = key.first,
                    productionYear = key.second,
                    heldMwh = group.filter { it.status == LotStatus.HELD }.sumOf { it.quantityMwh },
                    reservedMwh = group.filter { it.status == LotStatus.RESERVED }.sumOf { it.quantityMwh },
                    inTransferMwh = group.filter { it.status == LotStatus.IN_TRANSFER }.sumOf { it.quantityMwh },
                    expiringWithin90DaysMwh = group.filter { ChronoUnit.DAYS.between(today, it.expiryDate) in 0..90 }.sumOf { it.quantityMwh },
                    lotCount = group.size,
                )
            }
            .sortedWith(compareBy({ it.bucketCode }, { it.productionYear }))
        val coverage = allocation.coverage(legalEntityId)
        return GooPositionReport(
            legalEntityId = legalEntityId,
            asOf = clock.instant(),
            inventory = inventory,
            totalLiveMwh = inventory.sumOf { it.liveMwh },
            totalOpenObligationMwh = coverage.totalDemandMwh,
            coverage = coverage,
            expiryLadder = lots.expiryLadder(legalEntityId, today, 18),
        )
    }

    override fun complianceLadder(legalEntityId: UUID, complianceYear: Int): ComplianceLadder {
        referenceData.requireLegalEntity(legalEntityId)
        val held = holdings.holdings(legalEntityId).map { ProductHolding(it.productCode, it.registryAccountId, it.quantity, it.reservedQuantity) }
        val open = trades.openObligations(legalEntityId, null, ProductFamily.EMISSION_ALLOWANCE)
            .filter { it.deliveryDate.year <= complianceYear + 1 }
        val input = repository.complianceInput(legalEntityId, complianceYear)
        return ComplianceLadder(
            legalEntityId = legalEntityId,
            complianceYear = complianceYear,
            asOf = clock.instant(),
            holdings = held,
            heldAllowances = held.sumOf { it.quantity },
            openPurchases = open.filter { it.side == Side.BUY }.sumOf { it.remainingQuantity.toLong() },
            openSales = open.filter { it.side == Side.SELL }.sumOf { it.remainingQuantity.toLong() },
            freeAllocationExpected = input?.freeAllocationExpected ?: 0,
            verifiedEmissions = input?.verifiedEmissionsTco2e,
            forecastEmissions = input?.forecastEmissionsTco2e,
        )
    }

    override fun snapshot(legalEntityId: UUID, kind: SnapshotKind, asOfDate: LocalDate): PositionSnapshotView? = repository.snapshot(
        legalEntityId,
        kind,
        asOfDate,
    )

    override fun snapshots(legalEntityId: UUID, kind: SnapshotKind, from: LocalDate, to: LocalDate): List<PositionSnapshotView> =
        repository.snapshots(legalEntityId, kind, from, to)

    @Transactional
    override fun upsertComplianceInput(command: UpsertComplianceInput) {
        referenceData.requireLegalEntity(command.legalEntityId)
        repository.upsertComplianceInput(command, actorProvider.current().userId)
    }

    @Transactional
    override fun takeSnapshots(legalEntityId: UUID, asOfDate: LocalDate): List<PositionSnapshotView> {
        val goo = PositionSnapshotView(Ids.newId(), legalEntityId, SnapshotKind.GOO, asOfDate, clock.instant(), toMap(gooPosition(legalEntityId)))
        val ets =
            PositionSnapshotView(Ids.newId(), legalEntityId, SnapshotKind.ETS, asOfDate, clock.instant(), toMap(complianceLadder(legalEntityId, asOfDate.year)))
        repository.upsertSnapshot(goo)
        repository.upsertSnapshot(ets)
        return listOf(goo, ets)
    }

    private fun toMap(value: Any): Map<String, Any?> = Json.mapper.readValue(
        Json.mapper.writeValueAsString(value),
        object : TypeReference<Map<String, Any?>>() {},
    )
}

interface PositionRepository {
    fun complianceInput(legalEntityId: UUID, complianceYear: Int): UpsertComplianceInput?

    fun upsertComplianceInput(input: UpsertComplianceInput, actor: String)

    fun snapshot(legalEntityId: UUID, kind: SnapshotKind, asOfDate: LocalDate): PositionSnapshotView?

    fun snapshots(legalEntityId: UUID, kind: SnapshotKind, from: LocalDate, to: LocalDate): List<PositionSnapshotView>

    fun upsertSnapshot(snapshot: PositionSnapshotView)
}
