package com.pianonurmi.etrm

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One source of time (ADR 0050): code reads the injected `Clock`, never `now()` without one. detekt runs without type
 * resolution here, so its `ForbiddenMethodCall` cannot see the calls; this scan of the sources does.
 *
 * Production code has no exception: domain events and `AsOf.now` read `ApplicationClock`, and row stamps the
 * application never reads (`updated_at` and the like) are the database's transaction time. Tests take their time from
 * a `TestClock`, a fixed instant, the application's `Clock` bean or `TestDatabase.transactionTime()`; the only
 * exceptions are tokens, which Spring Security validates against the system clock.
 */
class TimeSourceTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val call = Regex("""\b(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year)\.now\(\s*\)""")
    private val testExceptions = listOf(
        Regex("""/TokenValidationIT\.kt$"""),
        Regex("""/SecuredChainIT\.kt$"""),
    )

    private fun sources(dir: String) = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != "node_modules" && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/$dir/kotlin/") }
        .toList()

    private fun offences(files: List<File>, exceptions: List<Regex> = emptyList()) = files
        .filter { file -> exceptions.none { it.containsMatchIn(file.invariantSeparatorsPath) } }
        .flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (call.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" else null
            }
        }

    @Test
    fun `production code takes the time from the injected clock`() {
        val sources = sources("main")
        withClue("the scan found the sources") { (sources.size > 100) shouldBe true }
        offences(sources).shouldBeEmpty()
    }

    @Test
    fun `tests take the time from a test clock, a fixed instant or the application`() {
        val sources = sources("test") + sources("testFixtures")
        withClue("the scan found the sources") { (sources.size > 100) shouldBe true }
        offences(sources, testExceptions).shouldBeEmpty()
    }
}
