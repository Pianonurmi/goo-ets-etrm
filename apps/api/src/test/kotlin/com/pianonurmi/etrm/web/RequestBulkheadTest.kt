package com.pianonurmi.etrm.web

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RequestBulkheadTest {
    private val meters = SimpleMeterRegistry()
    private val bulkhead = RequestBulkhead(1, Duration.ofMillis(50), meters)

    private fun request(path: String) = MockHttpServletRequest("GET", path).apply { requestURI = path }

    @Test
    fun `past the limit an API request waits briefly and is refused with 503, and other paths pass`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = Thread.ofVirtual().start {
            bulkhead.doFilter(
                request("/api/v1/trades"),
                MockHttpServletResponse(),
                FilterChain { _, _ ->
                    inside.countDown()
                    release.await()
                },
            )
        }
        inside.await(5, TimeUnit.SECONDS) shouldBe true

        val refused = MockHttpServletResponse()
        bulkhead.doFilter(request("/api/v1/lots"), refused, MockFilterChain())
        refused.status shouldBe 503
        refused.getHeader("Retry-After") shouldBe "2"
        refused.contentAsString shouldContain "busy"
        meters.counter("etrm.http.bulkhead.rejected").count() shouldBe 1.0

        val page = MockHttpServletResponse()
        bulkhead.doFilter(request("/trades"), page, MockFilterChain())
        page.status shouldBe 200

        release.countDown()
        holding.join()
        val after = MockHttpServletResponse()
        bulkhead.doFilter(request("/api/v1/lots"), after, MockFilterChain())
        after.status shouldBe 200
    }
}
