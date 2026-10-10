package com.pianonurmi.etrm.referencedata.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.time.Instant
import java.util.UUID

enum class ChangeRequestStatus { PENDING, APPLIED, REJECTED, CANCELLED }

/** Sensitive master data that only changes with a second person's approval. */
enum class ChangeEntityType { REGISTRY, COUNTERPARTY_REGISTRY_ACCOUNT, BANK_ACCOUNT }

/**
 * A requested change to sensitive master data. [change] holds the new values (only the fields that change);
 * it is applied when a second person approves the matching `REFERENCE_DATA_CHANGE` task in the inbox.
 */
data class ChangeRequestView(
    val id: UUID,
    val entityType: ChangeEntityType,
    val entityKey: String,
    val legalEntityId: UUID?,
    val change: Map<String, Any?>,
    val expectedVersion: Int?,
    val summary: String,
    val reason: String,
    val status: ChangeRequestStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionNote: String?,
    val version: Int,
)

data class RequestChange(
    val entityType: ChangeEntityType,
    val entityKey: String,
    val change: Map<String, Any?>,
    val reason: String,
    val expectedVersion: Int? = null,
)

/** Registry settings that change through four-eyes: integration mode and whether the registry is in use. */
data class RegistryChange(
    val integration: RegistryIntegration? = null,
    val active: Boolean? = null,
    val reason: String,
    val expectedVersion: Int,
)

enum class AccountChangeAction { CREATE, DEACTIVATE, REACTIVATE, ADD_TO_TRUSTED_LIST, REMOVE_FROM_TRUSTED_LIST }

/**
 * A change to a counterparty's registry account. CREATE needs [registryCode], [accountNumber], [name] and
 * [accountType]; the other actions need [accountId].
 */
data class CounterpartyAccountChange(
    val action: AccountChangeAction,
    val reason: String,
    val accountId: UUID? = null,
    val registryCode: String? = null,
    val accountNumber: String? = null,
    val name: String? = null,
    val accountType: RegistryAccountType? = null,
)

interface ChangeRequestCommand {
    fun request(command: RequestChange): ChangeRequestView

    fun requestRegistryChange(code: String, change: RegistryChange): ChangeRequestView

    fun requestCounterpartyAccountChange(counterpartyId: UUID, change: CounterpartyAccountChange): ChangeRequestView

    fun cancel(id: UUID, note: String): ChangeRequestView
}

interface ChangeRequestQuery {
    fun changeRequest(id: UUID): ChangeRequestView?

    fun changeRequests(status: ChangeRequestStatus? = ChangeRequestStatus.PENDING, entityType: ChangeEntityType? = null): List<ChangeRequestView>
}

data class ReferenceDataChangeRequested(val request: ChangeRequestView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class ReferenceDataChangeCancelled(val request: ChangeRequestView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class ReferenceDataChangeApplied(val request: ChangeRequestView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class RegistryChanged(val registry: RegistryView, val previous: RegistryView, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata
