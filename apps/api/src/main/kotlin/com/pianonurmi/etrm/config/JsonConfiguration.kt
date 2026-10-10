package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.platform.json.Json
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Aligns the web JSON mapper with the domain mapper so API payloads and stored JSON agree. */
@Configuration
class JsonConfiguration {
    @Bean
    fun etrmJsonCustomizer(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { builder -> Json.configure(builder) }
}
