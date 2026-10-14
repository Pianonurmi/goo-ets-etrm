package com.pianonurmi.etrm.ledger.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.util.UUID

/** An event the ledger could not post, as shown in the LEDGER_ATTENTION task (review findings F4 and F5). */
data class UnpostedEventView(
    val id: UUID,
    val legalEntityId: UUID?,
    val eventId: UUID?,
    val eventType: String,
    val ruleCode: String?,
    val kind: String,
    val reason: String,
    val replayable: Boolean,
)

/** Raised once per event and rule that did not reach the books; the ledger opens an inbox task for accountants. */
data class LedgerAttentionNeeded(val unposted: UnpostedEventView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
