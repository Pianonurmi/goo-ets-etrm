package com.pianonurmi.etrm.config

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.Schema
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Marks Kotlin properties as `required` so generated clients see `id: string` rather than `id?: string`:
 * constructor properties that are neither nullable nor defaulted, and non-null computed properties (they are
 * always serialised and never sent).
 */
class KotlinRequiredProperties : ModelConverter {
    override fun resolve(type: AnnotatedType, context: ModelConverterContext, chain: Iterator<ModelConverter>): Schema<*>? {
        val schema = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val target = schema?.`$ref`?.let { context.definedModels[it.substringAfterLast('/')] } ?: schema
        val cls = rawClass(type.type)?.takeIf { !it.isEnum && it.isAnnotationPresent(Metadata::class.java) }
        if (target == null || cls == null) return schema
        val holder = target.takeIf { it.properties != null } ?: target.allOf?.lastOrNull { it.properties != null } ?: return schema
        val required = runCatching { requiredProperties(cls) }.getOrDefault(emptyList()).filter { holder.properties.containsKey(it) }
        if (required.isNotEmpty()) holder.required = (holder.required.orEmpty() + required).distinct()
        return schema
    }

    private fun requiredProperties(cls: Class<*>): List<String> {
        val parameters = cls.kotlin.primaryConstructor?.parameters.orEmpty()
        val fromConstructor = parameters.filter { !it.isOptional && !it.type.isMarkedNullable }.mapNotNull { it.name }
        val names = parameters.mapNotNull { it.name }.toSet()
        val computed = cls.kotlin.memberProperties.filter { it.name !in names && !it.returnType.isMarkedNullable }.map { it.name }
        return fromConstructor + computed
    }

    private fun rawClass(type: Type?): Class<*>? = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        is com.fasterxml.jackson.databind.JavaType -> type.rawClass
        else -> null
    }
}

/**
 * Rewrites Jackson-polymorphic types (`@JsonTypeInfo` + `@JsonSubTypes`, e.g. `Criterion`) into a `oneOf` with a
 * discriminator mapping, and gives every subtype a literal `type` property, so clients get a discriminated union.
 */
object PolymorphicSchemas {
    fun roots(basePackage: String): List<Class<*>> {
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean = true
        }
        scanner.addIncludeFilter(AnnotationTypeFilter(JsonTypeInfo::class.java, false, false))
        return scanner.findCandidateComponents(basePackage)
            .map { Class.forName(it.beanClassName) }
            .filter { it.getDeclaredAnnotation(JsonTypeInfo::class.java) != null && it.getDeclaredAnnotation(JsonSubTypes::class.java) != null }
            .sortedBy { it.name }
    }

    fun apply(api: OpenAPI, roots: List<Class<*>>) {
        val schemas = api.components?.schemas ?: return
        roots.forEach { root ->
            val property = root.getDeclaredAnnotation(JsonTypeInfo::class.java).property
            val subtypes = root.getDeclaredAnnotation(JsonSubTypes::class.java).value
            val base = schemas[root.simpleName] ?: return@forEach
            val mapping = linkedMapOf<String, String>()
            subtypes.forEach { subtype ->
                val name = subtype.value.java.simpleName
                val schema = schemas[name] ?: return@forEach
                flatten(schema, "#/components/schemas/${root.simpleName}")
                schema.addProperty(property, Schema<String>().apply { addType("string") }.also { it.setEnum(listOf(subtype.name)) })
                schema.required = (listOf(property) + schema.required.orEmpty()).distinct()
                mapping[subtype.name] = "#/components/schemas/$name"
            }
            base.properties = null
            base.required = null
            base.types = null
            base.type = null
            base.oneOf = mapping.values.map { Schema<Any>().`$ref`(it) }
            base.discriminator = Discriminator().propertyName(property).mapping(mapping)
        }
    }

    /** `allOf: [$ref Base, {...}]` becomes the inline object alone, so the union does not refer back to itself. */
    private fun flatten(schema: Schema<*>, baseRef: String) {
        val parts = schema.allOf ?: run {
            if (schema.properties == null) schema.properties = linkedMapOf()
            schema.addType("object")
            return
        }
        schema.allOf = null
        schema.addType("object")
        parts.filter { it.`$ref` != baseRef }.forEach { part ->
            part.properties?.forEach { (key, value) -> schema.addProperty(key, value) }
            part.required?.let { schema.required = (schema.required.orEmpty() + it).distinct() }
        }
        if (schema.properties == null) schema.properties = linkedMapOf()
    }
}
