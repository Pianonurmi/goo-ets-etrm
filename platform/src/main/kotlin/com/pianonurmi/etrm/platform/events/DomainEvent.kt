package com.pianonurmi.etrm.platform.events

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.time.ApplicationClock
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
}

/**
 * Stable type name used in the audit journal and for externalisation. An extension rather than an interface
 * member so that `DomainEvent by metadata` delegation cannot redirect it to the metadata holder's class.
 */
val DomainEvent.eventType: String get() = this::class.simpleName ?: "UnknownEvent"

/** Convenience holder so event data classes only need to add their payload; stamped with the application's clock. */
data class EventMetadata(
    override val eventId: UUID = Ids.newId(),
    override val occurredAt: Instant = ApplicationClock.instant(),
    override val correlationId: String? = null,
) : DomainEvent
