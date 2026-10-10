package com.pianonurmi.etrm

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Verifies the module boundaries declared with `@ApplicationModule`: no cycles, no access to another module's
 * internals, only declared dependencies. Also renders the C4-style module diagrams into `build/spring-modulith-docs`.
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
}
