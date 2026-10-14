package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.LedgerAttentionNeeded
import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.api.UnpostedEventView
import com.pianonurmi.etrm.ledger.domain.UnpostedEvent
import com.pianonurmi.etrm.ledger.domain.UnpostedEventRepository
import com.pianonurmi.etrm.ledger.domain.UnpostedKind
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.eventType
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.tx.Savepoints
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Keeps a failing posting from failing the business transaction, and from vanishing (review findings F4 and F5).
 *
 * Every posting listener runs its body through [guard]: inside a savepoint, so a failure (no FX rate, too few cost
 * layers, an unmapped account) rolls back only what the listener did, and the delivery, registry confirmation or
 * payment that published the event still commits. The failure is recorded as an unposted event. A posting rule that
 * applies but lacks an amount (a delivered price that cannot be resolved yet) is recorded the same way by
 * [missingAmount]. Each record raises [LedgerAttentionNeeded], which opens an inbox task for accountants, and keeps the
 * event so that [UnpostedReplay] can hand it to its listener again.
 */
@Component
class UnpostedEvents(
    private val repository: UnpostedEventRepository,
    private val savepoints: Savepoints,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(UnpostedEvents::class.java)
    private val current = ThreadLocal<Handling?>()
    private val touched = ThreadLocal<MutableSet<UUID>?>()

    private data class Handling(val event: DomainEvent, val replaySafe: Boolean)

    /**
     * Runs a posting listener's [body] for [event]. [replaySafe] is false when the listener changes more than the
     * journal (cost layers), so an event it only partly posted must not be handed to it again.
     */
    fun guard(event: DomainEvent, legalEntityId: UUID?, replaySafe: Boolean = true, body: () -> Unit) {
        val outer = current.get()
        current.set(Handling(event, replaySafe))
        try {
            runCatching { savepoints.attempt(body) }.onFailure { failure ->
                log.error("Posting {} {} failed; recorded for the ledger's attention", event.eventType, event.eventId, failure)
                record(
                    UnpostedKind.FAILED,
                    legalEntityId,
                    event.eventId,
                    event.eventType,
                    ruleCode = null,
                    reason = failure.message ?: failure.javaClass.simpleName,
                    event = event,
                    facts = null,
                    // The savepoint undid everything the listener did, so running it again is safe.
                    replayable = true,
                )
            }
        } finally {
            current.set(outer)
        }
    }

    /** A rule applied to [facts] but [missing] amounts were absent or negative, so it posted nothing. */
    fun missingAmount(facts: PostingFacts, ruleCode: String, missing: List<String>) {
        val handling = current.get()
        record(
            UnpostedKind.MISSING_AMOUNT,
            facts.legalEntityId,
            facts.eventId,
            facts.eventType,
            ruleCode,
            reason = "Rule $ruleCode needs ${missing.joinToString()}, which ${if (missing.size == 1) "is" else "are"} missing or negative",
            event = handling?.event,
            facts = facts,
            replayable = handling?.replaySafe ?: false,
        )
    }

    /** Runs [block] and returns the ids of the unposted records it created or touched again. */
    internal fun <T> collecting(block: () -> T): Pair<T, Set<UUID>> {
        val outer = touched.get()
        val set = mutableSetOf<UUID>()
        touched.set(set)
        try {
            return block() to set
        } finally {
            touched.set(outer)
        }
    }

    @Suppress("LongParameterList")
    private fun record(
        kind: UnpostedKind,
        legalEntityId: UUID?,
        eventId: UUID?,
        eventType: String,
        ruleCode: String?,
        reason: String,
        event: DomainEvent?,
        facts: PostingFacts?,
        replayable: Boolean,
    ) {
        // Kept only when it reads back as the same event, so that a replay can hand it to its listener.
        val eventJson = event?.let { e ->
            runCatching { Json.mapper.writeValueAsString(e).also { Json.mapper.readValue(it, e.javaClass) } }.getOrNull()
        }
        val recorded = repository.record(
            UnpostedEvent(
                id = Ids.newId(),
                legalEntityId = legalEntityId,
                eventId = eventId,
                eventType = eventType,
                ruleCode = ruleCode,
                kind = kind,
                reason = reason.take(MAX_REASON),
                eventClass = event?.javaClass?.name,
                eventJson = eventJson,
                factsJson = facts?.let { Json.mapper.writeValueAsString(it) },
                replayable = replayable && eventJson != null,
            ),
        )
        touched.get()?.add(recorded.id)
        if (recorded.attempts == 1) events.publishEvent(LedgerAttentionNeeded(view(recorded)))
    }

    companion object {
        private const val MAX_REASON = 2000

        fun view(u: UnpostedEvent) = UnpostedEventView(u.id, u.legalEntityId, u.eventId, u.eventType, u.ruleCode, u.kind.name, u.reason, u.replayable)
    }
}
