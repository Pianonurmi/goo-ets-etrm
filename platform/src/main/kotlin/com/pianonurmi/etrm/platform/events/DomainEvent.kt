package com.pianonurmi.etrm.platform.events

import com.pianonurmi.etrm.platform.ids.Ids
import java.time.Instant
import java.util.UUID

/**
 * Base contract for every domain event published between modules. Events are published inside the producing
 * transaction and delivered through Spring Modulith's event publication registry (transactional outbox),
 * so consumers must be idempotent on [eventId].
 */
interface DomainEvent {
    val eventId: UUID
    val occurredAt: Instant
    val correlationId: String?

    /** Stable, versioned type name used in the event journal and for externalisation. */
    val eventType: String get() = this::class.simpleName ?: "UnknownEvent"
}

/** Convenience holder so event data classes only need to add their payload. */
data class EventMetadata(
    override val eventId: UUID = Ids.newId(),
    override val occurredAt: Instant = Instant.now(),
    override val correlationId: String? = null,
) : DomainEvent
