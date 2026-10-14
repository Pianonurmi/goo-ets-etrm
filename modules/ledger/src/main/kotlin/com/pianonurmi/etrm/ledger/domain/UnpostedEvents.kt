package com.pianonurmi.etrm.ledger.domain

import java.time.Instant
import java.util.UUID

/** Why an event did not reach the books. */
enum class UnpostedKind {
    /** The posting listener failed (an exception); everything it did was rolled back to its savepoint. */
    FAILED,

    /** A posting rule applied, but an amount it needs was missing or negative, so that rule posted nothing. */
    MISSING_AMOUNT,
}

enum class UnpostedStatus { OPEN, POSTED, DISMISSED }

/**
 * An event the ledger could not post (review findings F4 and F5). [eventClass] and [eventJson] let a job hand the
 * event to its listener again; [replayable] is false where the listener has side effects beyond postings that a
 * second run would repeat.
 */
data class UnpostedEvent(
    val id: UUID,
    val legalEntityId: UUID?,
    val eventId: UUID?,
    val eventType: String,
    val ruleCode: String?,
    val kind: UnpostedKind,
    val reason: String,
    val eventClass: String?,
    val eventJson: String?,
    val factsJson: String?,
    val replayable: Boolean,
    val status: UnpostedStatus = UnpostedStatus.OPEN,
    val attempts: Int = 1,
    val createdAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
)

interface UnpostedEventRepository {
    /** Records [event], or counts another attempt on the open record of the same event and rule; returns the record. */
    fun record(event: UnpostedEvent): UnpostedEvent

    fun find(id: UUID): UnpostedEvent?

    fun open(legalEntityId: UUID?, limit: Int): List<UnpostedEvent>

    /** Open records the replay job may retry, oldest first. */
    fun replayable(limit: Int): List<UnpostedEvent>

    fun resolve(id: UUID, status: UnpostedStatus, by: String, resolution: String?): Boolean

    fun openCount(): Long
}
