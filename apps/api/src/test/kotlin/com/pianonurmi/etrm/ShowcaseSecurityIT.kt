package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.SeedToken
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** The public showcase (ADR 0032): demo sign-in with CSRF, personas, throttling, and the seeder's in-memory token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("showcase", "showcase-test")
class ShowcaseSecurityIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var seedToken: SeedToken

    private val json = JsonMapper.builder().build()

    /** One browser: keeps its cookies, reads XSRF-TOKEN like the SPA does. */
    private inner class Browser(private val forwardedFor: String? = null) {
        private val cookies = CookieManager()
        private val http = HttpClient.newBuilder().cookieHandler(cookies).build()

        fun xsrf(): String? = cookies.cookieStore.cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value

        fun send(method: String, path: String, body: String? = null, form: Boolean = false, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .header("Accept", "application/json")
            if (body != null) builder.header("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            forwardedFor?.let { builder.header("X-Forwarded-For", it) }
            headers.forEach { (name, value) -> builder.header(name, value) }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }

        fun get(path: String, headers: Map<String, String> = emptyMap()) = send("GET", path, headers = headers)

        fun login(username: String, password: String, withCsrf: Boolean = true): HttpResponse<String> {
            get("/api/v1/ui-config")
            val body = "username=${enc(username)}&password=${enc(password)}"
            val csrf = if (withCsrf) mapOf("X-XSRF-TOKEN" to (xsrf() ?: "")) else emptyMap()
            return send("POST", "/api/v1/auth/login", body, form = true, headers = csrf)
        }

        fun me(persona: String? = null, extra: Map<String, String> = emptyMap()): Map<*, *> {
            val response = get("/api/v1/me", persona?.let { mapOf("X-Persona" to it) }.orEmpty() + extra)
            response.statusCode() shouldBe 200
            return json.readValue(response.body(), Map::class.java)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    @Test
    fun `ui-config offers the showcase sign-in and the personas`() {
        val config = json.readValue(Browser().get("/api/v1/ui-config").body(), Map::class.java)
        config["authMode"] shouldBe "SHOWCASE"
        (config["personas"] as List<*>).map { (it as Map<*, *>)["id"] } shouldContain "middle2"
    }

    @Test
    fun `nothing is reachable without signing in, and dev headers open nothing`() {
        val browser = Browser()
        browser.get("/api/v1/me").statusCode() shouldBe 401
        browser.get("/api/v1/trades/search", mapOf("X-Actor" to "mallory", "X-Roles" to "ADMIN")).statusCode() shouldBe 401
        browser.get("/v3/api-docs").statusCode() shouldBe 401
        browser.get("/actuator/prometheus").statusCode() shouldBe 401
    }

    @Test
    fun `sign-in needs the CSRF token and the right password`() {
        Browser().login("demo", "demo-password-for-tests", withCsrf = false).statusCode() shouldBe 403
        Browser(forwardedFor = "198.51.100.20").login("demo", "wrong-password-here").statusCode() shouldBe 401
        Browser().login("demo", "demo-password-for-tests").statusCode() shouldBe 204
        Browser().login("visitor", "correct-horse-battery").statusCode() shouldBe 204
    }

    @Test
    fun `a signed-in visitor acts as the chosen persona, never as dev headers`() {
        val browser = Browser()
        browser.login("visitor", "correct-horse-battery").statusCode() shouldBe 204

        val trader = browser.me()
        trader["userId"] shouldBe "trader"
        trader["login"] shouldBe "visitor"
        trader["authMode"] shouldBe "SHOWCASE"

        val middle = browser.me("middle2", mapOf("X-Actor" to "mallory", "X-Roles" to "ADMIN"))
        middle["userId"] shouldBe "middle2"
        middle["roles"] shouldBe listOf("MIDDLE_OFFICE")

        browser.get("/api/v1/me", mapOf("X-Persona" to "root")).statusCode() shouldBe 403

        // State-changing calls need the CSRF header too.
        browser.send("POST", "/api/v1/auth/logout").statusCode() shouldBe 403
        browser.send("POST", "/api/v1/auth/logout", headers = mapOf("X-XSRF-TOKEN" to browser.xsrf()!!)).statusCode() shouldBe 204
        browser.get("/api/v1/me").statusCode() shouldBe 401
    }

    @Test
    fun `five failures lock the address, with a time to retry`() {
        val attacker = Browser(forwardedFor = "203.0.113.7")
        repeat(5) { attacker.login("demo", "guess-number-$it").statusCode() shouldBe 401 }
        val locked = attacker.login("demo", "demo-password-for-tests")
        locked.statusCode() shouldBe 429
        locked.headers().firstValue("Retry-After").isPresent shouldBe true
        locked.body() shouldContain "too-many-attempts"
    }

    @Test
    fun `the seed token works from loopback only, and only when it is right`() {
        val seeder = Browser()
        val headers = mapOf("X-Actor" to "middle", "X-Roles" to "MIDDLE_OFFICE")
        seeder.me(extra = headers + (SeedToken.HEADER to seedToken.value))["userId"] shouldBe "middle"
        seeder.get("/api/v1/me", headers + (SeedToken.HEADER to "guessed")).statusCode() shouldBe 403
        Browser(forwardedFor = "203.0.113.9").get("/api/v1/me", headers + (SeedToken.HEADER to seedToken.value)).statusCode() shouldBe 403
        seedToken.value shouldNotBe ""
    }
}
