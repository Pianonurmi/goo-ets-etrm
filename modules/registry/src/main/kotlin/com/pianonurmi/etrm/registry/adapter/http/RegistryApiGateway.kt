package com.pianonurmi.etrm.registry.adapter.http

import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.InstructionStatusSource
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.type.TypeReference
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** One HTTP request a mapping wants sent; [body] is serialised as JSON. */
data class ApiRequest(val method: HttpMethod, val path: String, val body: Map<String, Any?>? = null, val headers: Map<String, String> = emptyMap())

/** What a mapping needs besides the instruction: account numbers and certificate details resolved from our data. */
data class InstructionContext(val fromAccountNumber: String, val issuanceRef: String?)

/**
 * Translates instructions into one registry's API and its answers back. Mappings hold no HTTP or security code, so
 * a registry's specification changes only its mapping. [verified] is false until the mapping was checked against the
 * registry's published specification and test environment; unverified mappings run only where explicitly allowed.
 */
interface RegistryApiMapping {
    val name: String
    val verified: Boolean

    fun submit(instruction: RegistryInstructionView, context: InstructionContext): ApiRequest

    /** The answer to a submission with a 2xx status. */
    fun accepted(status: Int, body: Map<String, Any?>): GatewayResult

    /** Business refusals (4xx other than 401, 408 and 429): the reason to record. */
    fun refusal(status: Int, body: Map<String, Any?>): String = body["message"]?.toString() ?: body["error"]?.toString() ?: "HTTP $status"

    /** A status query by our instruction key, or null when the registry offers none. */
    fun statusQuery(instruction: RegistryInstructionView): ApiRequest?

    /** The registry's view of an instruction; null when it does not know it. */
    fun status(body: Map<String, Any?>): GatewayResult?
}

/**
 * ETRM's own registry REST contract (docs/integration/registry-api-contract.md), for registries or integration
 * middleware that implement it. It is our specification, not a registry's, and therefore verified by our tests.
 */
@Component
class GenericRestMapping : RegistryApiMapping {
    override val name = "GENERIC_REST"
    override val verified = true

    override fun submit(instruction: RegistryInstructionView, context: InstructionContext): ApiRequest = ApiRequest(
        HttpMethod.POST,
        "/instructions",
        buildMap {
            put("instructionKey", instruction.instructionKey)
            put("type", instruction.type.name)
            put("registry", instruction.registryCode)
            put("fromAccount", context.fromAccountNumber)
            put("toRegistry", instruction.toRegistryCode)
            put("toAccount", instruction.toAccountRef)
            put("issuanceRef", context.issuanceRef)
            put("certificateStart", instruction.range?.start?.toString())
            put("certificateEnd", instruction.range?.end?.toString())
            put("product", instruction.productCode)
            put("quantity", instruction.quantity)
            put("unit", instruction.unit)
            put("complianceYear", instruction.complianceYear)
            instruction.cancellation?.let { c ->
                put(
                    "cancellation",
                    mapOf(
                        "purpose" to c.purpose.name,
                        "beneficiaryName" to c.beneficiaryName,
                        "beneficiaryId" to c.beneficiaryId,
                        "consumptionCountry" to c.consumptionCountry,
                        "consumptionStart" to c.consumptionPeriod.start.toString(),
                        "consumptionEnd" to c.consumptionPeriod.endExclusive.toString(),
                    ),
                )
            }
        },
    )

    override fun accepted(status: Int, body: Map<String, Any?>): GatewayResult = result(body) ?: GatewayResult.Sent(body["id"]?.toString(), body)

    override fun statusQuery(instruction: RegistryInstructionView): ApiRequest = ApiRequest(HttpMethod.GET, "/instructions/${instruction.instructionKey}")

    override fun status(body: Map<String, Any?>): GatewayResult? = result(body) ?: GatewayResult.Sent(body["id"]?.toString(), body)

    private fun result(body: Map<String, Any?>): GatewayResult? {
        val id = body["id"]?.toString()
        return when (body["status"]?.toString()?.uppercase()) {
            "COMPLETED" -> GatewayResult.Confirmed(id, body)
            "REJECTED", "CANCELLED" -> GatewayResult.Failed(body["reason"]?.toString() ?: "rejected by the registry", body)
            else -> null
        }
    }
}

/** OAuth 2 access tokens per registry, cached until shortly before they expire. */
@Component
class RegistryTokens(private val clients: RegistryHttpClients, private val clock: Clock) {
    private data class Token(val value: String, val expiresAt: Instant)

    private val tokens = ConcurrentHashMap<String, Token>()

    /** Null when the adapter needs no token. */
    fun token(registryCode: String, settings: AdapterSettings): String? {
        val tokenUrl = settings.tokenUrl ?: return null
        tokens[registryCode]?.takeIf { it.expiresAt.isAfter(clock.instant().plus(SKEW)) }?.let { return it.value }
        val form = LinkedMultiValueMap<String, String>()
        when (settings.grantType) {
            GrantType.CLIENT_CREDENTIALS -> form.add("grant_type", "client_credentials")
            GrantType.REFRESH_TOKEN -> {
                form.add("grant_type", "refresh_token")
                form.add("refresh_token", checkNotNull(settings.refreshToken()) { "Refresh token for $registryCode is not in the environment" })
            }
        }
        settings.clientId?.let { form.add("client_id", it) }
        settings.credential()?.let { form.add("client_secret", it) }
        settings.scope?.let { form.add("scope", it) }
        val answer = clients.idempotentRead(settings, "token for $registryCode") {
            clients.client(settings.copy(baseUrl = tokenUrl)).post().contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(String::class.java)
        }
        val json = Json.mapper.readValue(answer, MAP)
        val value = checkNotNull(json["access_token"]?.toString()) { "Token endpoint of $registryCode returned no access_token" }
        val ttl = (json["expires_in"] as? Number)?.toLong() ?: DEFAULT_TTL_SECONDS
        tokens[registryCode] = Token(value, clock.instant().plusSeconds(ttl))
        return value
    }

    fun invalidate(registryCode: String) {
        tokens.remove(registryCode)
    }

    private companion object {
        val SKEW: Duration = Duration.ofSeconds(30)
        const val DEFAULT_TTL_SECONDS = 300L
        val MAP = object : TypeReference<Map<String, Any?>>() {}
    }
}

/**
 * The API channel. Each call carries our instruction key as `Idempotency-Key`. Outcomes are classified for the
 * dispatcher (ADR 0012):
 * - refusals → Failed;
 * - not reached or not accepted (connection refused, 429, 503) → Retry;
 * - possibly processed (timeouts, 500, 502, 504) → Unknown, resolved by a status query.
 */
@Component
class ApiRegistryGateway(
    mappings: List<RegistryApiMapping>,
    private val clients: RegistryHttpClients,
    private val properties: RegistryAdapterProperties,
    private val tokens: RegistryTokens,
    private val referenceData: ReferenceDataQuery,
    private val lots: LotQuery,
) : RegistryGateway, InstructionStatusSource {
    private val log = LoggerFactory.getLogger(ApiRegistryGateway::class.java)
    private val mappings = mappings.associateBy { it.name }

    override val channel = "API"

    override val inline = false

    override fun supports(registryCode: String): Boolean = clients.isEnabled(registryCode) && mapping(registryCode) != null

    override fun execute(instruction: RegistryInstructionView): GatewayResult {
        val settings = settings(instruction.registryCode)
        val mapping = mapping(instruction.registryCode) ?: return GatewayResult.Retry("No usable API mapping for ${instruction.registryCode}")
        val context = InstructionContext(
            fromAccountNumber = referenceData.requireRegistryAccount(instruction.fromAccountId).accountNumber,
            issuanceRef = instruction.lotId?.let(lots::lot)?.issuanceRef,
        )
        val request = mapping.submit(instruction, context)
        return try {
            val (status, body) = send(instruction, settings, request, retryOnUnauthorised = true)
            mapping.accepted(status, body)
        } catch (ex: RestClientResponseException) {
            classify(ex, mapping)
        } catch (ex: ResourceAccessException) {
            if (ex.notReached()) {
                GatewayResult.Retry(
                    "Registry not reachable: ${ex.message}",
                )
            } else {
                GatewayResult.Unknown("No answer from the registry: ${ex.message}")
            }
        } catch (ex: RestClientException) {
            // The request went out but its answer could not be read (a body cut off or too slow): it may have been processed.
            GatewayResult.Unknown("The registry's answer could not be read: ${ex.message}")
        }
    }

    override fun status(instruction: RegistryInstructionView): GatewayResult? {
        if (!supports(instruction.registryCode)) return null
        val settings = settings(instruction.registryCode)
        val mapping = checkNotNull(mapping(instruction.registryCode)) { "No API mapping for ${instruction.registryCode}" }
        val request = mapping.statusQuery(instruction) ?: return null
        return try {
            val (_, body) = clients.idempotentRead(settings, "status of ${instruction.instructionKey}") {
                send(instruction, settings, request, retryOnUnauthorised = true)
            }
            mapping.status(body)
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.value() == HTTP_NOT_FOUND) {
                null
            } else {
                null.also {
                    log.warn("Status query for {} failed: {}", instruction.instructionKey, ex.message)
                }
            }
        } catch (ex: ResourceAccessException) {
            null.also { log.warn("Status query for {} failed: {}", instruction.instructionKey, ex.message) }
        }
    }

    private fun send(
        instruction: RegistryInstructionView,
        settings: AdapterSettings,
        request: ApiRequest,
        retryOnUnauthorised: Boolean,
    ): Pair<Int, Map<String, Any?>> {
        val token = tokens.token(instruction.registryCode, settings)
        return try {
            val spec = clients.client(settings).method(request.method).uri(request.path).headers { h ->
                token?.let { h.setBearerAuth(it) }
                h.set(IDEMPOTENCY_HEADER, instruction.instructionKey)
                h.accept = listOf(MediaType.APPLICATION_JSON)
                request.headers.forEach { (k, v) -> h.set(k, v) }
            }
            val entity = (if (request.body != null) spec.contentType(MediaType.APPLICATION_JSON).body(Json.mapper.writeValueAsString(request.body)) else spec)
                .retrieve().toEntity(String::class.java)
            entity.statusCode.value() to parse(entity.body)
        } catch (ex: RestClientResponseException) {
            // A rejected token means the request was not processed: renew it once and send again.
            if (ex.statusCode.value() == HTTP_UNAUTHORISED && retryOnUnauthorised && token != null) {
                tokens.invalidate(instruction.registryCode)
                send(instruction, settings, request, retryOnUnauthorised = false)
            } else {
                throw ex
            }
        }
    }

    private fun classify(ex: RestClientResponseException, mapping: RegistryApiMapping): GatewayResult {
        val status = ex.statusCode
        val body = parse(ex.responseBodyAsString)
        return when {
            status.value() in NOT_ACCEPTED -> GatewayResult.Retry("Registry answered ${status.value()}: ${mapping.refusal(status.value(), body)}")
            status.is4xxClientError -> GatewayResult.Failed(mapping.refusal(status.value(), body), body)
            else -> GatewayResult.Unknown("Registry answered ${status.value()}; the instruction may have been processed")
        }
    }

    private fun mapping(registryCode: String): RegistryApiMapping? {
        val settings = properties.settings(registryCode) ?: return null
        val mapping = mappings[settings.mapping.uppercase()] ?: return null
        if (!mapping.verified && !settings.allowUnverified) {
            log.warn("API mapping {} for {} is not verified against the registry's specification; set allow-unverified to use it", mapping.name, registryCode)
            return null
        }
        return mapping
    }

    private fun settings(registryCode: String): AdapterSettings = checkNotNull(properties.settings(registryCode)) { "No adapter settings for $registryCode" }

    private fun parse(body: String?): Map<String, Any?> =
        if (body.isNullOrBlank()) emptyMap() else runCatching { Json.mapper.readValue(body, MAP) }.getOrElse { mapOf("raw" to body) }

    private fun ResourceAccessException.notReached(): Boolean = generateSequence(cause) { it.cause }
        .any { it is ConnectException || it is HttpConnectTimeoutException }

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        const val HTTP_UNAUTHORISED = 401
        const val HTTP_NOT_FOUND = 404

        /** The registry says it did not take the request: 408 request timeout, 429 too many requests, 503 unavailable. */
        val NOT_ACCEPTED = setOf(408, 429, 503)
        val MAP = object : TypeReference<Map<String, Any?>>() {}
    }
}
