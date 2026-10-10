package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/**
 * The committed OpenAPI document (`apps/api/openapi/etrm-api.json`) is what the web client is generated from
 * (ADR 0027). This test fails when the API changed without the snapshot; `./gradlew :apps:api:updateOpenApiSnapshot`
 * rewrites it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiSnapshotIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)

        private val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

        /** Keys sorted at every level so the snapshot does not depend on reflection order. */
        fun normalise(json: String): String = mapper.writeValueAsString(sorted(mapper.readValue(json, Any::class.java))) + "\n"

        private fun sorted(node: Any?): Any? = when (node) {
            is Map<*, *> -> TreeMap(node.entries.associate { (key, value) -> key.toString() to sorted(value) })
            is List<*> -> node.map { sorted(it) }
            else -> node
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private fun document(): String = RestClient.create("http://localhost:$port").get().uri("/v3/api-docs").retrieve().body(String::class.java)!!

    @Test
    fun `operation ids are unique and named after controller and method`() {
        val paths = mapper.readValue(document(), Map::class.java)["paths"] as Map<*, *>
        val ids = paths.values.flatMap { (it as Map<*, *>).values }.map { (it as Map<*, *>)["operationId"].toString() }
        ids.groupBy { it }.filterValues { it.size > 1 }.keys.shouldBeEmpty()
        ids.filter { !it.matches(Regex("^[a-z][A-Za-z]*_[a-z][A-Za-z0-9]*$")) || it.matches(Regex(".*_\\d+$")) }.shouldBeEmpty()
    }

    @Test
    fun `committed snapshot matches the running API`() {
        val actual = normalise(document())
        val snapshot = Path.of(System.getProperty("etrm.openapi.snapshot") ?: "openapi/etrm-api.json")
        if (System.getProperty("etrm.openapi.write") == "true") {
            Files.createDirectories(snapshot.parent)
            Files.writeString(snapshot, actual)
            return
        }
        check(Files.exists(snapshot)) { "Missing $snapshot; run ./gradlew :apps:api:updateOpenApiSnapshot" }
        val expected = Files.readString(snapshot)
        if (expected != actual) {
            val diff = expected.lines().zip(actual.lines()).indexOfFirst { (a, b) -> a != b }
            error(
                "OpenAPI snapshot is out of date (first difference at line ${diff + 1}); " +
                    "run ./gradlew :apps:api:updateOpenApiSnapshot and commit apps/api/openapi/etrm-api.json",
            )
        }
        expected shouldBe actual
    }
}
