package com.pianonurmi.etrm.config

import com.pianonurmi.etrm.web.Personas
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Writes an RFC 9457 problem from a filter, before the MVC exception handlers exist for the request. */
internal fun HttpServletResponse.problem(status: Int, code: String, detail: String) {
    this.status = status
    contentType = "application/problem+json"
    val escaped = detail.replace("\\", "\\\\").replace("\"", "\\\"")
    writer.write("""{"type":"urn:etrm:problem:$code","title":"$code","status":$status,"detail":"$escaped"}""")
}

/**
 * The demo seeder's credential in the showcase (ADR 0032): 256 random bits made at start, kept only in this JVM and
 * sent by the in-process `DemoClient`. Never configured, logged or returned.
 */
class SeedToken(val value: String = random()) {
    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), value.toByteArray())

    companion object {
        const val HEADER = "X-Etrm-Seed"

        private fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}

/** The seeder acting through the API: the dev persona headers then name the persona, as in the dev profile. */
class SeedAuthentication : AbstractAuthenticationToken(AuthorityUtils.NO_AUTHORITIES) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): Any = "demo-seeder"
}

/**
 * Accepts the seed token only from a loopback peer (behind Caddy every visitor has their own address). The token is
 * the secret; the loopback check is defence in depth. A wrong or remote token is refused outright.
 */
class SeedTokenFilter(private val token: SeedToken) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val sent = request.getHeader(SeedToken.HEADER)
        if (sent == null) {
            chain.doFilter(request, response)
            return
        }
        if (!token.matches(sent) || !isLoopback(request.remoteAddr)) {
            response.problem(HttpServletResponse.SC_FORBIDDEN, "forbidden", "Seed credentials are not accepted here")
            return
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = SeedAuthentication()
        SecurityContextHolder.setContext(context)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun isLoopback(address: String?): Boolean =
        address != null && runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
}

/**
 * A signed-in visitor acts as the persona named in `X-Persona` (default `trader`); an unknown persona is refused. The
 * login name goes to the log context, so the access log shows who acted as whom.
 */
class PersonaFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requested = request.getHeader(PERSONA_HEADER)
        if (requested != null && Personas.find(requested) == null) {
            response.problem(HttpServletResponse.SC_FORBIDDEN, "forbidden", "Unknown persona")
            return
        }
        val login = SecurityContextHolder.getContext().authentication?.takeIf { it !is SeedAuthentication && it.isAuthenticated }?.name
        if (login != null) MDC.put(LOGIN_MDC, login)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(LOGIN_MDC)
        }
    }

    companion object {
        const val PERSONA_HEADER = "X-Persona"
        const val DEFAULT_PERSONA = "trader"
        const val LOGIN_MDC = "etrm.login"
    }
}

/**
 * Brute-force protection for the demo login: five failures per user name or client address within the window lock
 * that key for the window, and the whole login accepts at most [globalPerMinute] attempts a minute.
 */
class LoginThrottle(
    private val clock: Clock = Clock.systemUTC(),
    private val maxFailures: Int = 5,
    private val window: Duration = Duration.ofMinutes(15),
    private val globalPerMinute: Int = 30,
) {
    private val failures = ConcurrentHashMap<String, MutableList<Instant>>()
    private val attempts = ArrayDeque<Instant>()

    /** Seconds to wait before another attempt is allowed, or null when it is allowed now. */
    @Synchronized
    fun retryAfter(username: String?, address: String): Long? {
        val now = clock.instant()
        while (attempts.isNotEmpty() && attempts.first().isBefore(now.minusSeconds(60))) attempts.removeFirst()
        if (attempts.size >= globalPerMinute) return Duration.between(now, attempts.first().plusSeconds(60)).seconds.coerceAtLeast(1)
        val blocked = keys(username, address).mapNotNull { key ->
            val recent = failures[key]?.filter { it.isAfter(now.minus(window)) }.orEmpty()
            if (recent.size >= maxFailures) Duration.between(now, recent.first().plus(window)).seconds.coerceAtLeast(1) else null
        }
        if (blocked.isNotEmpty()) return blocked.max()
        attempts.addLast(now)
        return null
    }

    @Synchronized
    fun failed(username: String?, address: String) {
        val now = clock.instant()
        keys(username, address).forEach { key ->
            failures.compute(key) { _, list ->
                (list ?: mutableListOf()).apply {
                    removeIf { it.isBefore(now.minus(window)) }
                    add(now)
                }
            }
        }
    }

    @Synchronized
    fun succeeded(username: String?) {
        username?.let { failures.remove("user:${it.lowercase()}") }
    }

    private fun keys(username: String?, address: String) = listOfNotNull(username?.lowercase()?.let { "user:$it" }, "ip:$address")
}

class LoginThrottleFilter(private val throttle: LoginThrottle, private val loginPath: String) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method != "POST" || request.requestURI != loginPath

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val wait = throttle.retryAfter(request.getParameter("username"), request.remoteAddr)
        if (wait != null) {
            response.setHeader("Retry-After", wait.toString())
            response.problem(429, "too-many-attempts", "Too many sign-in attempts; try again in $wait seconds")
            return
        }
        chain.doFilter(request, response)
    }
}

/** Loads the deferred CSRF token on every request, so the XSRF-TOKEN cookie is there before the first POST. */
class CsrfCookieFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        chain.doFilter(request, response)
    }
}
