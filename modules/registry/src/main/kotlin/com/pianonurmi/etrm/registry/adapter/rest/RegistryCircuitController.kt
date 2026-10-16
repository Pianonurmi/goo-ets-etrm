package com.pianonurmi.etrm.registry.adapter.rest

import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.registry.api.RegistryCircuitCommand
import com.pianonurmi.etrm.registry.api.RegistryCircuitQuery
import com.pianonurmi.etrm.registry.api.RegistryCircuitView
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Registries whose dispatch paused after transport failures in a row, and the operator's resume. */
@RestController
@RequestMapping("/api/v1/registry/circuits")
@Tag(name = "Registry", description = "Transfer routes, registry instructions and statement import")
class RegistryCircuitController(
    private val query: RegistryCircuitQuery,
    private val command: RegistryCircuitCommand,
    private val access: RegistryAccess,
) {
    @GetMapping
    fun circuits(): List<RegistryCircuitView> = access.reader().let { query.circuits() }

    /** Dispatches a paused registry's instructions again; they belong to every legal entity, so it needs all of them. */
    @PostMapping("/{registryCode}/resume")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun resume(
        @PathVariable registryCode: String,
    ): RegistryCircuitView = access.registryWideOperator("Resuming a registry's dispatch").let { command.resume(registryCode) }
}
