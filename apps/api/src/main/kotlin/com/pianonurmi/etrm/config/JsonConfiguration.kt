package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.platform.json.Json
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature

/**
 * Aligns the web JSON mapper with the domain mapper so API payloads and stored JSON agree. Request bodies are
 * stricter than stored JSON: a property the API does not know (a misspelt `expectedVersion`, a field from another
 * version of a client) is refused rather than silently dropped. Stored JSON keeps reading leniently, so historical
 * payloads never break.
 */
@Configuration
class JsonConfiguration {
    @Bean
    fun etrmJsonCustomizer(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { builder ->
        Json.configure(builder).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }
}
