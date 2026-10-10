package com.pianonurmi.etrm.web

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.time.Duration

/**
 * Serves the web UI bundled under `classpath:/static/` (ADR 0025). Hashed assets are cached for a year; any other
 * GET outside the API answers with `index.html`, so client-side routes survive a reload. Paths under the API,
 * actuator and OpenAPI prefixes never fall back and end in a 404 problem instead.
 */
@Configuration
class SpaConfiguration : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/assets/**")
            .addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .setCacheControl(CacheControl.noCache())
            .resourceChain(false)
            .addResolver(SpaFallbackResolver())
    }

    private class SpaFallbackResolver : PathResourceResolver() {
        override fun getResource(resourcePath: String, location: Resource): Resource? {
            val found = super.getResource(resourcePath, location)
            if (found != null || isServerPath(resourcePath) || looksLikeFile(resourcePath)) return found
            return ClassPathResource("static/index.html").takeIf { it.exists() }
        }
    }

    companion object {
        private val SERVER_PREFIXES = listOf("api/", "actuator", "v3/", "swagger-ui", "error")

        fun isServerPath(path: String): Boolean = SERVER_PREFIXES.any { path.removePrefix("/").startsWith(it) }

        /** A missing `favicon.ico` or `x.js` is a 404, not the application shell. */
        fun looksLikeFile(path: String): Boolean = path.substringAfterLast('/').contains('.')
    }
}
