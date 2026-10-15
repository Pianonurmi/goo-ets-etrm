package com.pianonurmi.etrm.platform.db

/**
 * The order in which the modules' schemas migrate, for the application and for tests alike. Each module owns
 * `db/migration/<module>` and schema `etrm_<module>`, and no foreign key crosses schemas, so the order only has to put a
 * module after the modules it reads at migration time; one list keeps the application and the tests the same.
 */
object ModuleOrder {
    /** The business modules, in migration order (`platform` first). A new module is added here. */
    val modules: List<String> = listOf(
        "platform",
        "document",
        "referencedata",
        "taxonomy",
        "product",
        "marketdata",
        "trade",
        "inventory",
        "allocation",
        "registry",
        "etscompliance",
        "position",
        "settlement",
        "clearing",
        "valuation",
        "ledger",
        "risk",
        "reporting",
        "workflow",
        "audit",
    )

    /** Schemas outside the business modules, migrated after them when present: `demo` (the seeder's run log). */
    val additional: List<String> = listOf("demo")

    /** [names] in migration order; an unknown name is refused, so a typo cannot skip a schema silently. */
    fun sorted(names: Collection<String>): List<String> {
        val order = modules + additional
        val unknown = names.filterNot { it in order }
        require(unknown.isEmpty()) { "Unknown module schema(s) $unknown; known: $order" }
        return names.distinct().sortedBy(order::indexOf)
    }
}
