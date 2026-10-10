package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.CaptureLeg
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ConfirmTrade
import com.pianonurmi.etrm.trade.api.DeliveryObligationChanged
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.DeliveryStatus
import com.pianonurmi.etrm.trade.api.RecordDelivery
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeCommand
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeSettled
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeVersionView
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.trade.domain.TradeAggregate
import com.pianonurmi.etrm.trade.domain.TradeRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.Currency
import java.util.UUID

@Service
@Transactional
class TradeService(
    private val repository: TradeRepository,
    private val referenceData: ReferenceDataQuery,
    private val criteria: CriteriaService,
    private val buckets: ProductBucketQuery,
    private val catalogue: ProductCatalogueQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    /** Trades with notional at or above this EUR amount need four-eyes approval; 0 means every trade. */
    @Value("\${etrm.trade.approval-threshold:0}") private val approvalThreshold: BigDecimal,
) : TradeCommand, TradeQuery {
    // ----------------------------------------------------------------------------------------------------------
    // Capture and amendment
    // ----------------------------------------------------------------------------------------------------------

    override fun capture(command: CaptureTrade): TradeView {
        val actor = actorProvider.current().userId
        val counterparty = referenceData.requireCounterparty(command.counterpartyId)
        val trade = build(command, actor)
        repository.insert(trade, actor, command.reason ?: "captured")
        val submitted = TradeAggregate(trade).submitForApproval(requiresApproval(trade))
        repository.saveVersion(submitted, actor, "submitted")
        events.publishEvent(TradeCaptured(submitted))
        if (counterparty.counterpartyType == CounterpartyType.INTERCOMPANY) captureMirror(command, submitted, counterparty.linkedLegalEntityId!!, actor)
        return submitted
    }

    private fun captureMirror(command: CaptureTrade, original: TradeView, mirrorEntityId: UUID, actor: String) {
        val mirrorBook = command.mirrorBookId ?: throw ValidationException("Intercompany trades require mirrorBookId in the counterparty legal entity")
        if (referenceData.requireBook(
                mirrorBook,
            ).legalEntityId != mirrorEntityId
        ) {
            throw ValidationException("mirrorBookId must belong to legal entity $mirrorEntityId")
        }
        val mirrorCounterparty = referenceData.counterparties().firstOrNull { it.linkedLegalEntityId == command.legalEntityId }
            ?: throw ValidationException("No intercompany counterparty record represents legal entity ${command.legalEntityId}")
        val mirrorCommand = command.copy(
            legalEntityId = mirrorEntityId,
            bookId = mirrorBook,
            counterpartyId = mirrorCounterparty.id,
            legs = command.legs.map { it.copy(side = it.side.opposite(), deliveryAccountId = null, counterpartyAccountRef = it.deliveryAccountId?.toString()) },
            mirrorBookId = null,
        )
        val mirror = build(mirrorCommand, actor).copy(intercompanyLinkId = original.id)
        repository.insert(mirror, actor, "intercompany mirror of ${original.tradeNumber}")
        val submitted = TradeAggregate(mirror).submitForApproval(requiresApproval(mirror))
        repository.saveVersion(submitted, actor, "submitted")
        val linked = original.copy(intercompanyLinkId = mirror.id, version = original.version + 1)
        repository.saveVersion(linked, actor, "linked to ${mirror.tradeNumber}")
        events.publishEvent(TradeCaptured(submitted))
    }

    private fun build(command: CaptureTrade, actor: String): TradeView {
        val entity = referenceData.requireLegalEntity(command.legalEntityId)
        val book = referenceData.requireBook(command.bookId)
        if (book.legalEntityId != entity.id) throw ValidationException("Book ${book.code} does not belong to legal entity ${entity.code}")
        referenceData.requireCounterparty(command.counterpartyId)
        command.brokerId?.let(referenceData::requireCounterparty)
        if (command.legs.isEmpty()) throw ValidationException("A trade needs at least one leg")
        val id = Ids.newId()
        return TradeView(
            id = id,
            tradeNumber = repository.nextTradeNumber(command.tradeDate.year),
            legalEntityId = entity.id,
            bookId = book.id,
            counterpartyId = command.counterpartyId,
            productFamily = command.productFamily,
            instrumentType = command.instrumentType,
            tradeDate = command.tradeDate,
            trader = command.trader ?: actor,
            brokerId = command.brokerId,
            masterAgreementRef = command.masterAgreementRef,
            externalRef = command.externalRef,
            status = TradeStatus.DRAFT,
            version = 1,
            intercompanyLinkId = null,
            approvedBy = null,
            approvedAt = null,
            confirmedAt = null,
            confirmationRef = null,
            createdBy = actor,
            legs = command.legs.mapIndexed { index, leg -> buildLeg(command.productFamily, command.legalEntityId, index + 1, leg) },
        )
    }

    private fun buildLeg(family: ProductFamily, legalEntityId: UUID, legNo: Int, leg: CaptureLeg): TradeLegView {
        if (leg.quantity.signum() <= 0) throw ValidationException("Leg $legNo quantity must be positive")
        if (leg.price.signum() < 0) throw ValidationException("Leg $legNo price must not be negative")
        if (leg.deliveries.isEmpty()) throw ValidationException("Leg $legNo needs a delivery schedule")
        val scheduled = leg.deliveries.sumOf { it.quantity }
        if (scheduled.compareTo(leg.quantity) != 0) throw ValidationException("Leg $legNo deliveries total $scheduled but leg quantity is ${leg.quantity}")
        leg.deliveries.forEach { d ->
            if (d.quantity.signum() <= 0) throw ValidationException("Leg $legNo delivery quantities must be positive")
            if (d.deliveryDate !in d.deliveryWindow && d.deliveryDate != d.deliveryWindow.endExclusive) {
                throw ValidationException("Leg $legNo delivery date ${d.deliveryDate} must lie within ${d.deliveryWindow}")
            }
        }
        leg.deliveryAccountId?.let { accountId ->
            val account = referenceData.requireRegistryAccount(accountId)
            if (account.holderId != legalEntityId) throw ValidationException("Delivery account ${account.accountNumber} is not held by the trading entity")
        }
        val criteriaRef = when (family) {
            ProductFamily.GUARANTEE_OF_ORIGIN -> {
                val criterion = leg.criterion ?: throw ValidationException("Leg $legNo: GoO legs require delivery criteria")
                if (leg.unit != QuantityUnit.MWH) throw ValidationException("Leg $legNo: GoO quantities are in MWh")
                if (leg.quantity.stripTrailingZeros().scale() > 0) throw ValidationException("Leg $legNo: GoO quantities are whole MWh")
                criteria.store(criterion, "trade")
            }
            ProductFamily.EMISSION_ALLOWANCE -> {
                val code = leg.allowanceProductCode ?: throw ValidationException("Leg $legNo: allowance legs require allowanceProductCode")
                catalogue.allowanceProduct(code) ?: throw NotFoundException("AllowanceProduct", code)
                if (leg.unit != QuantityUnit.TCO2E) throw ValidationException("Leg $legNo: allowance quantities are in tCO2e")
                null
            }
        }
        val bucket = criteriaRef?.let { ref -> buckets.buckets().firstOrNull { it.criteriaId == ref.id }?.code }
        return TradeLegView(
            id = Ids.newId(),
            legNo = legNo,
            side = leg.side,
            energyCarrier = leg.energyCarrier ?: EnergyCarrier.ELECTRICITY.takeIf { family == ProductFamily.GUARANTEE_OF_ORIGIN },
            allowanceProductCode = leg.allowanceProductCode?.uppercase(),
            criteriaId = criteriaRef?.id,
            criterion = leg.criterion,
            criteriaSummary = criteriaRef?.summary,
            bucketCode = bucket,
            quantity = leg.quantity,
            unit = leg.unit,
            price = leg.price,
            priceCurrency = Currency.getInstance(leg.priceCurrency.uppercase()),
            priceType = leg.priceType,
            priceIndex = leg.priceIndex,
            deliveryAccountId = leg.deliveryAccountId,
            counterpartyAccountRef = leg.counterpartyAccountRef,
            deliveries = leg.deliveries.sortedBy { it.deliveryDate }.mapIndexed { i, d ->
                DeliveryScheduleView(Ids.newId(), i + 1, d.deliveryWindow, d.deliveryDate, d.quantity, BigDecimal.ZERO, d.paymentDate, DeliveryStatus.PENDING)
            },
        )
    }

    override fun amend(tradeId: UUID, command: AmendTrade): TradeView {
        val actor = actorProvider.current().userId
        val current = locked(tradeId)
        if (current.version != command.expectedVersion) throw StaleVersionException("Trade", tradeId, command.expectedVersion, current.version)
        val legs = command.legs?.mapIndexed { index, leg -> buildLeg(current.productFamily, current.legalEntityId, index + 1, leg) } ?: current.legs
        val amended = current.copy(
            tradeDate = command.tradeDate ?: current.tradeDate,
            brokerId = command.brokerId ?: current.brokerId,
            masterAgreementRef = command.masterAgreementRef ?: current.masterAgreementRef,
            externalRef = command.externalRef ?: current.externalRef,
            legs = legs,
        )
        val wasFirm = current.status.firm
        val next = TradeAggregate(current).amend(amended)
        save(next, actor, command.reason)
        events.publishEvent(TradeAmended(next, current.version))
        if (wasFirm) obligationsOf(current).forEach { events.publishEvent(DeliveryObligationClosed(it, "amended")) }
        return next
    }

    // ----------------------------------------------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------------------------------------------

    override fun approve(tradeId: UUID): TradeView {
        val actor = actorProvider.current().userId
        val next = TradeAggregate(locked(tradeId)).approve(actor, clock.instant())
        save(next, actor, "approved")
        events.publishEvent(TradeApproved(next))
        return next
    }

    override fun reject(tradeId: UUID, reason: String): TradeView {
        val next = TradeAggregate(locked(tradeId)).reject()
        save(next, actorProvider.current().userId, "rejected: $reason")
        return next
    }

    override fun confirm(tradeId: UUID, command: ConfirmTrade): TradeView {
        val actor = actorProvider.current().userId
        val next = TradeAggregate(locked(tradeId)).confirm(command.confirmationRef, command.confirmedAt ?: clock.instant())
        save(next, actor, "confirmed via ${command.method}")
        events.publishEvent(TradeConfirmed(next))
        obligationsOf(next).forEach { events.publishEvent(DeliveryObligationOpened(it)) }
        next.intercompanyLinkId?.let { linkedId ->
            val linked = locked(linkedId)
            if (linked.status == TradeStatus.APPROVED) confirm(linkedId, command)
        }
        return next
    }

    override fun cancel(tradeId: UUID, reason: String): TradeView {
        val current = locked(tradeId)
        val next = TradeAggregate(current).cancel()
        save(next, actorProvider.current().userId, "cancelled: $reason")
        events.publishEvent(TradeCancelled(next, reason))
        if (current.status.firm) {
            obligationsOf(current).filter {
                it.status != DeliveryStatus.DELIVERED
            }.forEach { events.publishEvent(DeliveryObligationClosed(it, "cancelled")) }
        }
        return next
    }

    override fun recordDelivery(tradeId: UUID, command: RecordDelivery): TradeView {
        val actor = actorProvider.current().userId
        val (next, schedule) = TradeAggregate(locked(tradeId)).recordDelivery(command.scheduleId, command.quantity)
        save(next, actor, "delivery ${command.quantity} on ${command.deliveredOn}")
        repository.appendDeliveryRecord(command.scheduleId, command.quantity, command.deliveredOn, command.reference, actor)
        val obligation = obligationsOf(next).first { it.scheduleId == schedule.id }
        events.publishEvent(DeliveryRecorded(next, obligation, command.quantity, command.deliveredOn, command.reference))
        if (schedule.status == DeliveryStatus.DELIVERED) {
            events.publishEvent(DeliveryObligationClosed(obligation, "delivered"))
        } else {
            events.publishEvent(DeliveryObligationChanged(obligation))
        }
        return next
    }

    override fun markSettled(tradeId: UUID): TradeView {
        val next = TradeAggregate(locked(tradeId)).markSettled()
        save(next, actorProvider.current().userId, "settled")
        events.publishEvent(TradeSettled(next))
        return next
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun trade(id: UUID): TradeView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun requireTrade(id: UUID): TradeView = repository.find(id) ?: throw NotFoundException("Trade", id)

    @Transactional(readOnly = true)
    override fun byNumber(tradeNumber: String): TradeView? = repository.byNumber(tradeNumber)

    @Transactional(readOnly = true)
    override fun trades(filter: TradeFilter): List<TradeView> = repository.list(filter)

    @Transactional(readOnly = true)
    override fun versions(tradeId: UUID): List<TradeVersionView> = repository.versions(tradeId)

    @Transactional(readOnly = true)
    override fun openObligations(legalEntityId: UUID, side: Side?, productFamily: ProductFamily?): List<DeliveryObligationView> =
        repository.openObligations(legalEntityId, side, productFamily)

    @Transactional(readOnly = true)
    override fun obligation(scheduleId: UUID): DeliveryObligationView? = repository.obligation(scheduleId)

    // ----------------------------------------------------------------------------------------------------------

    private fun requiresApproval(trade: TradeView): Boolean = approvalThreshold.signum() == 0 || trade.notional >= approvalThreshold

    private fun locked(id: UUID): TradeView = repository.lock(id) ?: throw NotFoundException("Trade", id)

    private fun save(trade: TradeView, actor: String, reason: String?) {
        if (!repository.saveVersion(trade, actor, reason)) throw StaleVersionException("Trade", trade.id, trade.version - 1, -1)
    }

    private fun obligationsOf(trade: TradeView): List<DeliveryObligationView> = trade.legs.flatMap { leg ->
        leg.deliveries.map { d ->
            DeliveryObligationView(
                scheduleId = d.id,
                tradeId = trade.id,
                tradeNumber = trade.tradeNumber,
                legId = leg.id,
                legalEntityId = trade.legalEntityId,
                bookId = trade.bookId,
                counterpartyId = trade.counterpartyId,
                side = leg.side,
                productFamily = trade.productFamily,
                energyCarrier = leg.energyCarrier,
                allowanceProductCode = leg.allowanceProductCode,
                criteriaId = leg.criteriaId,
                criterion = leg.criterion,
                quantity = d.quantity,
                deliveredQuantity = d.deliveredQuantity,
                deliveryWindow = d.deliveryWindow,
                deliveryDate = d.deliveryDate,
                deliveryAccountId = leg.deliveryAccountId,
                counterpartyAccountRef = leg.counterpartyAccountRef,
                price = leg.price,
                priceCurrency = leg.priceCurrency,
                priceType = leg.priceType,
                status = d.status,
            )
        }
    }
}
