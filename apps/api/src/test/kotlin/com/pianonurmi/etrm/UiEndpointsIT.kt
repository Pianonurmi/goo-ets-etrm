package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatusCode
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

/** Bootstrap endpoints, SPA serving and framework errors as problem details (test profile, header actors). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UiEndpointsIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler(HttpStatusCode::isError) { _, _ -> }.build() }

    @Test
    fun `ui-config tells the browser to act as a persona`() {
        val config = client.get().uri("/api/v1/ui-config").retrieve().body(Map::class.java)!!
        config["authMode"] shouldBe "DEV_HEADERS"
        config["environment"] shouldBe "TEST"
        config["oidc"] shouldBe null
        @Suppress("UNCHECKED_CAST")
        val personas = config["personas"] as List<Map<String, Any?>>
        personas.map { it["id"] } shouldContainAll listOf("trader", "middle", "middle2", "risk", "risk2", "admin")
        personas.single { it["id"] == "backoffice" }["roles"] shouldBe listOf("BACK_OFFICE", "REGISTRY_OPERATOR")
    }

    @Test
    fun `me reflects the acting user, roles and entity scope`() {
        val entity = "0190a1b2-0000-7000-8000-000000000001"
        val me = client.get().uri("/api/v1/me")
            .header("X-Actor", "maria").header("X-Roles", "TRADER,RISK").header("X-Entities", entity)
            .retrieve().body(Map::class.java)!!
        me["userId"] shouldBe "maria"
        me["roles"] shouldBe listOf("TRADER", "RISK")
        me["legalEntityIds"] shouldBe listOf(entity)
        me["allEntities"] shouldBe false
    }

    @Test
    fun `the browser reports an error it could not handle, and a malformed report is refused`() {
        fun send(body: String) = client.post().uri("/api/v1/client-errors")
            .header("X-Actor", "maria").header("X-Roles", "").header("Content-Type", "application/json").body(body)
            .retrieve().toEntity(Map::class.java)

        send("""{"kind":"UNHANDLED_REJECTION","message":"boom","page":"/trades","lastTraceId":"4bf92f3577b34da6a3ce929d0e0e4736"}""")
            .statusCode.value() shouldBe 202
        send("""{"kind":"ERROR","message":"boom","lastTraceId":"not a trace id"}""").statusCode.value() shouldBe 422
        send("""{"kind":"ERROR","message":""}""").statusCode.value() shouldBe 422
    }

    @Test
    fun `client routes fall back to the application shell`() {
        val deepLink = client.get().uri("/trades/0190a1b2-0000-7000-8000-000000000001").retrieve().toEntity(String::class.java)
        deepLink.statusCode.value() shouldBe 200
        deepLink.body!! shouldContain "etrm-test-shell"
        deepLink.headers.cacheControl!! shouldContain "no-cache"
        deepLink.headers.getFirst("Content-Security-Policy")!! shouldContain "default-src 'self'"

        val asset = client.get().uri("/assets/app-test.js").retrieve().toEntity(String::class.java)
        asset.statusCode.value() shouldBe 200
        asset.headers.cacheControl!! shouldContain "immutable"

        client.get().uri("/assets/missing.js").retrieve().toBodilessEntity().statusCode.value() shouldBe 404
        client.get().uri("/favicon-missing.ico").retrieve().toBodilessEntity().statusCode.value() shouldBe 404
    }

    @Test
    fun `framework errors are problem details with their own status`() {
        val unknown = client.get().uri("/api/v1/nope").retrieve().toEntity(Map::class.java)
        unknown.statusCode.value() shouldBe 404
        unknown.body!!["type"] shouldBe "urn:etrm:problem:not-found"

        val wrongMethod = client.delete().uri("/api/v1/ui-config").retrieve().toEntity(Map::class.java)
        wrongMethod.statusCode.value() shouldBe 405

        val badId = client.get().uri("/api/v1/trades/not-a-uuid").retrieve().toEntity(Map::class.java)
        badId.statusCode.value() shouldBe 400
        badId.body!!["type"] shouldBe "urn:etrm:problem:bad-request"

        // A body of the wrong shape names the field, not the parser's internals.
        val badBody = client.post().uri("/api/v1/trades").header("Content-Type", "application/json").body("""{"legs":"not a list"}""")
            .retrieve().toEntity(Map::class.java)
        badBody.statusCode.value() shouldBe 400
        badBody.body!!["detail"].toString() shouldContain "legs"
        badBody.body!!["detail"].toString() shouldNotContain "com.pianonurmi"
    }

    @Test
    fun `swagger ui and the api document are still served`() {
        client.get().uri("/swagger-ui/index.html").retrieve().toBodilessEntity().statusCode.value() shouldBe 200
        client.get().uri("/v3/api-docs").retrieve().toBodilessEntity().statusCode.value() shouldBe 200
    }
}
