package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.ObligationChanged
import com.pianonurmi.etrm.allocation.api.ObligationClosed
import com.pianonurmi.etrm.allocation.api.ObligationCommand
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationOpened
import com.pianonurmi.etrm.allocation.api.ObligationQuery
import com.pianonurmi.etrm.allocation.api.ObligationStatus
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.OpenObligation
import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.DeliveryObligationChanged
import com.pianonurmi.etrm.trade.api.DeliveryObligationClosed
import com.pianonurmi.etrm.trade.api.DeliveryObligationOpened
import com.pianonurmi.etrm.trade.api.DeliveryObligationView
import com.pianonurmi.etrm.trade.api.Side
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Obligations the matching engine serves. Sell-side GoO deliveries arrive from confirmed trades through events;
 * disclosure and own-consumption obligations are opened explicitly.
 */
@Service
@Transactional
class ObligationService(
    private val repository: ObligationRepository,
    private val criteria: CriteriaService,
    private val referenceData: ReferenceDataQuery,
    private val events: ApplicationEventPublisher,
) : ObligationCommand, ObligationQuery {
    override fun open(command: OpenObligation): ObligationView = upsert(command, reopen = false)

    /**
     * Opens or updates the obligation of [command]'s source. A closed obligation stays closed, except when its trade
     * becomes firm again ([reopen]): an amended trade closes its obligation and reopens it on the new confirmation.
     */
    private fun upsert(command: OpenObligation, reopen: Boolean): ObligationView {
        if (command.quantityMwh <= 0) throw ValidationException("Obligation quantity must be positive")
        if ((command.kind == ObligationKind.DISCLOSURE || command.kind == ObligationKind.OWN_CONSUMPTION) && command.cancellation == null) {
            throw ValidationException("${command.kind} obligations require cancellation details")
        }
        if ((command.kind == ObligationKind.SALE || command.kind == ObligationKind.INTERCOMPANY) && command.targetAccountRef == null) {
            throw ValidationException("${command.kind} obligations require a target account")
        }
        val existing = command.sourceRef?.let(repository::bySource)
        val view = ObligationView(
            id = existing?.id ?: Ids.newId(),
            kind = command.kind,
            holderEntityId = command.holderEntityId,
            bookId = command.bookId,
            counterpartyId = command.counterpartyId,
            tradeRef = command.tradeRef,
            sourceRef = command.sourceRef,
            criterion = command.criterion,
            criteriaSummary = criteria.describe(command.criterion),
            quantityMwh = command.quantityMwh,
            fulfilledMwh = existing?.fulfilledMwh ?: 0,
            deliveryWindow = command.deliveryWindow,
            deliveryDate = command.deliveryDate,
            targetRegistryCode = command.targetRegistryCode?.uppercase(),
            targetAccountRef = command.targetAccountRef,
            cancellation = command.cancellation,
            priorityClass = command.priorityClass ?: command.kind.defaultPriority,
            constraints = command.constraints,
            status = if ((existing?.fulfilledMwh ?: 0) >= command.quantityMwh) ObligationStatus.FULFILLED else ObligationStatus.OPEN,
            version = (existing?.version ?: 0) + 1,
        )
        if (existing == null) {
            repository.insert(view)
            events.publishEvent(ObligationOpened(view))
        } else if (existing.status == ObligationStatus.CLOSED && reopen) {
            if (!repository.update(view)) throw ConflictException("Obligation ${existing.id} changed concurrently")
            events.publishEvent(ObligationOpened(view))
        } else {
            if (existing.status == ObligationStatus.CLOSED) throw ConflictException("Obligation ${existing.id} is closed")
            if (view.quantityMwh < existing.fulfilledMwh) {
                throw ValidationException(
                    "Quantity ${view.quantityMwh} is below the ${existing.fulfilledMwh} MWh already fulfilled",
                )
            }
            if (!repository.update(view)) throw ConflictException("Obligation ${existing.id} changed concurrently")
            events.publishEvent(ObligationChanged(view))
        }
        return view
    }

    override fun close(obligationId: UUID, reason: String): ObligationView {
        val current = repository.lock(obligationId) ?: throw NotFoundException("Obligation", obligationId)
        if (current.status == ObligationStatus.CLOSED) return current
        val closed = current.copy(status = ObligationStatus.CLOSED, version = current.version + 1)
        if (!repository.update(closed)) throw ConflictException("Obligation $obligationId changed concurrently")
        events.publishEvent(ObligationClosed(closed, reason))
        return closed
    }

    override fun setConstraints(obligationId: UUID, constraints: List<AllocationConstraint>): ObligationView {
        val current = repository.lock(obligationId) ?: throw NotFoundException("Obligation", obligationId)
        if (current.status != ObligationStatus.OPEN) throw ConflictException("Obligation $obligationId is ${current.status}; its constraints are fixed")
        val updated = current.copy(constraints = constraints, version = current.version + 1)
        if (!repository.update(updated)) throw ConflictException("Obligation $obligationId changed concurrently")
        events.publishEvent(ObligationChanged(updated))
        return updated
    }

    /** Records fulfilment from a confirmed allocation line. */
    fun fulfil(obligationId: UUID, quantityMwh: Long): ObligationView {
        val current = repository.lock(obligationId) ?: throw NotFoundException("Obligation", obligationId)
        val fulfilled = current.fulfilledMwh + quantityMwh
        if (fulfilled > current.quantityMwh) throw ConflictException("Obligation $obligationId would be over-fulfilled ($fulfilled of ${current.quantityMwh})")
        val updated = current.copy(
            fulfilledMwh = fulfilled,
            status = if (fulfilled == current.quantityMwh) ObligationStatus.FULFILLED else current.status,
            version = current.version + 1,
        )
        if (!repository.update(updated)) throw ConflictException("Obligation $obligationId changed concurrently")
        events.publishEvent(ObligationChanged(updated))
        return updated
    }

    @Transactional(readOnly = true)
    override fun obligation(id: UUID): ObligationView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun requireObligation(id: UUID): ObligationView = repository.find(id) ?: throw NotFoundException("Obligation", id)

    @Transactional(readOnly = true)
    override fun bySource(sourceRef: UUID): ObligationView? = repository.bySource(sourceRef)

    @Transactional(readOnly = true)
    override fun open(holderEntityId: UUID): List<ObligationView> = repository.open(holderEntityId)

    // ----------------------------------------------------------------------------------------------------------
    // Trade events: sell-side GoO deliveries become obligations.
    // ----------------------------------------------------------------------------------------------------------

    @EventListener
    fun on(event: DeliveryObligationOpened) = syncFromTrade(event.obligation)

    @EventListener
    fun on(event: DeliveryObligationChanged) = syncFromTrade(event.obligation)

    @EventListener
    fun on(event: DeliveryObligationClosed) {
        repository.bySource(event.obligation.scheduleId)?.let { if (it.status != ObligationStatus.CLOSED) close(it.id, "trade: ${event.reason}") }
    }

    private fun syncFromTrade(delivery: DeliveryObligationView) {
        if (delivery.side != Side.SELL || delivery.productFamily != ProductFamily.GUARANTEE_OF_ORIGIN) return
        val criterion = delivery.criterion ?: delivery.criteriaId?.let(criteria::load) ?: return
        val counterparty = referenceData.counterparty(delivery.counterpartyId)
        val kind = if (counterparty?.counterpartyType == CounterpartyType.INTERCOMPANY) ObligationKind.INTERCOMPANY else ObligationKind.SALE
        val (registry, account) = parseAccountRef(delivery.counterpartyAccountRef)
        upsert(
            OpenObligation(
                kind = kind,
                holderEntityId = delivery.legalEntityId,
                bookId = delivery.bookId,
                counterpartyId = delivery.counterpartyId,
                tradeRef = delivery.tradeNumber,
                sourceRef = delivery.scheduleId,
                criterion = criterion,
                quantityMwh = delivery.quantity.toLong(),
                deliveryWindow = delivery.deliveryWindow,
                deliveryDate = delivery.deliveryDate,
                targetRegistryCode = registry,
                targetAccountRef = account,
            ),
            reopen = true,
        )
    }

    companion object {
        /** Counterparty accounts on trades are written as `REGISTRY_CODE:account-number`. */
        fun parseAccountRef(ref: String?): Pair<String?, String?> {
            if (ref.isNullOrBlank()) return null to null
            val idx = ref.indexOf(':')
            return if (idx > 0) ref.substring(0, idx).uppercase() to ref.substring(idx + 1) else null to ref
        }
    }
}
