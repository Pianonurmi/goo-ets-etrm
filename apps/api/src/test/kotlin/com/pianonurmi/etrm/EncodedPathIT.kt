package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Every operation of the OpenAPI document refuses a caller without credentials, however its path is spelt:
 * plain, with the first segment percent-encoded (`/%61pi/...`), and with every segment's first letter encoded.
 * The container decodes the path before routing, so a matcher that reads the raw URI would let these through.
 */
abstract class EncodedPathChecks {
    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()

    /** Operations that are public on purpose, as `METHOD path`. */
    protected open val public: Set<String> = setOf("GET /api/v1/ui-config")

    /** Unsafe methods may also be refused by CSRF protection before authentication. */
    protected open fun refused(method: String, status: Int): Boolean = status == 401

    private fun operations(): List<Pair<String, String>> {
        val document = JsonMapper.builder().build().readTree(File("openapi/etrm-api.json"))
        val paths = document.get("paths")
        return paths.propertyNames().flatMap { path ->
            paths.get(path).propertyNames()
                .filter { it in setOf("get", "post", "put", "patch", "delete") }
                .map { it.uppercase() to path }
        }.filter { (method, path) -> "$method $path" !in public }
    }

    private fun concrete(path: String) = path.replace(Regex("\\{[^}]+}"), "0190a1b2-0000-7000-8000-000000000001")

    private fun encoded(char: Char) = "%" + "%02x".format(char.code)

    private fun encodeFirst(path: String): String = "/" + encoded(path[1]) + path.substring(2)

    private fun encodeEvery(path: String): String = path.split("/").joinToString("/") { segment ->
        if (segment.isNotEmpty() && segment[0].isLetter()) encoded(segment[0]) + segment.substring(1) else segment
    }

    private fun status(method: String, path: String): Int {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("Accept", "application/json")
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    fun `no operation answers without credentials, however the path is encoded`() {
        val operations = operations()
        (operations.size > 300) shouldBe true
        val leaks = operations.flatMap { (method, template) ->
            val path = concrete(template)
            listOf(path, encodeFirst(path), encodeEvery(path)).mapNotNull { variant ->
                val status = status(method, variant)
                if (refused(method, status)) null else "$method $variant -> $status"
            }
        }
        leaks.shouldBeEmpty()
    }

    @Test
    fun `encoded actuator and api-docs paths need credentials`() {
        listOf("/%61ctuator/sbom", "/%61ctuator/prometheus", "/%76%33/api-docs", "/%61pi/v1/trades").forEach { path ->
            status("GET", path) shouldBe 401
        }
    }
}

/** The production chain (OIDC resource server). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured-test")
class EncodedPathSecuredIT : EncodedPathChecks() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }
}

/** The public showcase chain (session cookie with CSRF). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("showcase", "showcase-test")
class EncodedPathShowcaseIT : EncodedPathChecks() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    override val public = setOf("GET /api/v1/ui-config", "POST /api/v1/auth/login", "POST /api/v1/auth/logout")

    override fun refused(method: String, status: Int) = status == 401 || (method != "GET" && status == 403)
}
