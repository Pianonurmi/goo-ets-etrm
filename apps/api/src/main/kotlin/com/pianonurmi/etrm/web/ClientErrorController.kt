package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.security.ActorProvider
import io.micrometer.core.instrument.MeterRegistry
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

enum class ClientErrorKind {
    /** `window.onerror`: an exception nobody caught. */
    ERROR,

    /** `unhandledrejection`: a promise rejected without a handler. */
    UNHANDLED_REJECTION,

    /** A render error the router's error page caught. */
    ROUTE_ERROR,
}

data class ClientErrorReport(
    val kind: ClientErrorKind,
    @field:NotBlank @field:Size(max = 1000) val message: String,
    @field:Size(max = 8000) val stack: String? = null,
    /** The page's path and query, without the origin. */
    @field:Size(max = 500) val page: String? = null,
    /** The `X-Trace-Id` of the last API response the browser saw, to find the request that led to the error. */
    @field:Pattern(regexp = "^[0-9a-f]{16,32}$") val lastTraceId: String? = null,
)

/**
 * Errors the web UI could not handle, reported by the browser (`shared/telemetry`). Each is logged at WARN with the
 * user and the last trace id, and counted in `etrm.web.client.errors`. A user may report [perUserPerMinute] a minute
 * and everyone together [globalPerMinute]; reports past that are counted as dropped and not logged. The answer is
 * 202 either way, so the browser never retries.
 */
@RestController
@RequestMapping("/api/v1")
class ClientErrorController(
    private val actors: ActorProvider,
    private val meters: MeterRegistry,
    private val clock: Clock,
    @Value("\${etrm.web.client-errors.per-user-per-minute:10}") perUserPerMinute: Int,
    @Value("\${etrm.web.client-errors.global-per-minute:300}") globalPerMinute: Int,
) {
    private val limiter = ClientErrorLimiter(perUserPerMinute, globalPerMinute)

    @PostMapping("/client-errors")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun report(
        @Valid @RequestBody body: ClientErrorReport,
    ) {
        val user = actors.current().userId
        val accepted = limiter.tryAcquire(user, clock.instant())
        meters.counter(METER, "kind", body.kind.name, "outcome", if (accepted) "logged" else "dropped").increment()
        if (!accepted) return
        log.atWarn()
            .addKeyValue("client.error.kind", body.kind.name)
            .addKeyValue("client.error.page", body.page?.oneLine())
            .addKeyValue("client.error.last_trace_id", body.lastTraceId)
            .addKeyValue("client.error.stack", body.stack)
            .addKeyValue("user.id", user)
            .log("Client error ({}) on {}: {}", body.kind, body.page?.oneLine(), body.message.oneLine())
    }

    private companion object {
        const val METER = "etrm.web.client.errors"
        val log = LoggerFactory.getLogger("etrm.client-errors")

        /** No line breaks into a plain-text log line: one report is one line. */
        fun String.oneLine() = replace(Regex("[\\r\\n\\t]+"), " ")
    }
}

/** Fixed one-minute windows per user and overall. Keys of past windows are dropped, so the map stays small. */
class ClientErrorLimiter(private val perUser: Int, private val global: Int) {
    private val counts = HashMap<String, Int>()
    private var window = Long.MIN_VALUE
    private var total = 0

    @Synchronized
    fun tryAcquire(user: String, now: Instant): Boolean {
        val minute = now.epochSecond / SECONDS_PER_MINUTE
        if (minute != window) {
            window = minute
            total = 0
            counts.clear()
        }
        if (total >= global) return false
        val used = counts.getOrDefault(user, 0)
        if (used >= perUser) return false
        counts[user] = used + 1
        total++
        return true
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
