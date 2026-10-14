package com.pianonurmi.etrm

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One source of time (ADR 0050): production code reads the injected `Clock`, never `now()` without one. detekt runs
 * without type resolution here, so its `ForbiddenMethodCall` cannot see the calls; this scan of the sources does.
 *
 * Remaining exceptions, removed in increment 15 (C7): `updated_at`-style stamps in the jOOQ adapters, the default of
 * `EventMetadata.occurredAt` and `AsOf.now`.
 */
class TimeSourceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val call = Regex("""\b(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year)\.now\(\s*\)""")
    private val exceptions = listOf(
        Regex("""/adapter/jooq/.*\.kt$""") to "Instant",
        Regex("""/platform/events/DomainEvent\.kt$""") to "Instant",
        Regex("""/platform/bitemporal/Bitemporal\.kt$""") to "Instant",
    )

    @Test
    fun `production code takes the time from the injected clock`() {
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != "node_modules" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/kotlin/") }
            .toList()
        withClue("the scan found the sources") { (sources.size > 100) shouldBe true }
        val offences = sources.flatMap { file ->
            val path = file.invariantSeparatorsPath
            file.readLines().mapIndexedNotNull { index, line ->
                val type = call.find(line)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                val allowed = exceptions.any { (pattern, allowedType) -> pattern.containsMatchIn(path) && allowedType == type }
                if (allowed) null else "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
            }
        }
        offences.shouldBeEmpty()
    }
}
