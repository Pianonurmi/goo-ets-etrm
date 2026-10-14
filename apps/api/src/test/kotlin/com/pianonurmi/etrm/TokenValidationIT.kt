package com.pianonurmi.etrm

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

/**
 * The production chain with Spring Boot's own JWT decoder and the validation `application.yml` configures: the
 * signature against the identity provider's key set (served here by a stub), the expiry, the issuer and the audience
 * are all checked, and unsigned tokens are refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("secured-test")
class TokenValidationIT {
    companion object {
        private const val ISSUER = "https://login.example.test/realms/etrm"
        private val trusted: KeyPair = rsa()
        private val stranger: KeyPair = rsa()

        private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        /** The identity provider's key set endpoint, publishing the trusted key only. */
        private val keySet: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            val jwks = JWKSet(RSAKey.Builder(trusted.public as RSAPublicKey).keyID("trusted").build()).toString().toByteArray()
            createContext("/jwks") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, jwks.size.toLong())
                exchange.responseBody.use { it.write(jwks) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            TestWiring.register(registry)
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri") { "http://127.0.0.1:${keySet.address.port}/jwks" }
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { ISSUER }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()

    private fun claims(
        audience: String = "etrm-api",
        issuer: String = ISSUER,
        expires: Instant = Instant.now().plusSeconds(300),
    ): JWTClaimsSet = JWTClaimsSet.Builder()
        .subject("u-trader")
        .issuer(issuer)
        .audience(audience)
        .issueTime(Date.from(Instant.now().minusSeconds(60)))
        .expirationTime(Date.from(expires))
        .claim("roles", listOf("TRADER"))
        .claim("legal_entities", listOf("*"))
        .build()

    private fun signed(claims: JWTClaimsSet, keys: KeyPair = trusted): String =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("trusted").build(), claims).apply {
            sign(RSASSASigner(keys.private as RSAPrivateKey))
        }.serialize()

    private fun me(token: String): Int = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/me")).header("Authorization", "Bearer $token").build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    @Test
    fun `a token signed by the trusted key, for this API, from the issuer, is accepted`() {
        me(signed(claims())) shouldBe 200
    }

    @Test
    fun `tokens for another audience, from another issuer, expired, unsigned or signed by another key are refused`() {
        me(signed(claims(audience = "another-app"))) shouldBe 401
        me(signed(claims(issuer = "https://evil.example.test/realms/etrm"))) shouldBe 401
        me(signed(claims(expires = Instant.now().minusSeconds(3600)))) shouldBe 401
        me(signed(claims(), stranger)) shouldBe 401
        me(PlainJWT(claims()).serialize()) shouldBe 401
    }
}
