package com.pianonurmi.etrm.product.domain

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.product.api.criteria.Criterion
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Versioned JSON envelope `{"v": n, "criterion": {...}}`. Readers upcast older versions through [CriteriaUpcaster]s
 * so criteria stored on historical trades never have to be rewritten and always evaluate identically.
 */
object CriteriaCodec {
    const val CURRENT_VERSION: Int = 1

    private val upcasters: Map<Int, CriteriaUpcaster> = emptyMap()

    fun toJson(criterion: Criterion): String {
        val node = Json.mapper.createObjectNode()
        node.put("v", CURRENT_VERSION)
        node.set("criterion", Json.mapper.valueToTree<JsonNode>(criterion))
        return Json.mapper.writeValueAsString(node)
    }

    fun parse(json: String): Criterion {
        val root = runCatching { Json.mapper.readTree(json) }.getOrElse { throw ValidationException("Criteria JSON is malformed: ${it.message}") }
        if (root !is ObjectNode || !root.has("v") || !root.has("criterion")) {
            throw ValidationException("Criteria JSON must be an envelope with 'v' and 'criterion'")
        }
        var version = root.get("v").asInt()
        var body: JsonNode = root.get("criterion")
        if (version > CURRENT_VERSION) throw ValidationException("Criteria schema version $version is newer than supported $CURRENT_VERSION")
        while (version < CURRENT_VERSION) {
            val upcaster = upcasters[version] ?: throw ValidationException("No upcaster from criteria schema version $version")
            body = upcaster.upcast(body)
            version++
        }
        return runCatching { Json.mapper.treeToValue(body, Criterion::class.java) }
            .getOrElse { throw ValidationException("Criteria JSON is invalid: ${it.message}") }
    }

    /** Canonical (recursively key-sorted) form used for hashing so equivalent documents dedupe. */
    fun canonical(criterion: Criterion): String = Json.mapper.writeValueAsString(sortKeys(Json.mapper.valueToTree(criterion)))

    private fun sortKeys(node: JsonNode): JsonNode = when {
        node.isObject -> {
            val sorted = Json.mapper.createObjectNode()
            node.properties().sortedBy { it.key }.forEach { (key, value) -> sorted.set(key, sortKeys(value)) }
            sorted
        }
        node.isArray -> {
            val array = Json.mapper.createArrayNode()
            node.forEach { array.add(sortKeys(it)) }
            array
        }
        else -> node
    }
}

/** Transforms a criterion body from schema version `n` to `n + 1`. */
fun interface CriteriaUpcaster {
    fun upcast(body: JsonNode): JsonNode
}
