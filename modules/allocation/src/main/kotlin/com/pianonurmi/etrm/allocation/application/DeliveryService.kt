package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.DeliverAllowances
import com.pianonurmi.etrm.allocation.api.DeliveryCommand
import com.pianonurmi.etrm.allocation.api.MatchReceipt
import com.pianonurmi.etrm.allocation.api.ReceiptMatch
import com.pianonurmi.etrm.allocation.api.ReceiptSource
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.HoldingChanged
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotReceived
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.registry.api.AssetKind
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.SubmitAllowanceTransfer
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Deliveries that do not go through an allocation plan:
 * - allowance sales are delivered by a Union Registry transfer; its confirmation records the trade delivery;
 * - purchases (certificate lots and allowances) are matched on receipt, by the trade number given as the receipt's
 *   reference, or manually.
 *
 * Listeners never fail the transaction that published the event: a receipt that cannot be matched stays unmatched.
 */
@Service
@Transactional
class DeliveryService(
    private val tradeQuery: TradeQuery,
    private val tradeCommand: TradeCommand,
    private val instructions: RegistryInstructionCommand,
    private val lots: LotQuery,
    private val allowances: AllowanceHoldingQuery,
    private val savepoints: Savepoints,
) : DeliveryCommand {
    private val log = LoggerFactory.getLogger(DeliveryService::class.java)

    override fun deliverAllowances(command: DeliverAllowances): RegistryInstructionView {
        val obligation = tradeQuery.obligation(command.scheduleId) ?: throw NotFoundException("DeliverySchedule", command.scheduleId)
        if (obligation.side != Side.SELL || obligation.productFamily != ProductFamily.EMISSION_ALLOWANCE) {
            throw ValidationException("Schedule ${command.scheduleId} is not an allowance sale")
        }
        requireDeliverable(obligation)
        val quantity = command.quantity ?: wholeUnits(obligation.remainingQuantity)
        if (quantity <= 0 || BigDecimal(quantity) > obligation.remainingQuantity) {
            throw ValidationException("Quantity $quantity must be positive and at most the remaining ${obligation.remainingQuantity}")
        }
        if (obligation.priceType != PriceType.FIXED) throw ValidationException("Trade ${obligation.tradeNumber} has no fixed price yet")
        val from = obligation.deliveryAccountId ?: throw ValidationException("Trade ${obligation.tradeNumber} has no delivery account")
        val to = obligation.counterpartyAccountId
            ?: throw ValidationException("Trade ${obligation.tradeNumber} has no registered counterparty account")
        return instructions.submitAllowanceTransfer(
            SubmitAllowanceTransfer(
                fromAccountId = from,
                productCode = obligation.allowanceProductCode ?: throw ValidationException("Trade ${obligation.tradeNumber} has no allowance product"),
                quantity = quantity,
                toAccountId = to,
                idempotencyKey = command.idempotencyKey ?: "delivery:${obligation.scheduleId}",
                movementType = AllowanceMovementType.SALE,
                unitPrice = obligation.price,
                priceCurrency = obligation.priceCurrency.currencyCode,
                obligationRef = obligation.scheduleId,
            ),
        )
    }

    override fun matchReceipt(command: MatchReceipt): ReceiptMatch {
        val obligation = tradeQuery.obligation(command.scheduleId) ?: throw NotFoundException("DeliverySchedule", command.scheduleId)
        if (obligation.side != Side.BUY) throw ValidationException("Schedule ${command.scheduleId} is not a purchase")
        val receipt = receipt(command.source())
        if (receipt.family != obligation.productFamily || receipt.holderEntityId != obligation.legalEntityId) {
            throw ValidationException("The receipt does not belong to trade ${obligation.tradeNumber}")
        }
        if (receipt.productCode != null && receipt.productCode != obligation.allowanceProductCode) {
            throw ValidationException("Received ${receipt.productCode}; trade ${obligation.tradeNumber} buys ${obligation.allowanceProductCode}")
        }
        requireDeliverable(obligation)
        if (BigDecimal(receipt.quantity) > obligation.remainingQuantity) {
            throw ValidationException("Receipt of ${receipt.quantity} exceeds the remaining ${obligation.remainingQuantity}")
        }
        return record(obligation, receipt)
    }

    // ----------------------------------------------------------------------------------------------------------
    // Listeners
    // ----------------------------------------------------------------------------------------------------------

    /** A confirmed allowance sale transfer is the delivery of the trade it was submitted for. */
    @EventListener
    fun on(event: InstructionConfirmed) {
        val instruction = event.instruction
        if (instruction.assetKind != AssetKind.ALLOWANCE || instruction.type != InstructionType.ALLOWANCE_TRANSFER) return
        val scheduleId = instruction.obligationRef ?: return
        val obligation = tradeQuery.obligation(scheduleId)?.takeIf { it.side == Side.SELL } ?: return
        quietly("allowance delivery for ${obligation.tradeNumber}") {
            tradeCommand.recordDelivery(
                obligation.tradeId,
                RecordDelivery(
                    scheduleId,
                    BigDecimal(instruction.quantity),
                    LocalDate.ofInstant(requireNotNull(instruction.confirmedAt), java.time.ZoneOffset.UTC),
                    instruction.externalRef,
                    idempotencyKey = "instruction:${instruction.id}",
                ),
            )
        }
    }

    /** Certificates received with the purchase trade number as their acquisition reference. */
    @EventListener
    fun on(event: LotReceived) {
        val lot = event.lot
        if (lot.acquisitionSource != AcquisitionSource.TRANSFER_IN) return
        val tradeNumber = lot.acquisitionRef ?: return
        autoMatch(tradeNumber, Receipt.of(lot))
    }

    /** Allowances bought or transferred in with the purchase trade number as the movement reference. */
    @EventListener
    fun on(event: HoldingChanged) {
        val movement = event.movement
        if (movement.type != AllowanceMovementType.PURCHASE && movement.type != AllowanceMovementType.TRANSFER_IN) return
        val tradeNumber = movement.reference ?: return
        autoMatch(tradeNumber, Receipt.of(event.holding, movement))
    }

    // ----------------------------------------------------------------------------------------------------------

    private fun autoMatch(tradeNumber: String, receipt: Receipt) {
        val trade = tradeQuery.byNumber(tradeNumber) ?: return
        if (trade.legalEntityId != receipt.holderEntityId || trade.status !in DELIVERABLE) return
        val candidate = tradeQuery.openObligations(receipt.holderEntityId, Side.BUY, receipt.family)
            .filter { it.tradeId == trade.id && it.status != DeliveryStatus.CANCELLED && it.status != DeliveryStatus.DELIVERED }
            .filter { receipt.productCode == null || it.allowanceProductCode == receipt.productCode }
            .filter { it.deliveryAccountId == null || it.deliveryAccountId == receipt.accountId }
            .sortedBy { it.deliveryDate }
            .firstOrNull { BigDecimal(receipt.quantity) <= it.remainingQuantity }
        if (candidate == null) {
            log.info("Receipt {} names trade {} but fits none of its open purchase schedules; match it manually", receipt.key, tradeNumber)
            return
        }
        quietly("receipt ${receipt.key} on $tradeNumber") { record(candidate, receipt) }
    }

    private fun record(obligation: DeliveryObligationView, receipt: Receipt): ReceiptMatch {
        tradeCommand.recordDelivery(
            obligation.tradeId,
            RecordDelivery(obligation.scheduleId, BigDecimal(receipt.quantity), receipt.receivedOn, receipt.reference, idempotencyKey = receipt.key),
        )
        return ReceiptMatch(obligation.scheduleId, obligation.tradeId, obligation.tradeNumber, receipt.quantity, receipt.key)
    }

    private fun receipt(source: ReceiptSource): Receipt = when (source) {
        is ReceiptSource.Lot -> {
            val lot = lots.requireLot(source.lotId)
            if (lot.acquisitionSource != AcquisitionSource.TRANSFER_IN) throw ValidationException("Lot ${lot.id} was not received by transfer")
            Receipt.of(lot)
        }
        is ReceiptSource.AllowanceMovement -> {
            val movement = allowances.movements(source.holdingId).firstOrNull { it.seq == source.movementSeq }
                ?: throw NotFoundException("AllowanceMovement", "${source.holdingId}/${source.movementSeq}")
            if (movement.type != AllowanceMovementType.PURCHASE && movement.type != AllowanceMovementType.TRANSFER_IN) {
                throw ValidationException("Movement ${movement.seq} is a ${movement.type}, not a receipt")
            }
            val holding = allowances.holdingById(source.holdingId) ?: throw NotFoundException("AllowanceHolding", source.holdingId)
            Receipt.of(holding, movement)
        }
    }

    private fun requireDeliverable(obligation: DeliveryObligationView) {
        val trade = tradeQuery.requireTrade(obligation.tradeId)
        if (trade.status !in DELIVERABLE) throw ConflictException("Trade ${trade.tradeNumber} is ${trade.status}; it cannot be delivered")
        if (obligation.status == DeliveryStatus.CANCELLED || obligation.status == DeliveryStatus.DELIVERED) {
            throw ConflictException("Schedule ${obligation.scheduleId} is ${obligation.status}")
        }
    }

    /** Runs [block] in a savepoint so a refused delivery leaves the publishing transaction intact. */
    private fun quietly(what: String, block: () -> Unit) {
        try {
            savepoints.attempt(block)
        } catch (e: DomainException) {
            log.warn("Could not record {}: {}", what, e.message)
        }
    }

    private fun wholeUnits(quantity: BigDecimal): Long = try {
        quantity.longValueExact()
    } catch (_: ArithmeticException) {
        throw ValidationException("Allowances move in whole units; remaining quantity is $quantity")
    }

    private companion object {
        val DELIVERABLE = setOf(TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)
    }
}

/** A receipt reduced to what matching needs; [key] makes recording it idempotent. */
private data class Receipt(
    val key: String,
    val holderEntityId: UUID,
    val accountId: UUID,
    val family: ProductFamily,
    val productCode: String?,
    val quantity: Long,
    val receivedOn: LocalDate,
    val reference: String?,
) {
    companion object {
        fun of(lot: com.pianonurmi.etrm.inventory.api.LotView) = Receipt(
            key = "lot-receipt:${lot.id}",
            holderEntityId = lot.holderEntityId,
            accountId = lot.registryAccountId,
            family = ProductFamily.GUARANTEE_OF_ORIGIN,
            productCode = null,
            quantity = lot.quantityMwh,
            receivedOn = lot.acquisitionDate,
            reference = lot.acquisitionRef,
        )

        fun of(holding: com.pianonurmi.etrm.inventory.api.AllowanceHoldingView, movement: com.pianonurmi.etrm.inventory.api.AllowanceMovementView) =
            Receipt(
                key = "holding-receipt:${holding.id}:${movement.seq}",
                holderEntityId = holding.holderEntityId,
                accountId = holding.registryAccountId,
                family = ProductFamily.EMISSION_ALLOWANCE,
                productCode = holding.productCode,
                quantity = kotlin.math.abs(movement.quantity),
                receivedOn = movement.effectiveDate,
                reference = movement.reference,
            )
    }
}
