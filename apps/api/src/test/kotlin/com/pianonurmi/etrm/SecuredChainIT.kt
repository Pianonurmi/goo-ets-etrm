package com.pianonurmi.etrm

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatusCode
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.time.Instant

/** The production chain: public UI shell and configuration, bearer token for everything under /api. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured-test")
@Import(SecuredChainIT.StubJwt::class)
class SecuredChainIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        const val ENTITY = "0190a1b2-0000-7000-8000-000000000002"
    }

    @TestConfiguration
    class StubJwt {
        /** Accepts the single token `trader-token`; anything else is rejected like a bad signature. */
        @Bean
        fun jwtDecoder(): JwtDecoder = JwtDecoder { token ->
            if (token != "trader-token") throw BadJwtException("invalid token")
            Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("u-trader")
                .claim("name", "Tia Trader")
                .claim("roles", listOf("TRADER"))
                .claim("legal_entities", listOf(ENTITY))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build()
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler(HttpStatusCode::isError) { _, _ -> }.build() }

    @Test
    fun `the shell and ui-config are public`() {
        val shell = client.get().uri("/inbox").retrieve().toEntity(String::class.java)
        shell.statusCode.value() shouldBe 200
        shell.headers.getFirst("Content-Security-Policy")!! shouldContain "connect-src 'self' https://login.example.test"
        shell.headers.getFirst("Content-Security-Policy")!! shouldContain "frame-ancestors 'none'"
        shell.headers.getFirst("X-Frame-Options") shouldBe "DENY"

        // The silent-renew page runs in a frame of the application itself, and only there (review finding W1).
        val silent = client.get().uri("/auth/silent").retrieve().toEntity(String::class.java)
        silent.statusCode.value() shouldBe 200
        silent.headers.getFirst("Content-Security-Policy")!! shouldContain "frame-ancestors 'self'"
        silent.headers.getFirst("X-Frame-Options") shouldBe "SAMEORIGIN"

        val config = client.get().uri("/api/v1/ui-config").retrieve().body(Map::class.java)!!
        config["authMode"] shouldBe "OIDC"
        (config["oidc"] as Map<*, *>)["clientId"] shouldBe "etrm-web"
        (config["oidc"] as Map<*, *>)["authority"] shouldBe "https://login.example.test/realms/etrm"
        config["personas"] shouldBe null
    }

    @Test
    fun `the api needs a bearer token and ignores dev headers`() {
        client.get().uri("/api/v1/me").retrieve().toBodilessEntity().statusCode.value() shouldBe 401
        client.get().uri("/api/v1/me").header("X-Actor", "mallory").header("X-Roles", "ADMIN")
            .retrieve().toBodilessEntity().statusCode.value() shouldBe 401
        client.get().uri("/api/v1/me").header("Authorization", "Bearer forged")
            .retrieve().toBodilessEntity().statusCode.value() shouldBe 401
        client.post().uri("/inbox").retrieve().toBodilessEntity().statusCode.value() shouldBe 401
    }

    @Test
    fun `the API description needs a token outside development`() {
        client.get().uri("/v3/api-docs").retrieve().toBodilessEntity().statusCode.value() shouldBe 401
        client.get().uri("/swagger-ui/index.html").retrieve().toBodilessEntity().statusCode.value() shouldBe 401
        client.get().uri("/v3/api-docs").header("Authorization", "Bearer trader-token").retrieve().toBodilessEntity()
            .statusCode.value() shouldBe 200
    }

    @Test
    fun `me reads roles, name and entity scope from the token`() {
        val me = client.get().uri("/api/v1/me").header("Authorization", "Bearer trader-token").retrieve().body(Map::class.java)!!
        me["userId"] shouldBe "u-trader"
        me["displayName"] shouldBe "Tia Trader"
        me["roles"] shouldBe listOf("TRADER")
        me["legalEntityIds"] shouldBe listOf(ENTITY)
        me["allEntities"] shouldBe false
        me["authMode"] shouldBe "OIDC"
    }

    @Test
    fun `the actuator on the application port is for administrators only`() {
        client.get().uri("/actuator/health").retrieve().toBodilessEntity().statusCode.value() shouldBe 200
        client.get().uri("/actuator/sbom").retrieve().toBodilessEntity().statusCode.value() shouldBe 401
        client.get().uri("/actuator/sbom").header("Authorization", "Bearer trader-token").retrieve().toBodilessEntity()
            .statusCode.value() shouldBe 403
    }

    @Test
    fun `an unknown api path is a 404 problem once authenticated`() {
        val response = client.get().uri("/api/v1/nope").header("Authorization", "Bearer trader-token").retrieve().toEntity(Map::class.java)
        response.statusCode.value() shouldBe 404
        response.body!!["type"] shouldBe "urn:etrm:problem:not-found"
    }
}
