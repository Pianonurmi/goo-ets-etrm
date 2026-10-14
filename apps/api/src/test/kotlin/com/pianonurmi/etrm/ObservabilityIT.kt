package com.pianonurmi.etrm

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatusCode
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

/**
 * Production chain with the actuator on its own port (as in Kubernetes): Prometheus scrapes the management port
 * without a token, the public port keeps metrics behind authentication, and every response carries its trace id.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0", "management.tracing.sampling.probability=1.0"],
)
@ActiveProfiles("secured-test")
@Import(SecuredChainIT.StubJwt::class)
class ObservabilityIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    private fun client(port: Int) =
        RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler(HttpStatusCode::isError) { _, _ -> }.build()

    @Test
    fun `Prometheus scrapes the management port, and the public port keeps metrics private`() {
        client(port).get().uri("/api/v1/ui-config").retrieve().toBodilessEntity()

        val scrape = client(managementPort).get().uri("/actuator/prometheus").retrieve().toEntity(String::class.java)
        scrape.statusCode.value() shouldBe 200
        scrape.body!! shouldContain "jvm_memory_used_bytes"
        scrape.body!! shouldContain "http_server_requests_seconds_bucket"
        scrape.body!! shouldContain "application=\"goo-ets-etrm\""
        // Every scheduled job has its yardstick and last success from the start (JobMetrics).
        scrape.body!! shouldContain Regex("etrm_jobs_expected_interval_seconds\\{[^}]*job_name=\"partition-maintenance\"[^}]*} 86400")
        scrape.body!! shouldContain Regex("etrm_jobs_last_success_seconds\\{[^}]*job_name=\"clearing-margin\"")
        client(managementPort).get().uri("/actuator/health/readiness").retrieve().toEntity(String::class.java)
            .statusCode.value() shouldBe 200

        client(port).get().uri("/actuator/prometheus").retrieve().toBodilessEntity().statusCode.value() shouldBe 401
    }

    @Test
    fun `every response carries its trace id, also problems`() {
        val ok = client(port).get().uri("/api/v1/ui-config").retrieve().toBodilessEntity()
        ok.headers.getFirst("X-Trace-Id")!! shouldMatch Regex("[0-9a-f]{32}")
        val refused = client(port).get().uri("/api/v1/trades").retrieve().toBodilessEntity()
        refused.statusCode.value() shouldBe 401
        refused.headers.getFirst("X-Trace-Id")!! shouldMatch Regex("[0-9a-f]{32}")
    }
}
