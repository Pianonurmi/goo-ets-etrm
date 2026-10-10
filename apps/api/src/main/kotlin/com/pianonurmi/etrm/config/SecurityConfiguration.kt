package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import java.util.UUID

/**
 * Production security: stateless OIDC resource server. Roles come from the `roles` claim (Keycloak realm roles or
 * Entra ID app roles) and legal-entity scope from the `legal_entities` claim.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties::class)
class SecurityConfiguration {
    @Bean
    @Profile("!dev & !test")
    fun resourceServerChain(http: HttpSecurity, properties: SecurityProperties): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
            it.anyRequest().authenticated()
        }
        .oauth2ResourceServer { oauth ->
            oauth.jwt { jwt ->
                jwt.jwtAuthenticationConverter(
                    JwtAuthenticationConverter().apply {
                        setJwtGrantedAuthoritiesConverter { token ->
                            rolesOf(token, properties.rolesClaim).map { org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_$it") }
                        }
                    },
                )
            }
        }
        .build()

    /**
     * Development and test profile: no token required. The actor is taken from `X-Actor` / `X-Roles` headers so
     * segregation-of-duties paths can still be exercised end-to-end.
     */
    @Bean
    @Profile("dev", "test")
    fun developmentChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf(AbstractHttpConfigurer<*, *>::disable)
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .build()

    @Bean
    fun actorProvider(properties: SecurityProperties): ActorProvider = SpringSecurityActorProvider(properties)

    companion object {
        fun rolesOf(jwt: Jwt, claim: String): Set<String> {
            val raw = jwt.claims[claim]
            return when (raw) {
                is Collection<*> -> raw.mapNotNull { it?.toString() }.toSet()
                is String -> raw.split(" ", ",").filter { it.isNotBlank() }.toSet()
                else -> emptySet()
            }
        }
    }
}

@ConfigurationProperties(prefix = "etrm.security")
data class SecurityProperties(
    val rolesClaim: String = "roles",
    val legalEntitiesClaim: String = "legal_entities",
    val displayNameClaim: String = "name",
)

class SpringSecurityActorProvider(private val properties: SecurityProperties) : ActorProvider {
    override fun current(): Actor {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken) {
            val jwt = authentication.token
            return Actor(
                userId = jwt.subject,
                displayName = jwt.claims[properties.displayNameClaim]?.toString() ?: jwt.subject,
                roles = SecurityConfiguration.rolesOf(jwt, properties.rolesClaim).mapNotNull { parseRole(it) }.toSet(),
                legalEntityScope = (jwt.claims[properties.legalEntitiesClaim] as? Collection<*>)
                    ?.mapNotNull { runCatching { UUID.fromString(it.toString()) }.getOrNull() }?.toSet() ?: emptySet(),
            )
        }
        return HeaderActorResolver.resolve() ?: Actor.SYSTEM
    }

    private fun parseRole(name: String): Role? = runCatching { Role.valueOf(name.removePrefix("ROLE_").uppercase()) }.getOrNull()
}

/** Dev/test actor from request headers; absent outside a web request. */
object HeaderActorResolver {
    const val ACTOR_HEADER = "X-Actor"
    const val ROLES_HEADER = "X-Roles"

    fun resolve(): Actor? {
        val attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            as? org.springframework.web.context.request.ServletRequestAttributes ?: return null
        val request = attributes.request
        val userId = request.getHeader(ACTOR_HEADER) ?: return null
        val roles = request.getHeader(ROLES_HEADER)
            ?.split(",")
            ?.mapNotNull { runCatching { Role.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?.toSet()
            ?: setOf(Role.ADMIN)
        return Actor(userId = userId, displayName = userId, roles = roles)
    }
}
