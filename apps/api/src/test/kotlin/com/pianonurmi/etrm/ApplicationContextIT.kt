package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationContextIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `application starts, migrates every module schema and serves the API`() {
        val client = RestClient.create("http://localhost:$port")
        val registries = client.get().uri("/api/v1/reference/registries").retrieve().body(List::class.java)!!
        registries.map { (it as Map<*, *>)["code"] } shouldContainAll listOf("FINGRID", "UNION_REGISTRY", "SIMULATOR")
        val buckets = client.get().uri("/api/v1/products/buckets").retrieve().body(List::class.java)!!
        (buckets.size >= 17) shouldBe true
        val health = client.get().uri("/actuator/health").retrieve().body(Map::class.java)!!
        health["status"] shouldBe "UP"
    }
}
