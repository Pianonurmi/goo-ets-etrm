package com.pianonurmi.etrm

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.matchers.maps.shouldBeEmpty
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * OpenAPI schemas are named after the simple class name, so two different request or view classes with the same
 * name would silently share one schema in the generated web client. Classes named like a schema of the committed
 * OpenAPI document must all have the same shape.
 */
class SchemaNamesTest {
    private val schemaNames: Set<String> = run {
        val snapshot = Path.of(System.getProperty("etrm.openapi.snapshot") ?: "openapi/etrm-api.json")
        val document = JsonMapper.builder().build().readValue(Files.readString(snapshot), Map::class.java)
        ((document["components"] as Map<*, *>)["schemas"] as Map<*, *>).keys.map { it.toString() }.toSet()
    }

    @Test
    fun `classes behind one schema name have the same fields`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.pianonurmi.etrm")
            .filter { it.packageName.contains(".api") || it.packageName.endsWith(".adapter.rest") }
            .filter { !it.simpleName.endsWith("Controller") && it.simpleName.isNotEmpty() && !it.isAnonymousClass }
            .filter { !it.isInterface || it.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonTypeInfo") }
            .filter { !it.isEnum && it.simpleName in schemaNames }
            // A nested class only reaches the document through its exposed outer type (Criterion.Not, not BoundCriterion.Not).
            .filter { type -> type.enclosingClass.map { it.simpleName in schemaNames }.orElse(true) }
        val conflicting = classes
            .groupBy { it.simpleName }
            .filterValues { group -> group.map(::shape).distinct().size > 1 }
            .mapValues { (_, group) -> group.map { it.name } }
        conflicting.shouldBeEmpty()
    }

    private fun shape(type: JavaClass): String =
        type.fields.filter { JavaModifier.STATIC !in it.modifiers }.map { it.name + ":" + it.rawType.name }.sorted().toString()
}
