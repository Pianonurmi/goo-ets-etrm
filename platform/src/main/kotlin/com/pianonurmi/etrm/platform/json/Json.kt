package com.pianonurmi.etrm.platform.json

import tools.jackson.databind.DeserializationFeature
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
}
