package com.pianonurmi.etrm

import com.pianonurmi.etrm.events.EventPublicationMaintenance
import com.pianonurmi.etrm.events.ExternalEvents
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.UUID

/**
 * Externalisation against a real broker: a trade's events reach `etrm.trade` after commit, keyed by the trade id and
 * typed by header, and their publications complete; events that stay inside are not sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KafkaExternalizationIT {
    companion object {
        private val kafka = KafkaContainer("apache/kafka:3.9.1").also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            TestWiring.register(registry)
            registry.add("spring.modulith.events.externalization.enabled") { "true" }
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var maintenance: EventPublicationMaintenance

    @Test
    fun `a trade's events reach Kafka keyed by the trade and typed by header`() {
        val s = ApiScenario(port).referenceData()
        val tradeId = s.capturedHydroSale(40)

        val records = consume(ExternalEvents.TRADE) { record -> record.key() == tradeId }
        val captured = records.first { header(it, "etrm-event-type") == "TradeCaptured" }
        captured.value() shouldContain "\"tradeNumber\""
        captured.value() shouldContain tradeId
        header(captured, "etrm-event-id")!!.let(UUID::fromString)

        // Lot receipts go to the inventory topic; workflow tasks and obligations stay inside.
        val lotId = s.receiveLot(1, 20)
        consume(ExternalEvents.INVENTORY) { it.key() == lotId }.map { header(it, "etrm-event-type") }.toSet() shouldBe setOf("LotReceived")

        await().atMost(Duration.ofSeconds(20)).until { maintenance.countIncomplete() == 0L }
        maintenance.maintain()
    }

    private fun header(record: ConsumerRecord<String, String>, name: String): String? =
        record.headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)?.trim('"')

    private fun consume(topic: String, wanted: (ConsumerRecord<String, String>) -> Boolean): List<ConsumerRecord<String, String>> {
        val config = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "it-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
        KafkaConsumer(config, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf(topic))
            val found = mutableListOf<ConsumerRecord<String, String>>()
            await().atMost(Duration.ofSeconds(30)).until {
                consumer.poll(Duration.ofMillis(500)).filter(wanted).let(found::addAll)
                found.isNotEmpty()
            }
            return found
        }
    }
}
