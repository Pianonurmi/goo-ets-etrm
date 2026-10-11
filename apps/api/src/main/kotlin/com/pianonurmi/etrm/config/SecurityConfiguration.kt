package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.web.SpaConfiguration
import com.pianonurmi.etrm.web.UiProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.util.matcher.RequestMatcher
import java.util.UUID

/**
 * Production security: stateless OIDC resource server. Roles come from the `roles` claim (Keycloak realm roles or
 * Entra ID app roles) and legal-entity scope from the `legal_entities` claim.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties::class, UiProperties::class)
class SecurityConfiguration {
    /**
     * The web UI shell and its assets are public (they hold no data); every API call needs a bearer token except the
     * UI configuration the browser reads before signing in.
     */
    @Bean
    @Profile("!dev & !test")
    fun resourceServerChain(
        http: HttpSecurity,
        properties: SecurityProperties,
        ui: UiProperties,
        managementPort: ManagementPort,
    ): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .headers { browserHeaders(it, ui) }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
            // The API description helps an attacker map the surface; outside development it needs a token unless opted in.
            if (properties.publicApiDocs) it.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            // A separate management port (Kubernetes) is reachable only inside the cluster: Prometheus scrapes it.
            it.requestMatchers(managementPortOnly(managementPort)).permitAll()
            it.requestMatchers(HttpMethod.GET, "/api/v1/ui-config").permitAll()
            it.requestMatchers("/error").permitAll()
            it.requestMatchers(SPA_ROUTES).permitAll()
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
    fun developmentChain(http: HttpSecurity, ui: UiProperties): SecurityFilterChain = http
        .csrf(AbstractHttpConfigurer<*, *>::disable)
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .headers { browserHeaders(it, ui) }
        .authorizeHttpRequests { it.anyRequest().permitAll() }
        .build()

    @Bean
    fun managementPort(): ManagementPort = ManagementPort()

    /** Dev persona headers are honoured only in the dev and test profiles; elsewhere they are ignored even if sent. */
    @Bean
    fun actorProvider(properties: SecurityProperties, environment: Environment): ActorProvider =
        SpringSecurityActorProvider(properties, headersAllowed = environment.matchesProfiles("dev | test"))

    companion object {
        /** Actuator requests that arrive on the separate management port; nothing when the port is shared. */
        fun managementPortOnly(managementPort: ManagementPort) = RequestMatcher { request ->
            managementPort.matches(request.localPort) && request.requestURI.startsWith("/actuator")
        }

        /** GET requests for the UI shell: anything outside the API, actuator and OpenAPI paths. */
        val SPA_ROUTES = RequestMatcher { request ->
            request.method == HttpMethod.GET.name() && !SpaConfiguration.isServerPath(request.requestURI.removePrefix(request.contextPath))
        }

        /**
         * Content security policy for the UI: own scripts only, inline styles (Mantine injects them), and the identity
         * provider for token calls and the silent-renew frame.
         */
        fun contentSecurityPolicy(ui: UiProperties): String {
            val idp = if (ui.authMode == UiProperties.AuthMode.OIDC) " " + ui.oidc.origin() else ""
            return listOf(
                "default-src 'self'",
                "script-src 'self'",
                "style-src 'self' 'unsafe-inline'",
                "img-src 'self' data: blob:",
                "font-src 'self' data:",
                "connect-src 'self'$idp",
                "frame-src 'self'$idp",
                "frame-ancestors 'none'",
                "base-uri 'self'",
                "form-action 'self'$idp",
                "object-src 'none'",
            ).joinToString("; ")
        }

        private fun browserHeaders(headers: HeadersConfigurer<HttpSecurity>, ui: UiProperties) {
            headers.contentSecurityPolicy { it.policyDirectives(contentSecurityPolicy(ui)) }
            headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
            headers.frameOptions { it.deny() }
        }

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
    /** Serve the OpenAPI document and Swagger UI without a token (secured profiles only; dev and test always do). */
    val publicApiDocs: Boolean = false,
)

class SpringSecurityActorProvider(
    private val properties: SecurityProperties,
    private val headersAllowed: Boolean = false,
) : ActorProvider {
    override fun current(): Actor {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken) {
            val jwt = authentication.token
            val subject = jwt.subject ?: "unknown"
            return Actor(
                userId = subject,
                displayName = jwt.claims[properties.displayNameClaim]?.toString() ?: subject,
                roles = SecurityConfiguration.rolesOf(jwt, properties.rolesClaim).mapNotNull { parseRole(it) }.toSet(),
                legalEntityScope = entityClaim(jwt).mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet(),
                // A missing claim grants no entity; "*" must be granted explicitly by the identity provider.
                allEntities = "*" in entityClaim(jwt),
            )
        }
        return (if (headersAllowed) HeaderActorResolver.resolve() else null) ?: Actor.SYSTEM
    }

    private fun entityClaim(jwt: Jwt): Set<String> = when (val raw = jwt.claims[properties.legalEntitiesClaim]) {
        is Collection<*> -> raw.mapNotNull { it?.toString() }.toSet()
        is String -> raw.split(" ", ",").filter { it.isNotBlank() }.toSet()
        else -> emptySet()
    }

    private fun parseRole(name: String): Role? = runCatching { Role.valueOf(name.removePrefix("ROLE_").uppercase()) }.getOrNull()
}

/** Dev/test actor from request headers; absent outside a web request. */
object HeaderActorResolver {
    const val ACTOR_HEADER = "X-Actor"
    const val ROLES_HEADER = "X-Roles"

    /** Comma-separated legal entity ids; absent or `*` means all entities (development convenience only). */
    const val ENTITIES_HEADER = "X-Entities"

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
        val entities = request.getHeader(ENTITIES_HEADER)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("*")
        return Actor(
            userId = userId,
            displayName = userId,
            roles = roles,
            legalEntityScope = entities.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet(),
            allEntities = "*" in entities,
        )
    }
}
