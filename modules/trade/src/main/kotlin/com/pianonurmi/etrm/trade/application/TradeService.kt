package com.pianonurmi.etrm.trade.application

import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
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
    /** Trades with notional at or above this amount (in [thresholdCurrency]) need four-eyes approval; 0 means every trade. */
    @Value("\${etrm.trade.approval-threshold:0}") private val approvalThreshold: BigDecimal,
    private val fxRates: FxRates,
    @Value("\${etrm.trade.approval-threshold-currency:EUR}") private val thresholdCurrency: String,
) : TradeCommand, TradeQuery {
    // ----------------------------------------------------------------------------------------------------------
    // Capture and amendment
    // ----------------------------------------------------------------------------------------------------------

    private fun authorise(legalEntityId: UUID, vararg roles: Role) {
        val actor = actorProvider.current()
        actor.requireRole(*roles)
        actor.requireEntity(legalEntityId)
    }

    override fun capture(command: CaptureTrade): TradeView {
        authorise(command.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        val actor = actorProvider.current().userId
        val counterparty = referenceData.requireCounterparty(command.counterpartyId)
        val trade = build(command, actor)
        repository.insert(trade, actor, command.reason ?: "captured")
        val submitted = TradeAggregate(trade).submitForApproval(requiresApproval(trade))
        repository.saveVersion(submitted, actor, "submitted")
        events.publishEvent(TradeCaptured(submitted))
        openObligationsIfDue(submitted)
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
            // The mirror delivers to (or receives from) the original entity's own account.
            legs = command.legs.map {
                it.copy(side = it.side.opposite(), deliveryAccountId = null, counterpartyAccountId = it.deliveryAccountId, counterpartyAccountRef = null)
            },
            mirrorBookId = null,
        )
        val mirror = build(mirrorCommand, actor).copy(intercompanyLinkId = original.id)
        repository.insert(mirror, actor, "intercompany mirror of ${original.tradeNumber}")
        val submitted = TradeAggregate(mirror).submitForApproval(requiresApproval(mirror))
        repository.saveVersion(submitted, actor, "submitted")
        val linked = original.copy(intercompanyLinkId = mirror.id, version = original.version + 1)
        repository.saveVersion(linked, actor, "linked to ${mirror.tradeNumber}")
        events.publishEvent(TradeCaptured(submitted))
        openObligationsIfDue(submitted)
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
            legs = command.legs.mapIndexed { index, leg -> buildLeg(command.productFamily, command.legalEntityId, command.counterpartyId, index + 1, leg) },
            obligationsOpenAt = entity.obligationsOpenAt,
        )
    }

    private fun buildLeg(family: ProductFamily, legalEntityId: UUID, counterpartyId: UUID, legNo: Int, leg: CaptureLeg): TradeLegView {
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
        val bucket = criteriaRef?.let { ref ->
            val firstDelivery = leg.deliveries.minOf { it.deliveryDate }
            buckets.bucketFor(ref.id, leg.criterion!!, criteria.contextFor(firstDelivery))
        }
        val counterpartyAccount = counterpartyAccount(counterpartyId, legNo, leg)
        return TradeLegView(
            id = Ids.newId(),
            legNo = legNo,
            side = leg.side,
            energyCarrier = leg.energyCarrier ?: EnergyCarrier.ELECTRICITY.takeIf { family == ProductFamily.GUARANTEE_OF_ORIGIN },
            allowanceProductCode = leg.allowanceProductCode?.uppercase(),
            criteriaId = criteriaRef?.id,
            criterion = leg.criterion,
            criteriaSummary = criteriaRef?.summary,
            bucketCode = bucket?.bucket?.code,
            bucketMatch = bucket?.kind,
            quantity = leg.quantity,
            unit = leg.unit,
            price = leg.price,
            priceCurrency = Currency.getInstance(leg.priceCurrency.uppercase()),
            priceType = leg.priceType,
            priceIndex = leg.priceIndex,
            deliveryAccountId = leg.deliveryAccountId,
            counterpartyAccountRef = counterpartyAccount?.let { "${it.registryCode}:${it.accountNumber}" } ?: leg.counterpartyAccountRef,
            counterpartyAccountId = counterpartyAccount?.id,
            deliveries = leg.deliveries.sortedBy { it.deliveryDate }.mapIndexed { i, d ->
                DeliveryScheduleView(Ids.newId(), i + 1, d.deliveryWindow, d.deliveryDate, d.quantity, BigDecimal.ZERO, d.paymentDate, DeliveryStatus.PENDING)
            },
        )
    }

    /**
     * The counterparty's registry account for a leg: the explicit account (which must belong to the counterparty, or
     * for intercompany counterparties to the represented legal entity, and be active), or the account a textual
     * reference resolves to. Free-text references that match no registered account stay text.
     */
    private fun counterpartyAccount(counterpartyId: UUID, legNo: Int, leg: CaptureLeg): RegistryAccountView? {
        val counterparty = referenceData.requireCounterparty(counterpartyId)

        fun belongs(account: RegistryAccountView) = when (account.holderType) {
            HolderType.COUNTERPARTY -> account.holderId == counterparty.id
            HolderType.LEGAL_ENTITY -> account.holderId == counterparty.linkedLegalEntityId
        }
        leg.counterpartyAccountId?.let { id ->
            val account = referenceData.requireRegistryAccount(id)
            if (!belongs(
                    account,
                )
            ) {
                throw ValidationException("Leg $legNo: account ${account.accountNumber} does not belong to counterparty ${counterparty.code}")
            }
            if (!account.active) throw ValidationException("Leg $legNo: counterparty account ${account.accountNumber} is inactive")
            return account
        }
        return leg.counterpartyAccountRef?.let(referenceData::registryAccountByRef)?.takeIf { belongs(it) && it.active }
    }

    override fun amend(tradeId: UUID, command: AmendTrade): TradeView {
        val actor = actorProvider.current().userId
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        if (current.version != command.expectedVersion) throw StaleVersionException("Trade", tradeId, command.expectedVersion, current.version)
        val legs = command.legs?.mapIndexed {
            index,
            leg,
            ->
            buildLeg(current.productFamily, current.legalEntityId, current.counterpartyId, index + 1, leg)
        } ?: current.legs
        val amended = current.copy(
            tradeDate = command.tradeDate ?: current.tradeDate,
            brokerId = command.brokerId ?: current.brokerId,
            masterAgreementRef = command.masterAgreementRef ?: current.masterAgreementRef,
            externalRef = command.externalRef ?: current.externalRef,
            legs = legs,
        )
        val wasFirm = current.obligationsOpen
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
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.MIDDLE_OFFICE, Role.RISK)
        val next = TradeAggregate(current).approve(actor, clock.instant())
        save(next, actor, "approved")
        events.publishEvent(TradeApproved(next))
        openObligationsIfDue(next)
        return next
    }

    override fun reject(tradeId: UUID, reason: String): TradeView {
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.MIDDLE_OFFICE, Role.RISK)
        val next = TradeAggregate(current).reject()
        save(next, actorProvider.current().userId, "rejected: $reason")
        return next
    }

    override fun confirm(tradeId: UUID, command: ConfirmTrade): TradeView {
        authorise(requireTrade(tradeId).legalEntityId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        return confirmAs(tradeId, command, actorProvider.current().userId)
    }

    /** Confirmation on behalf of [actor], e.g. `system` when the counterparty's confirmation matched ours. */
    fun confirmAs(tradeId: UUID, command: ConfirmTrade, actor: String): TradeView {
        val current = locked(tradeId)
        val next = TradeAggregate(current).confirm(command.confirmationRef, command.confirmedAt ?: clock.instant())
        save(next, actor, "confirmed via ${command.method}")
        events.publishEvent(TradeConfirmed(next))
        if (!current.obligationsOpen) obligationsOf(next).forEach { events.publishEvent(DeliveryObligationOpened(it)) }
        next.intercompanyLinkId?.let { linkedId ->
            val linked = locked(linkedId)
            if (linked.status == TradeStatus.APPROVED) confirmAs(linkedId, command, actor)
        }
        return next
    }

    override fun cancel(tradeId: UUID, reason: String): TradeView {
        val current = locked(tradeId)
        authorise(current.legalEntityId, Role.TRADER, Role.MIDDLE_OFFICE)
        val next = TradeAggregate(current).cancel()
        save(next, actorProvider.current().userId, "cancelled: $reason")
        events.publishEvent(TradeCancelled(next, reason))
        if (current.obligationsOpen) {
            obligationsOf(current).filter {
                it.status != DeliveryStatus.DELIVERED
            }.forEach { events.publishEvent(DeliveryObligationClosed(it, "cancelled")) }
        }
        return next
    }

    override fun recordDelivery(tradeId: UUID, command: RecordDelivery): TradeView {
        val actor = actorProvider.current().userId
        val current = locked(tradeId)
        if (command.idempotencyKey != null && repository.deliveryRecorded(command.idempotencyKey)) return current
        val (next, schedule) = TradeAggregate(current).recordDelivery(command.scheduleId, command.quantity)
        save(next, actor, "delivery ${command.quantity} on ${command.deliveredOn}")
        val deliveryId = repository.appendDeliveryRecord(
            command.scheduleId,
            command.quantity,
            command.deliveredOn,
            command.reference,
            command.idempotencyKey,
            actor,
        )
        val obligation = obligationsOf(next).first { it.scheduleId == schedule.id }
        events.publishEvent(DeliveryRecorded(next, deliveryId, obligation, command.quantity, command.deliveredOn, command.reference))
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

    /**
     * Whether a trade needs a second person's approval: its notional, converted at the trade date's rate, against the
     * threshold. A trade whose currency cannot be converted is treated as above it.
     */
    private fun requiresApproval(trade: TradeView): Boolean {
        if (approvalThreshold.signum() == 0) return true
        val converted = trade.legs.map { leg ->
            fxRates.rate(trade.tradeDate, leg.priceCurrency.currencyCode, thresholdCurrency)?.let { leg.notional * it.rate } ?: return true
        }
        return converted.fold(BigDecimal.ZERO, BigDecimal::add) >= approvalThreshold
    }

    private fun locked(id: UUID): TradeView = repository.lock(id) ?: throw NotFoundException("Trade", id)

    private fun save(trade: TradeView, actor: String, reason: String?) {
        if (!repository.saveVersion(trade, actor, reason)) throw StaleVersionException("Trade", trade.id, trade.version - 1, -1)
    }

    /** Approved trades of entities whose obligations open at approval oblige delivery from now on. */
    private fun openObligationsIfDue(trade: TradeView) {
        if (trade.status == TradeStatus.APPROVED && trade.obligationsOpen) obligationsOf(trade).forEach { events.publishEvent(DeliveryObligationOpened(it)) }
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
                counterpartyAccountId = leg.counterpartyAccountId,
                price = leg.price,
                priceCurrency = leg.priceCurrency,
                priceType = leg.priceType,
                status = d.status,
                priceIndex = leg.priceIndex,
            )
        }
    }
}
