package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.test.TestDatabase
import org.springframework.test.context.DynamicPropertyRegistry

/** Shared wiring for full-context tests: the external or Testcontainers PostgreSQL, reset so the app migrates itself. */
object TestWiring {
    private val modules = listOf(
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
        "position",
        "settlement",
        "ledger",
        "workflow",
        "audit",
    )

    @Volatile
    private var reset = false

    fun register(registry: DynamicPropertyRegistry) {
        if (!reset) {
            TestDatabase.resetSchemas(*modules.toTypedArray())
            reset = true
        }
        TestDatabase.springProperties().forEach { (key, value) -> registry.add(key) { value } }
    }
}
