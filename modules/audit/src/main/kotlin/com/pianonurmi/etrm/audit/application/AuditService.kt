package com.pianonurmi.etrm.audit.application

import com.pianonurmi.etrm.audit.api.AuditCommand
import com.pianonurmi.etrm.audit.api.AuditEventView
import com.pianonurmi.etrm.audit.api.AuditFilter
import com.pianonurmi.etrm.audit.api.AuditQuery
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.eventType
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import java.time.Clock
import java.util.UUID

/**
 * Records every [DomainEvent] published in the application together with the acting user. Runs in the publishing
 * transaction so the audit record and the business change commit or roll back together.
 */
@Service
class AuditService(
    private val repository: AuditRepository,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) : AuditQuery, AuditCommand {
    private val log = LoggerFactory.getLogger(AuditService::class.java)
    private val mapType = object : TypeReference<Map<String, Any?>>() {}

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: DomainEvent) {
        val payload = runCatching { Json.mapper.readValue(Json.mapper.writeValueAsString(event), mapType) }
            .getOrElse { mapOf("unserializable" to event.toString()) }
        val (aggregateType, aggregateRef) = aggregateOf(event, payload)
        repository.append(
            AuditEventView(
                seq = 0,
                eventId = event.eventId,
                occurredAt = event.occurredAt,
                recordedAt = clock.instant(),
                actor = actorProvider.current().userId,
                eventType = event.eventType,
                aggregateType = aggregateType,
                aggregateRef = aggregateRef,
                correlationId = event.correlationId,
                payload = payload,
            ),
        )
    }

    @Transactional
    override fun record(action: String, aggregateType: String?, aggregateRef: String?, details: Map<String, Any?>): AuditEventView {
        val view = AuditEventView(
            seq = 0,
            eventId = Ids.newId(),
            occurredAt = clock.instant(),
            recordedAt = clock.instant(),
            actor = actorProvider.current().userId,
            eventType = action,
            aggregateType = aggregateType,
            aggregateRef = aggregateRef,
            correlationId = null,
            payload = details,
        )
        return repository.append(view)
    }

    @Transactional(readOnly = true)
    override fun events(filter: AuditFilter): List<AuditEventView> = repository.query(filter)

    @Transactional(readOnly = true)
    override fun event(eventId: UUID): AuditEventView? = repository.byEventId(eventId)

    /** Best-effort aggregate reference: the first nested object carrying an `id`, else the event's own id. */
    private fun aggregateOf(event: DomainEvent, payload: Map<String, Any?>): Pair<String?, String?> {
        payload.entries.firstOrNull { (key, value) -> key != "metadata" && value is Map<*, *> && value["id"] != null }?.let { (key, value) ->
            return key to (value as Map<*, *>)["id"].toString()
        }
        payload.entries.firstOrNull { (key, value) ->
            key.endsWith("Id") && value != null
        }?.let { (key, value) -> return key.removeSuffix("Id") to value.toString() }
        log.trace("No aggregate reference found for {}", event.eventType)
        return null to null
    }
}

interface AuditRepository {
    fun append(event: AuditEventView): AuditEventView

    fun query(filter: AuditFilter): List<AuditEventView>

    fun byEventId(eventId: UUID): AuditEventView?
}
