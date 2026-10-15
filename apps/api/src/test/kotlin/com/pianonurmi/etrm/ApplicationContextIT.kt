package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

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

    /** The application's clock (ADR 0050): dates sent to it are its dates. */
    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var lockProvider: LockProvider

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

    @Test
    fun `scheduled jobs take cluster-wide locks`() {
        val config = LockConfiguration(clock.instant(), "context-it-lock", Duration.ofMinutes(1), Duration.ZERO)
        val first = lockProvider.lock(config)
        first.isPresent shouldBe true
        lockProvider.lock(config).isPresent shouldBe false
        first.get().unlock()
        lockProvider.lock(config).also { it.isPresent shouldBe true }.get().unlock()
    }
}
