package com.pianonurmi.etrm.events

import com.pianonurmi.etrm.inventory.api.LotReceived
import com.pianonurmi.etrm.inventory.api.LotTransferred
import com.pianonurmi.etrm.ledger.api.JournalEntryPosted
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.eventType
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.risk.api.LimitBreached
import com.pianonurmi.etrm.settlement.api.InvoiceIssued
import com.pianonurmi.etrm.settlement.api.PaymentRecorded
import com.pianonurmi.etrm.trade.api.TradeEvent
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget

/**
 * The domain events other systems may consume, their Kafka topic and key (the aggregate id, so one aggregate's events
 * stay in order in one partition). Spring Modulith sends them after the producing transaction commits, from the event
 * publication registry, so an event is not lost when Kafka is down: the publication stays incomplete and is sent
 * again (ADR 0029).
 */
object ExternalEvents {
    const val TRADE = "etrm.trade"
    const val SETTLEMENT = "etrm.settlement"
    const val REGISTRY = "etrm.registry"
    const val INVENTORY = "etrm.inventory"
    const val LEDGER = "etrm.ledger"
    const val RISK = "etrm.risk"
    val TOPICS = listOf(TRADE, SETTLEMENT, REGISTRY, INVENTORY, LEDGER, RISK)

    /** Topic and key of an event that leaves the application, or null for one that stays inside. */
    fun route(event: Any): Pair<String, String>? = when (event) {
        is TradeEvent -> TRADE to event.trade.id.toString()
        is InvoiceIssued -> SETTLEMENT to event.invoice.id.toString()
        is PaymentRecorded -> SETTLEMENT to event.payment.id.toString()
        is InstructionConfirmed -> REGISTRY to event.instruction.id.toString()
        is InstructionFailed -> REGISTRY to event.instruction.id.toString()
        is LotReceived -> INVENTORY to event.lot.id.toString()
        is LotTransferred -> INVENTORY to event.lot.id.toString()
        is JournalEntryPosted -> LEDGER to event.entry.id.toString()
        is LimitBreached -> RISK to event.limit.id.toString()
        else -> null
    }

    /** Headers consumers filter and deduplicate on: the event type and its id. */
    fun headers(event: Any): Map<String, Any> = (event as? DomainEvent)?.let {
        mapOf(
            "etrm-event-type" to it.eventType,
            "etrm-event-id" to it.eventId.toString(),
            "etrm-occurred-at" to it.occurredAt.toString(),
        )
    } ?: emptyMap()
}

@ConfigurationProperties("etrm.events")
data class EventProperties(
    /** Partitions of each topic the application creates when externalisation is on. */
    val partitions: Int = 6,
    val replicas: Short = 1,
    /** Off where topics are provisioned outside the application (and its user may not create them). */
    val createTopics: Boolean = true,
)

@Configuration
@EnableConfigurationProperties(EventProperties::class)
class EventExternalizationConfig {
    @Bean
    fun eventExternalization(): EventExternalizationConfiguration = EventExternalizationConfiguration.externalizing()
        .select { ExternalEvents.route(it) != null }
        .routeAll { event ->
            val (topic, key) = ExternalEvents.route(event)!!
            RoutingTarget.forTarget(topic).andKey(key)
        }
        .headers(ExternalEvents::headers)
        .build()

    /** Topics are declared only when externalisation is on, so no broker is contacted otherwise. */
    @Bean
    @ConditionalOnBooleanProperty("spring.modulith.events.externalization.enabled")
    fun etrmTopics(properties: EventProperties): KafkaAdmin.NewTopics = KafkaAdmin.NewTopics(
        *ExternalEvents.TOPICS.filter { properties.createTopics }
            .map { TopicBuilder.name(it).partitions(properties.partitions).replicas(properties.replicas.toInt()).build() }
            .toTypedArray<NewTopic>(),
    )
}
