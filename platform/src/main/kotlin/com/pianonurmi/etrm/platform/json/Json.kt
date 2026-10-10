package com.pianonurmi.etrm.platform.json

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Canonical JSON mapper for domain-level serialisation (criteria, event payloads, audit). Spring's web mapper is
 * configured identically through the auto-configuration customizer in the application module.
 */
object Json {
    val mapper: JsonMapper = configure(JsonMapper.builder()).build()

    fun configure(builder: JsonMapper.Builder): JsonMapper.Builder = builder
        .addModule(kotlinModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

    /** Canonical form: recursively key-sorted, no whitespace. Stable across JSONB round trips, suitable for hashing. */
    fun canonical(json: String): String = mapper.writeValueAsString(sortKeys(mapper.readTree(json)))

    fun canonical(value: Any): String = mapper.writeValueAsString(sortKeys(mapper.valueToTree(value)))

    private fun sortKeys(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { sorted ->
            node.properties().sortedBy { it.key }.forEach { (key, value) -> sorted.set(key, sortKeys(value)) }
        }
        node.isArray -> mapper.createArrayNode().also { array -> node.forEach { array.add(sortKeys(it)) } }
        else -> node
    }
}
