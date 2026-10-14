package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.allocation.api.AllocationLineConfirmed
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.HoldingChanged
import com.pianonurmi.etrm.inventory.api.LotCancelled
import com.pianonurmi.etrm.inventory.api.LotExpired
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotReceived
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.domain.CostLayer
import com.pianonurmi.etrm.ledger.domain.CostLayerRepository
import com.pianonurmi.etrm.ledger.domain.CostingEngine
import com.pianonurmi.etrm.ledger.domain.CostingMethod
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.referencedata.api.AccountingPolicy
import com.pianonurmi.etrm.referencedata.api.AllowanceCostingMethod
import com.pianonurmi.etrm.referencedata.api.FreeAllocationPolicy
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.TradeQuery
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Translates business events into [PostingFacts]. Certificates use specific identification (lot cost basis);
 * fungible allowances use FIFO or weighted-average cost layers per the entity's policy.
 */
@Component
@Transactional
class LedgerEventListeners(
    private val ledger: LedgerService,
    private val costLayers: CostLayerRepository,
    private val referenceData: ReferenceDataQuery,
    private val lotQuery: LotQuery,
    private val tradeQuery: TradeQuery,
    private val clock: Clock,
    private val fixings: IndexFixings,
) {
    private val log = LoggerFactory.getLogger(LedgerEventListeners::class.java)

    @EventListener
    fun on(event: LotReceived) {
        val lot = event.lot
        val cost = lotCost(lot) ?: return
        ledger.post(
            facts(
                lot.holderEntityId,
                "LotReceived",
                event.eventId,
                lot.acquisitionDate,
                lot.costCurrency ?: base(lot.holderEntityId),
                "LOT",
                lot.id.toString(),
                "Certificates received ${lot.issuanceRef} ${lot.range} (${lot.quantityMwh} MWh)",
                mapOf("cost" to cost, "quantity" to BigDecimal(lot.quantityMwh)),
                mapOf("acquisitionSource" to lot.acquisitionSource.name),
                lotDimensions(lot),
            ),
        )
    }

    @EventListener
    fun on(event: LotCancelled) = postLotDerecognition(event.lot, "LotCancelled", event.eventId, "Certificates cancelled for ${event.details.purpose}")

    @EventListener
    fun on(event: LotExpired) = postLotDerecognition(event.lot, "LotExpired", event.eventId, "Certificates expired")

    private fun postLotDerecognition(lot: LotView, type: String, eventId: UUID, description: String) {
        val cost = lotCost(lot) ?: return
        ledger.post(
            facts(
                lot.holderEntityId,
                type,
                eventId,
                LocalDate.now(clock),
                lot.costCurrency ?: base(lot.holderEntityId),
                "LOT",
                lot.id.toString(),
                "$description ${lot.issuanceRef} ${lot.range}",
                mapOf("cost" to cost, "quantity" to BigDecimal(lot.quantityMwh)),
                emptyMap(),
                lotDimensions(lot),
            ),
        )
    }

    @EventListener
    fun on(event: AllocationLineConfirmed) {
        val obligation = event.obligation
        if (obligation.kind != ObligationKind.SALE && obligation.kind != ObligationKind.INTERCOMPANY) return
        val lot = event.line.reservedLotId?.let(lotQuery::lot) ?: return
        val tradeObligation = obligation.sourceRef?.let(tradeQuery::obligation)
        val quantity = BigDecimal(event.line.quantityMwh)
        val amounts = buildMap {
            put("quantity", quantity)
            lotCost(lot)?.let { put("cost", it) }
            tradeObligation?.let { delivery ->
                val price = deliveredUnitPrice(delivery, fixings)
                if (price == null) {
                    log.warn("No delivered price for {} {} on {}; revenue not posted", delivery.priceType, delivery.tradeNumber, delivery.deliveryDate)
                } else {
                    put("proceeds", (price * quantity).setScale(2, RoundingMode.HALF_UP))
                }
            }
        }
        ledger.post(
            facts(
                obligation.holderEntityId,
                "SaleDelivered",
                event.eventId,
                LocalDate.now(clock),
                tradeObligation?.priceCurrency?.currencyCode ?: base(obligation.holderEntityId),
                "ALLOCATION_LINE",
                event.line.id.toString(),
                "Delivered ${event.line.quantityMwh} MWh on ${obligation.tradeRef ?: obligation.id}",
                amounts,
                mapOf("obligationKind" to obligation.kind.name),
                lotDimensions(
                    lot,
                ) + mapOf("trade" to obligation.tradeRef, "obligation" to obligation.id.toString(), "counterparty" to obligation.counterpartyId?.toString()),
            ),
        )
    }

    @EventListener
    fun on(event: HoldingChanged) {
        val holding = event.holding
        val movement = event.movement
        val entity = referenceData.requireLegalEntity(holding.holderEntityId)
        val policy = entity.accountingPolicy
        val assetKey = "ALLOWANCE:${holding.registryAccountId}:${holding.productCode}"
        val quantity = BigDecimal(kotlin.math.abs(movement.quantity))
        val currency = movement.costCurrency ?: entity.baseCurrency.currencyCode
        val amounts = mutableMapOf("quantity" to quantity)
        when (movement.type) {
            AllowanceMovementType.PURCHASE, AllowanceMovementType.TRANSFER_IN, AllowanceMovementType.OPENING_BALANCE -> {
                val unitCost = movement.costPerUnit ?: BigDecimal.ZERO
                costLayers.insert(
                    CostLayer(Ids.newId(), entity.id, assetKey, movement.effectiveDate, quantity, quantity, unitCost, currency, movement.reference),
                )
                if (unitCost.signum() > 0) amounts["cost"] = (unitCost * quantity).setScale(2, RoundingMode.HALF_EVEN)
            }
            AllowanceMovementType.FREE_ALLOCATION -> {
                val fairValue = policy.freeAllocationPolicy == FreeAllocationPolicy.FAIR_VALUE
                val unitCost = if (fairValue) movement.costPerUnit ?: BigDecimal.ZERO else BigDecimal.ZERO
                costLayers.insert(
                    CostLayer(Ids.newId(), entity.id, assetKey, movement.effectiveDate, quantity, quantity, unitCost, currency, movement.reference),
                )
                if (unitCost.signum() > 0) amounts["cost"] = (unitCost * quantity).setScale(2, RoundingMode.HALF_EVEN)
            }
            AllowanceMovementType.SALE, AllowanceMovementType.SURRENDER, AllowanceMovementType.TRANSFER_OUT, AllowanceMovementType.DELETION -> {
                val method = if (policy.allowanceCostingMethod == AllowanceCostingMethod.FIFO) CostingMethod.FIFO else CostingMethod.WEIGHTED_AVERAGE
                val consumption = CostingEngine.consume(costLayers.open(entity.id, assetKey), quantity, method)
                consumption.consumed.forEach { (layer, qty) -> costLayers.reduce(layer.id, qty) }
                if (consumption.cost.signum() > 0) amounts["cost"] = consumption.cost
                if (movement.type == AllowanceMovementType.SALE) {
                    movement.costPerUnit?.let {
                        amounts["proceeds"] = (it * quantity).setScale(2, RoundingMode.HALF_UP)
                    }
                }
            }
            AllowanceMovementType.CORRECTION -> correction(entity.id, assetKey, movement.quantity, movement, currency, policy, amounts)
        }
        ledger.post(
            facts(
                entity.id,
                "HoldingChanged",
                event.eventId,
                movement.effectiveDate,
                currency,
                "ALLOWANCE_MOVEMENT",
                movement.seq.toString(),
                "${movement.type} ${quantity.toPlainString()} ${holding.productCode} on account ${holding.registryAccountId}",
                amounts,
                mapOf(
                    "movementType" to movement.type.name,
                    "allowanceClassification" to policy.allowanceClassification.name,
                    "freeAllocationPolicy" to policy.freeAllocationPolicy.name,
                    "product" to holding.productCode,
                ),
                mapOf(
                    "registryAccount" to holding.registryAccountId.toString(),
                    "product" to holding.productCode,
                    "complianceYear" to movement.complianceYear?.toString(),
                ),
            ),
        )
    }

    /**
     * A reconciliation correction moves the cost layers with the holding: units found in the registry come in at the
     * statement's price (zero when none is given), units missing go out at cost like a sale. The difference is booked
     * to the reconciliation result, so layers, the asset account and the holding stay in step.
     */
    @Suppress("LongParameterList") // the movement's parts the two directions need
    private fun correction(
        entityId: UUID,
        assetKey: String,
        signedQuantity: Long,
        movement: AllowanceMovementView,
        currency: String,
        policy: AccountingPolicy,
        amounts: MutableMap<String, BigDecimal>,
    ) {
        val quantity = BigDecimal(kotlin.math.abs(signedQuantity))
        if (signedQuantity > 0) {
            val unitCost = movement.costPerUnit ?: BigDecimal.ZERO
            costLayers.insert(CostLayer(Ids.newId(), entityId, assetKey, movement.effectiveDate, quantity, quantity, unitCost, currency, movement.reference))
            if (unitCost.signum() > 0) amounts["correctionGain"] = (unitCost * quantity).setScale(2, RoundingMode.HALF_EVEN)
        } else if (signedQuantity < 0) {
            val method = if (policy.allowanceCostingMethod == AllowanceCostingMethod.FIFO) CostingMethod.FIFO else CostingMethod.WEIGHTED_AVERAGE
            val consumption = CostingEngine.consume(costLayers.open(entityId, assetKey), quantity, method)
            consumption.consumed.forEach { (layer, qty) -> costLayers.reduce(layer.id, qty) }
            if (consumption.cost.signum() > 0) amounts["correctionLoss"] = consumption.cost
        }
    }

    private fun lotCost(lot: LotView): BigDecimal? = lot.costBasisPerMwh?.takeIf {
        it.signum() > 0
    }?.let { (it * BigDecimal(lot.quantityMwh)).setScale(2, RoundingMode.HALF_EVEN) }

    private fun lotDimensions(lot: LotView): Map<String, String?> =
        mapOf(
            "lot" to lot.id.toString(),
            "rootLot" to lot.rootLotId.toString(),
            "device" to lot.deviceGsrn,
            "registryAccount" to lot.registryAccountId.toString(),
        )

    private fun base(entityId: UUID): String = referenceData.requireLegalEntity(entityId).baseCurrency.currencyCode

    private fun facts(
        entity: UUID,
        type: String,
        eventId: UUID?,
        date: LocalDate,
        currency: String,
        sourceType: String,
        sourceRef: String,
        description: String,
        amounts: Map<String, BigDecimal>,
        attributes: Map<String, String>,
        dimensions: Map<String, String?>,
    ) = PostingFacts(entity, type, eventId, date, currency, sourceType, sourceRef, description, amounts, attributes, dimensions)
}
