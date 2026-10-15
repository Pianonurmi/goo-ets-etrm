package com.pianonurmi.etrm

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the module boundaries declared with `@ApplicationModule`: no cycles, no access to another module's
 * internals, only declared dependencies. Also renders the C4-style module diagrams into `build/spring-modulith-docs`,
 * and keeps the module diagram of arc42 section 5 equal to the modules as built (review finding C4).
 */
class ModularityTests {
    private val modules = ApplicationModules.of(EtrmApplication::class.java)

    @Test
    fun `module structure is valid`() {
        modules.forEach { println(it) }
        modules.verify()
    }

    @Test
    fun `module documentation renders`() {
        Documenter(modules).writeDocumentation()
    }

    @Test
    fun `the architecture document shows the module dependencies as built`() {
        val document = Path.of("../../docs/arc42/05-building-block-view.md")
        val text = Files.readString(document)
        val documented = text.substringAfter(BEGIN, "").substringBefore(END, "").trim()
        val built = moduleDiagram()
        withClue("Replace the block between $BEGIN and $END in $document with:\n$built\n") { documented shouldBe built }
    }

    /**
     * One Mermaid line per module and the modules it uses directly; the shared kernel is left out of the targets. The
     * packages of `apps/api` (configuration, the web layer, jobs across modules) are modules too and are drawn apart.
     */
    private fun moduleDiagram(): String {
        val lines = modules.filter { it.identifier.toString() != PLATFORM }.map { module ->
            val name = module.identifier.toString()
            val targets = module.getDirectDependencies(modules).uniqueModules()
                .map { it.identifier.toString() }
                .filter { it != PLATFORM && it != name }
                .sorted()
                .toList()
                .ifEmpty { listOf(PLATFORM) }
            name to "$name --> ${targets.joinToString(" & ")}"
        }
        val (application, domain) = lines.partition { it.first in APPLICATION }
        return (
            listOf("```mermaid", "flowchart TB", "  $PLATFORM[(platform<br/>shared kernel)]") +
                domain.map { "  ${it.second}" }.sorted() +
                listOf("  subgraph app[apps/api]") + application.map { "    ${it.second}" }.sorted() + listOf("  end", "```")
        ).joinToString("\n")
    }

    private companion object {
        const val PLATFORM = "platform"
        val APPLICATION = setOf("config", "database", "demo", "events", "home", "integrity", "jobs", "showcase", "web")
        const val BEGIN = "<!-- module-diagram:begin (generated, checked by ModularityTests) -->"
        const val END = "<!-- module-diagram:end -->"
    }
}
