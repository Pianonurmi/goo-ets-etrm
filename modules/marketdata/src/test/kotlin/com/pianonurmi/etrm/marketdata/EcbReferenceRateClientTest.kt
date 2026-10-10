package com.pianonurmi.etrm.marketdata

import com.pianonurmi.etrm.marketdata.adapter.http.EcbPullJob
import com.pianonurmi.etrm.marketdata.adapter.http.EcbReferenceRateClient
import com.pianonurmi.etrm.marketdata.api.ImportResult
import com.pianonurmi.etrm.marketdata.api.MarketDataCommand
import com.pianonurmi.etrm.marketdata.application.EcbSettings
import com.pianonurmi.etrm.marketdata.application.MarketDataService
import com.pianonurmi.etrm.marketdata.application.MarketDataSettings
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The ECB adapter against a local stub serving the reference rate file: retries, refusals and timeouts. */
class EcbReferenceRateClientTest {
    private lateinit var server: HttpServer
    private val calls = AtomicInteger()
    private val xml = javaClass.getResourceAsStream("/marketdata/eurofxref-daily.xml")!!.readBytes()

    @Volatile private var answer: (HttpExchange, Int) -> Unit = { ex, _ -> respond(ex, 200, xml) }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stats/eurofxref/eurofxref-daily.xml") { ex -> answer(ex, calls.incrementAndGet()) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun respond(ex: HttpExchange, status: Int, body: ByteArray) {
        ex.sendResponseHeaders(status, body.size.toLong().takeIf { it > 0 } ?: -1)
        ex.responseBody.use { if (body.isNotEmpty()) it.write(body) }
    }

    private fun settings(enabled: Boolean = true) = MarketDataSettings(
        ecb = EcbSettings(
            enabled = enabled,
            baseUrl = "http://127.0.0.1:${server.address.port}",
            readTimeout = Duration.ofMillis(300),
            maxAttempts = 3,
            retryBackoff = Duration.ofMillis(5),
        ),
    )

    @Test
    fun `server errors are retried and the file is returned`() {
        answer = { ex, n -> if (n < 3) respond(ex, 503, ByteArray(0)) else respond(ex, 200, xml) }
        EcbReferenceRateClient(settings()).fetchDaily() shouldBe xml
        calls.get() shouldBe 3
    }

    @Test
    fun `a client error is not retried and a timeout fails after the attempts`() {
        answer = { ex, _ -> respond(ex, 404, ByteArray(0)) }
        shouldThrow<IOException> { EcbReferenceRateClient(settings()).fetchDaily() }
        calls.get() shouldBe 1
        answer = { ex, _ ->
            Thread.sleep(600)
            respond(ex, 200, xml)
        }
        shouldThrow<IOException> { EcbReferenceRateClient(settings()).fetchDaily() }
    }

    @Test
    fun `the job imports through the ECB format only when enabled`() {
        val command = mockk<MarketDataCommand>()
        val bytes = slot<ByteArray>()
        every {
            command.importFile("ECB_DAILY_XML", any(), capture(bytes))
        } returns ImportResult(UUID.randomUUID(), "ECB_DAILY_XML", "f", 8, 8, 0, 0, emptyList(), false)
        val clock = Clock.fixed(Instant.parse("2026-10-09T14:30:00Z"), ZoneOffset.UTC)
        val meters = mockk<ObjectProvider<io.micrometer.core.instrument.MeterRegistry>>(relaxed = true)

        EcbPullJob(EcbReferenceRateClient(settings(enabled = false)), command, mockk<MarketDataService>(), settings(enabled = false), clock, meters).scheduled()
        verify(exactly = 0) { command.importFile(any(), any(), any()) }

        EcbPullJob(EcbReferenceRateClient(settings()), command, mockk<MarketDataService>(), settings(), clock, meters).scheduled()
        bytes.captured shouldBe xml
    }
}
