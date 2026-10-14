package com.pianonurmi.etrm.registry.adapter.http

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.ProxySelector
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings for registry API adapters, keyed by registry code:
 *
 * ```yaml
 * etrm.registry.adapters.FINGRID:
 *   enabled: true
 *   base-url: https://registry.example/api
 *   client-id: etrm
 *   credential-env: ETRM_REGISTRY_FINGRID_SECRET   # the secret itself only ever lives in the environment
 * ```
 */
@ConfigurationProperties(prefix = "etrm.registry")
data class RegistryAdapterProperties(val adapters: Map<String, AdapterSettings> = emptyMap()) {
    /** By registry code in any case: keys from environment variables arrive in lower case. */
    fun settings(registryCode: String): AdapterSettings? =
        adapters[registryCode.uppercase()] ?: adapters.entries.firstOrNull { it.key.equals(registryCode, ignoreCase = true) }?.value
}

data class AdapterSettings(
    val enabled: Boolean = false,
    val baseUrl: String? = null,
    val clientId: String? = null,
    /** Name of the environment variable holding the API secret; secrets are never stored in configuration files. */
    val credentialEnv: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    /** Attempts for idempotent reads; commands are never retried blindly (see the registry dispatcher). */
    val maxAttempts: Int = 3,
    val retryBackoff: Duration = Duration.ofMillis(500),
    /** The request mapping (`GENERIC_REST`, or a registry-specific one once its specification is in hand). */
    val mapping: String = "GENERIC_REST",
    /** OAuth 2 token endpoint; without it requests are sent without a token. */
    val tokenUrl: String? = null,
    val scope: String? = null,
    val grantType: GrantType = GrantType.CLIENT_CREDENTIALS,
    /** For REFRESH_TOKEN: the environment variable with the refresh token obtained by an interactive (PKCE) login. */
    val refreshTokenEnv: String? = null,
    /** Mappings not yet checked against the registry's published specification run only when this is set. */
    val allowUnverified: Boolean = false,
) {
    fun credential(environment: (String) -> String? = System::getenv): String? = credentialEnv?.let(environment)

    fun refreshToken(environment: (String) -> String? = System::getenv): String? = refreshTokenEnv?.let(environment)
}

enum class GrantType { CLIENT_CREDENTIALS, REFRESH_TOKEN }

/**
 * HTTP clients for registry adapters with explicit timeouts, and retries of idempotent reads. One client per adapter
 * setting, kept for the life of the application (review finding O7): a JDK client owns a connection pool and a
 * selector thread, so building one per call leaked both. Clients use the JVM's proxy settings (`https.proxyHost`)
 * and trust store (`javax.net.ssl.trustStore`).
 */
@Component
class RegistryHttpClients(private val properties: RegistryAdapterProperties) {
    private val log = LoggerFactory.getLogger(RegistryHttpClients::class.java)
    private val clients = ConcurrentHashMap<AdapterSettings, RestClient>()

    fun isEnabled(registryCode: String): Boolean = properties.settings(registryCode)?.let { it.enabled && it.baseUrl != null } ?: false

    fun client(registryCode: String): RestClient {
        val settings = checkNotNull(properties.settings(registryCode)) { "No adapter settings for registry $registryCode" }
        return client(settings)
    }

    fun client(settings: AdapterSettings): RestClient {
        val baseUrl = checkNotNull(settings.baseUrl) { "Adapter base-url is not configured" }
        return clients.computeIfAbsent(settings) {
            val http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout).proxy(ProxySelector.getDefault()).build()
            val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(settings.readTimeout) }
            RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build()
        }
    }

    /**
     * Runs an idempotent read, retrying transport errors and 5xx responses with linear backoff. Never use it for
     * commands: a timed-out command may have reached the registry, so it is resolved by a status query instead.
     */
    fun <T> idempotentRead(settings: AdapterSettings, description: String, call: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return call()
            } catch (ex: ResourceAccessException) {
                if (attempt >= settings.maxAttempts) throw ex
                backOff(settings, description, attempt, ex)
            } catch (ex: HttpServerErrorException) {
                if (attempt >= settings.maxAttempts) throw ex
                backOff(settings, description, attempt, ex)
            }
            attempt++
        }
    }

    private fun backOff(settings: AdapterSettings, description: String, attempt: Int, ex: RuntimeException) {
        log.warn("{} failed (attempt {}/{}): {}", description, attempt, settings.maxAttempts, ex.message)
        Thread.sleep(settings.retryBackoff.toMillis() * attempt)
    }
}
