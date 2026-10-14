package com.pianonurmi.etrm.web

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Caps the API requests in progress (review finding O3). Requests run on virtual threads, so nothing else limits how
 * many wait for a database connection at once; past the cap a request waits up to [queueTimeout] for a slot and is
 * then refused with 503 and `Retry-After`, which a client or the ingress retries, instead of piling up until every
 * request times out. Only `/api/` is limited: the UI bundle and the actuator stay reachable under load.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class RequestBulkhead(
    @Value("\${etrm.http.max-concurrent-requests:200}") private val maxConcurrent: Int,
    @Value("\${etrm.http.queue-timeout:PT2S}") private val queueTimeout: Duration,
    meters: MeterRegistry,
) : OncePerRequestFilter() {
    private val slots = Semaphore(maxConcurrent, true)
    private val rejected: Counter = Counter.builder("etrm.http.bulkhead.rejected")
        .description("API requests refused with 503 because the concurrent request limit was reached")
        .register(meters)

    init {
        Gauge.builder("etrm.http.bulkhead.in.use") { (maxConcurrent - slots.availablePermits()).toDouble() }
            .description("API requests in progress, counted against etrm.http.max-concurrent-requests")
            .register(meters)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!slots.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            rejected.increment()
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            response.setHeader("Retry-After", RETRY_AFTER_SECONDS.toString())
            response.contentType = "application/problem+json"
            response.writer.write(
                """{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"The server is busy; try again shortly"}""",
            )
            return
        }
        try {
            chain.doFilter(request, response)
        } finally {
            slots.release()
        }
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 2
    }
}
