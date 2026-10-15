package com.pianonurmi.etrm.platform.privacy

import com.pianonurmi.etrm.platform.json.Json
import tools.jackson.databind.JsonNode

/**
 * Personal data that must not enter a record that cannot forget it: a hash-chained or append-only row (the lot
 * journal, the audit trail, version and event history). Today that is the name and identifier of a cancellation's
 * beneficiary (review finding S7). Such records keep the `beneficiaryRef`; the inventory module's beneficiary register
 * holds the name and identifier until they are erased. The database applies the same rule to the rows its triggers
 * copy (`without_beneficiary`).
 */
object PersonalData {
    val BENEFICIARY_KEYS = setOf("beneficiaryName", "beneficiaryId")

    /** [value] as JSON, without the beneficiary's name and identifier at any depth. */
    fun redact(value: Any): JsonNode = strip(Json.mapper.valueToTree(value))

    private fun strip(node: JsonNode): JsonNode = when {
        node.isObject -> Json.mapper.createObjectNode().also { copy ->
            node.properties().forEach { (key, value) -> if (key !in BENEFICIARY_KEYS) copy.set(key, strip(value)) }
        }
        node.isArray -> Json.mapper.createArrayNode().also { copy -> node.forEach { copy.add(strip(it)) } }
        else -> node
    }
}
