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
 * Every command of the OpenAPI document, sent by a signed-in user who holds no role and sees one unrelated legal
 * entity, is refused. Authorisation is written by hand in each controller or service, so this is the net under
 * all of them: a new endpoint without a guard fails here unless it is listed below with its reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OperationGuardsIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        const val ELSEWHERE = "0190a1b2-0000-7000-8000-00000000abcd"
        const val SOME_ID = "0190a1b2-0000-7000-8000-000000000001"

        /** Commands any signed-in user may send, as `METHOD path`, each with the reason. */
        val OPEN: Map<String, String> = mapOf(
            "POST /api/v1/allocation/plans/what-if" to "computes and stores nothing; refused anyway without a role",
        )
    }

    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()

    private fun commands(): List<Pair<String, String>> {
        val document = JsonMapper.builder().build().readTree(File("openapi/etrm-api.json"))
        val paths = document.get("paths")
        return paths.propertyNames().flatMap { path ->
            paths.get(path).propertyNames()
                .filter { it in setOf("post", "put", "patch", "delete") }
                .map { it.uppercase() to path }
        }
    }

    private fun send(method: String, path: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .method(method, HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Actor", "nobody")
            .header("X-Roles", "")
            .header("X-Entities", ELSEWHERE)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body().take(200)
    }

    @Test
    fun `no command succeeds for a user without roles`() {
        val commands = commands()
        (commands.size > 140) shouldBe true
        val accepted = commands
            .filter { (method, path) -> "$method $path" !in OPEN }
            .mapNotNull { (method, template) ->
                val path = template.replace(Regex("\\{[^}]+}"), SOME_ID)
                val (status, body) = send(method, path)
                if (status in 200..299) "$method $template -> $status $body" else null
            }
        accepted.shouldBeEmpty()
    }
}
