package com.pianonurmi.etrm.registry.adapter.http

import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contract tests of the API channel against a local stub implementing ETRM's generic registry REST contract:
 * token handling, idempotency key, outcome classification and status queries.
 */
class ApiRegistryGatewayTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Recorded>()
    private val tokenCalls = AtomicInteger()

    /** What the stub answers to POST /instructions next. */
    @Volatile private var submitAnswer: (HttpExchange) -> Unit = { respond(it, 201, """{"id":"REG-1","status":"ACCEPTED"}""") }

    private data class Recorded(val method: String, val path: String, val headers: Map<String, String?>, val body: String)

    private val account = UUID.randomUUID()
    private val referenceData = mockk<ReferenceDataQuery> {
        every { requireRegistryAccount(account) } returns mockk<RegistryAccountView> { every { accountNumber } returns "FI-ACC-1" }
    }
    private val lots = mockk<LotQuery> { every { lot(any()) } returns null }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { ex ->
            tokenCalls.incrementAndGet()
            respond(ex, 200, """{"access_token":"t-${tokenCalls.get()}","expires_in":3600}""")
        }
        server.createContext("/api/instructions") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            requests += Recorded(
                ex.requestMethod,
                ex.requestURI.path,
                mapOf("auth" to ex.requestHeaders.getFirst("Authorization"), "key" to ex.requestHeaders.getFirst("Idempotency-Key")),
                body,
            )
            when {
                ex.requestMethod == "POST" -> submitAnswer(ex)
                ex.requestURI.path.endsWith("/known") -> respond(ex, 200, """{"id":"REG-9","status":"COMPLETED"}""")
                else -> respond(ex, 404, """{"error":"not found"}""")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun gateway(vararg extra: RegistryApiMapping, settings: AdapterSettings = settings()): ApiRegistryGateway {
        val properties = RegistryAdapterProperties(mapOf("FINGRID" to settings))
        val clients = RegistryHttpClients(properties)
        return ApiRegistryGateway(listOf(GenericRestMapping(), *extra), clients, properties, RegistryTokens(clients, Clock.systemUTC()), referenceData, lots)
    }

    private fun settings(mapping: String = "GENERIC_REST", allowUnverified: Boolean = false) = AdapterSettings(
        enabled = true,
        baseUrl = "http://127.0.0.1:${server.address.port}/api",
        clientId = "etrm",
        tokenUrl = "http://127.0.0.1:${server.address.port}/token",
        // Generous, so a busy CI runner does not turn a quick stub answer into a timeout; the timeout cases use SHORT_TIMEOUT.
        readTimeout = Duration.ofSeconds(10),
        retryBackoff = Duration.ofMillis(5),
        mapping = mapping,
        allowUnverified = allowUnverified,
    )

    private fun instruction(key: String = "plan-1-line-1") = RegistryInstructionView(
        id = UUID.randomUUID(),
        instructionKey = key,
        type = InstructionType.TRANSFER,
        registryCode = "FINGRID",
        holderEntityId = UUID.randomUUID(),
        fromAccountId = account,
        toRegistryCode = "FINGRID",
        toAccountRef = "FI-CP-9",
        lotId = null,
        range = CertificateRange.of(1, 100),
        quantity = 100,
        cancellation = null,
        planRef = null,
        lineRef = null,
        obligationRef = null,
        status = InstructionStatus.DISPATCHING,
        externalRef = null,
        attempts = 0,
        lastError = null,
        submittedBy = "ops",
        confirmedBy = null,
        createdAt = Instant.now(),
        sentAt = null,
        confirmedAt = null,
        version = 1,
        channel = "API",
    )

    @Test
    fun `a submission carries a bearer token and the instruction key, and acceptance means sent`() {
        val gateway = gateway()
        gateway.supports("FINGRID") shouldBe true
        gateway.supports("CESAR") shouldBe false
        gateway.execute(instruction()) shouldBe GatewayResult.Sent("REG-1", mapOf("id" to "REG-1", "status" to "ACCEPTED"))
        gateway.execute(instruction("plan-1-line-2"))
        tokenCalls.get() shouldBe 1
        requests.first().let {
            it.method shouldBe "POST"
            it.headers["auth"] shouldBe "Bearer t-1"
            it.headers["key"] shouldBe "plan-1-line-1"
            val body = Json.mapper.readValue(it.body, Map::class.java)
            body["fromAccount"] shouldBe "FI-ACC-1"
            body["toAccount"] shouldBe "FI-CP-9"
            body["certificateStart"] shouldBe "1"
            body["unit"] shouldBe "MWH"
        }
    }

    @Test
    fun `a completed answer confirms and a refusal fails`() {
        submitAnswer = { respond(it, 200, """{"id":"REG-2","status":"COMPLETED"}""") }
        gateway().execute(instruction()).shouldBeInstanceOf<GatewayResult.Confirmed>().externalRef shouldBe "REG-2"
        submitAnswer = { respond(it, 422, """{"error":"INSUFFICIENT","message":"Certificates 1-100 are not on account FI-ACC-1"}""") }
        gateway().execute(instruction()) shouldBe GatewayResult.Failed(
            "Certificates 1-100 are not on account FI-ACC-1",
            mapOf("error" to "INSUFFICIENT", "message" to "Certificates 1-100 are not on account FI-ACC-1"),
        )
    }

    @Test
    fun `not accepted is retried, possibly processed is unknown`() {
        submitAnswer = { respond(it, 503, """{"error":"maintenance"}""") }
        gateway().execute(instruction()).shouldBeInstanceOf<GatewayResult.Retry>()
        submitAnswer = { respond(it, 502, "") }
        gateway().execute(instruction()).shouldBeInstanceOf<GatewayResult.Unknown>()
        submitAnswer = {
            Thread.sleep(1_000)
            respond(it, 201, """{"id":"late"}""")
        }
        gateway(settings = settings().copy(readTimeout = SHORT_TIMEOUT)).execute(instruction()).shouldBeInstanceOf<GatewayResult.Unknown>()
        val unreachable = settings().copy(baseUrl = "http://127.0.0.1:1/api", tokenUrl = null)
        gateway(settings = unreachable).execute(instruction()).shouldBeInstanceOf<GatewayResult.Retry>()
    }

    @Test
    fun `an answer whose body cannot be read is unknown`() {
        submitAnswer = { ex ->
            // The headers arrive in time and the body does not, so the instruction may have been processed.
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(201, 20)
            ex.responseBody.flush()
            Thread.sleep(1_000)
            ex.responseBody.use { it.write("""{"id":"late-body"} """.toByteArray()) }
        }
        gateway(settings = settings().copy(readTimeout = SHORT_TIMEOUT)).execute(instruction()).shouldBeInstanceOf<GatewayResult.Unknown>()
    }

    @Test
    fun `a rejected token is renewed once`() {
        val attempts = AtomicInteger()
        submitAnswer = { if (attempts.incrementAndGet() == 1) respond(it, 401, """{"error":"expired"}""") else respond(it, 201, """{"id":"REG-3"}""") }
        gateway().execute(instruction()).shouldBeInstanceOf<GatewayResult.Sent>()
        tokenCalls.get() shouldBe 2
        requests.map { it.headers["auth"] } shouldBe listOf("Bearer t-1", "Bearer t-2")
    }

    @Test
    fun `status queries answer by instruction key`() {
        val gateway = gateway()
        gateway.status(instruction("known")).shouldBeInstanceOf<GatewayResult.Confirmed>().externalRef shouldBe "REG-9"
        gateway.status(instruction("unknown")) shouldBe null
        requests.last().let {
            it.method shouldBe "GET"
            it.path shouldBe "/api/instructions/unknown"
        }
    }

    @Test
    fun `unverified mappings are used only where explicitly allowed`() {
        val draft = object : RegistryApiMapping by GenericRestMapping() {
            override val name = "DRAFT_REGISTRY"
            override val verified = false

            override fun submit(instruction: RegistryInstructionView, context: InstructionContext) =
                ApiRequest(HttpMethod.POST, "/instructions", mapOf("key" to instruction.instructionKey))
        }
        gateway(draft, settings = settings("DRAFT_REGISTRY")).supports("FINGRID") shouldBe false
        gateway(draft, settings = settings("DRAFT_REGISTRY", allowUnverified = true)).supports("FINGRID") shouldBe true
    }

    private companion object {
        val SHORT_TIMEOUT: Duration = Duration.ofMillis(300)
    }
}
