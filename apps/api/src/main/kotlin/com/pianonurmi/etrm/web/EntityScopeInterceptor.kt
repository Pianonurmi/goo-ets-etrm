package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.security.ActorProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

/**
 * Refuses an API request that names a legal entity outside the caller's scope, as a query parameter or path
 * variable (`legalEntityId`, `holderEntityId`). It is a backstop for read endpoints keyed by entity;
 * commands and unkeyed lists still check scope in their services.
 */
class EntityScopeInterceptor(private val actors: ActorProvider) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        @Suppress("UNCHECKED_CAST")
        val pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String> ?: emptyMap()
        val named = ENTITY_PARAMETERS.flatMap { name -> request.getParameterValues(name).orEmpty().toList() + listOfNotNull(pathVariables[name]) }
        if (named.isEmpty()) return true
        val actor = actors.current()
        named.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.forEach { actor.requireEntity(it) }
        return true
    }

    companion object {
        val ENTITY_PARAMETERS = listOf("legalEntityId", "holderEntityId")
    }
}

@Configuration
class EntityScopeConfiguration(private val actors: ActorProvider) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(EntityScopeInterceptor(actors)).addPathPatterns("/api/**")
    }
}
