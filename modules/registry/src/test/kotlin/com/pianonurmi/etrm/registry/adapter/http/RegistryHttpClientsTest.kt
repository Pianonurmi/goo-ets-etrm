package com.pianonurmi.etrm.registry.adapter.http

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RegistryHttpClientsTest {
    private lateinit var server: HttpServer
    private val calls = AtomicInteger()
    private val stalled = CountDownLatch(1)
    private lateinit var settings: AdapterSettings
    private val clients = RegistryHttpClients(RegistryAdapterProperties())

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/flaky") { exchange ->
            val n = calls.incrementAndGet()
            val (status, body) = if (n < 3) 503 to "busy" else 200 to "ok"
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/missing") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/slow") { exchange ->
            // Holds the answer until the test has seen the timeout; teardown lets it go.
            stalled.await(STALL_LIMIT_SECONDS, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        settings = AdapterSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:${server.address.port}",
            readTimeout = Duration.ofMillis(200),
            maxAttempts = 3,
            retryBackoff = Duration.ofMillis(10),
        )
    }

    @AfterEach
    fun stop() {
        stalled.countDown()
        server.stop(0)
    }

    @Test
    fun `idempotent reads retry server errors until they succeed`() {
        val client = clients.client(settings)
        clients.idempotentRead(settings, "flaky read") { client.get().uri("/flaky").retrieve().body(String::class.java) } shouldBe "ok"
        calls.get() shouldBe 3
    }

    @Test
    fun `client errors are not retried`() {
        val client = clients.client(settings)
        shouldThrow<HttpClientErrorException> {
            clients.idempotentRead(settings, "missing") { client.get().uri("/missing").retrieve().toBodilessEntity() }
        }
        calls.get() shouldBe 1
    }

    @Test
    fun `read timeout surfaces as a transport error`() {
        val client = clients.client(settings.copy(maxAttempts = 1))
        shouldThrow<ResourceAccessException> { client.get().uri("/slow").retrieve().toBodilessEntity() }
    }

    @Test
    fun `secrets come only from the named environment variable`() {
        AdapterSettings(credentialEnv = "REG_SECRET").credential { if (it == "REG_SECRET") "s3cr3t" else null } shouldBe "s3cr3t"
        AdapterSettings().credential { "ignored" } shouldBe null
        RegistryAdapterProperties(mapOf("FINGRID" to settings)).settings("fingrid")?.enabled shouldBe true
    }
}

private const val STALL_LIMIT_SECONDS = 10L
