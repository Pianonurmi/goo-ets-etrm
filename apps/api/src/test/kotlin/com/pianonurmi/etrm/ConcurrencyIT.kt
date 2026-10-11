package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Requests on virtual threads with two carriers (see `apps/api/build.gradle.kts`): a cold context answers a burst of
 * concurrent first requests that serialise many different views at once. On Java 21 this hung the server: every carrier
 * was pinned in Jackson's and kotlin-reflect's synchronized caches.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // A context of its own, so Jackson's and kotlin-reflect's caches are cold.
    properties = ["etrm.test.context=concurrency"],
)
@ActiveProfiles("test")
class ConcurrencyIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private const val ROUNDS = 6
        private val PATHS = listOf(
            "/api/v1/reference/legal-entities",
            "/api/v1/reference/counterparties",
            "/api/v1/reference/registries",
            "/api/v1/trades?limit=50",
            "/api/v1/inventory/lots?limit=50",
            "/api/v1/settlement/items",
            "/api/v1/settlement/vat-rules",
            "/api/v1/registry/instructions/search",
            "/api/v1/inventory/breaks",
            "/api/v1/workflow/tasks",
            "/api/v1/confirmations",
            "/api/v1/market-data/curves",
            "/api/v1/market-data/instruments",
            "/api/v1/products/buckets",
            "/api/v1/taxonomy/ENERGY_SOURCE",
            "/api/v1/ui-config",
            "/api/v1/me",
        )
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a burst of concurrent first requests completes`() {
        ApiScenario(port).referenceData().receiveLot(1, 10)
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requests = (1..ROUNDS).flatMap { PATHS }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(requests.size)
        val results = requests.map { path ->
            pool.submit<String?> {
                start.await()
                val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
                    .header("X-Actor", "burst").header("X-Roles", ApiScenario.ALL_ROLES)
                    .timeout(Duration.ofSeconds(30)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() >= 500) "$path answered ${response.statusCode()}" else null
            }
        }
        start.countDown()
        pool.shutdown()
        val finished = pool.awaitTermination(60, TimeUnit.SECONDS)
        val problems = results.mapNotNull { if (it.isDone) it.get() else "timed out" }
        (if (finished) problems else problems + "requests still running after 60 s").shouldBeEmpty()
    }
}
