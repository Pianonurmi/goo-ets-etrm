package com.pianonurmi.etrm.referencedata.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.AccountChangeAction
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestCommand
import com.pianonurmi.etrm.referencedata.api.ChangeRequestQuery
import com.pianonurmi.etrm.referencedata.api.ChangeRequestStatus
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.api.CounterpartyAccountChange
import com.pianonurmi.etrm.referencedata.api.ReferenceDataChangeApplied
import com.pianonurmi.etrm.referencedata.api.ReferenceDataChangeCancelled
import com.pianonurmi.etrm.referencedata.api.ReferenceDataChangeRequested
import com.pianonurmi.etrm.referencedata.api.RegistryChange
import com.pianonurmi.etrm.referencedata.api.RequestChange
import com.pianonurmi.etrm.referencedata.domain.ChangeApplier
import com.pianonurmi.etrm.referencedata.domain.ChangeRequestRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Four-eyes changes to sensitive master data. Requests are validated by the entity type's [ChangeApplier] and
 * published; the inbox task (see `ReferenceDataChangeTasks`) applies or rejects them.
 */
@Service
@Transactional
class ChangeRequestService(
    private val repository: ChangeRequestRepository,
    appliers: List<ChangeApplier>,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : ChangeRequestCommand, ChangeRequestQuery {
    private val appliers = appliers.associateBy { it.entityType }

    override fun request(command: RequestChange): ChangeRequestView {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR, Role.ACCOUNTANT)
        if (command.reason.isBlank()) throw ValidationException("A change request needs a reason")
        if (command.change.isEmpty()) throw ValidationException("The change request changes nothing")
        val applier = applier(command.entityType)
        val summary = applier.validate(command.entityKey, command.change, command.expectedVersion)
        repository.pendingFor(command.entityType, command.entityKey)?.let {
            throw ConflictException("A change to ${command.entityType} ${command.entityKey} is already pending (${it.id})")
        }
        val legalEntityId = applier.legalEntityOf(command.entityKey)
        actor.requireEntity(legalEntityId)
        val request = ChangeRequestView(
            id = Ids.newId(),
            entityType = command.entityType,
            entityKey = command.entityKey,
            legalEntityId = legalEntityId,
            change = command.change,
            expectedVersion = command.expectedVersion,
            summary = summary,
            reason = command.reason.trim(),
            status = ChangeRequestStatus.PENDING,
            requestedBy = actor.userId,
            requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            decidedBy = null,
            decidedAt = null,
            decisionNote = null,
            version = 1,
        )
        repository.insert(request)
        events.publishEvent(ReferenceDataChangeRequested(request))
        return request
    }

    override fun requestRegistryChange(code: String, change: RegistryChange): ChangeRequestView = request(
        RequestChange(
            entityType = ChangeEntityType.REGISTRY,
            entityKey = code.uppercase(),
            change = buildMap {
                change.integration?.let { put("integration", it.name) }
                change.active?.let { put("active", it) }
            },
            reason = change.reason,
            expectedVersion = change.expectedVersion,
        ),
    )

    override fun requestCounterpartyAccountChange(counterpartyId: UUID, change: CounterpartyAccountChange): ChangeRequestView {
        val key = when (change.action) {
            AccountChangeAction.CREATE -> "$counterpartyId/${change.registryCode?.uppercase()}/${change.accountNumber?.trim()}"
            else -> change.accountId?.toString() ?: throw ValidationException("${change.action} needs accountId")
        }
        return request(
            RequestChange(
                entityType = ChangeEntityType.COUNTERPARTY_REGISTRY_ACCOUNT,
                entityKey = key,
                change = buildMap {
                    put("action", change.action.name)
                    put("counterpartyId", counterpartyId.toString())
                    change.accountId?.let { put("accountId", it.toString()) }
                    change.registryCode?.let { put("registryCode", it.uppercase()) }
                    change.accountNumber?.let { put("accountNumber", it.trim()) }
                    change.name?.let { put("name", it.trim()) }
                    change.accountType?.let { put("accountType", it.name) }
                },
                reason = change.reason,
            ),
        )
    }

    override fun cancel(id: UUID, note: String): ChangeRequestView {
        val actor = actorProvider.current()
        val request = repository.lock(id) ?: throw NotFoundException("ChangeRequest", id)
        if (request.status != ChangeRequestStatus.PENDING) return request
        if (actor.userId != request.requestedBy && !actor.has(Role.ADMIN)) throw ForbiddenException("Only the requester may cancel change request $id")
        return close(request, ChangeRequestStatus.CANCELLED, actor.userId, note).also { events.publishEvent(ReferenceDataChangeCancelled(it)) }
    }

    /** Applies an approved request (called from the inbox decision, inside the deciding transaction). */
    fun approve(id: UUID, by: String, note: String?): ChangeRequestView {
        val request = pending(id)
        if (request.requestedBy == by) throw SegregationOfDutiesException("A change request cannot be approved by its requester ($by)")
        val applier = applier(request.entityType)
        applier.validate(request.entityKey, request.change, request.expectedVersion)
        applier.apply(request)
        val applied = close(request, ChangeRequestStatus.APPLIED, by, note)
        events.publishEvent(ReferenceDataChangeApplied(applied))
        return applied
    }

    fun reject(id: UUID, by: String, note: String?): ChangeRequestView = close(pending(id), ChangeRequestStatus.REJECTED, by, note)

    @Transactional(readOnly = true)
    override fun changeRequest(id: UUID): ChangeRequestView? = repository.find(id)

    @Transactional(readOnly = true)
    override fun changeRequests(status: ChangeRequestStatus?, entityType: ChangeEntityType?): List<ChangeRequestView> =
        repository.list(status, entityType).filter { actorProvider.current().canAccess(it.legalEntityId) }

    private fun pending(id: UUID): ChangeRequestView {
        val request = repository.lock(id) ?: throw NotFoundException("ChangeRequest", id)
        if (request.status != ChangeRequestStatus.PENDING) throw ConflictException("Change request $id is ${request.status}")
        return request
    }

    private fun close(request: ChangeRequestView, status: ChangeRequestStatus, by: String, note: String?): ChangeRequestView {
        val closed = request.copy(
            status = status,
            decidedBy = by,
            decidedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            decisionNote = note,
            version = request.version + 1,
        )
        if (!repository.update(closed)) throw ConflictException("Change request ${request.id} changed concurrently")
        return closed
    }

    private fun applier(type: ChangeEntityType): ChangeApplier = appliers[type] ?: throw ValidationException("No change applier for $type")
}
