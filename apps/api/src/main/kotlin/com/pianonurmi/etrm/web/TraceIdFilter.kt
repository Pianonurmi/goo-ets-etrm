package com.pianonurmi.etrm.web

import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Returns the request's trace id in `X-Trace-Id`, so a user can quote it and support finds the trace and the log
 * lines of that request. Runs after the server observation filter has opened the request's span.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TraceIdFilter(private val tracer: Tracer) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        tracer.currentSpan()?.context()?.traceId()?.let { response.setHeader(HEADER, it) }
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Trace-Id"
    }
}
