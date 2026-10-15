package com.pianonurmi.etrm.taxonomy.adapter.rest

import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.taxonomy.api.CreateTaxonomyNode
import com.pianonurmi.etrm.taxonomy.api.TaxonomyCommand
import com.pianonurmi.etrm.taxonomy.api.TaxonomyNodeView
import com.pianonurmi.etrm.taxonomy.api.TaxonomyQuery
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import com.pianonurmi.etrm.taxonomy.api.UpdateTaxonomyNode
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/taxonomy")
@Tag(name = "Taxonomy", description = "Hierarchical code sets: energy sources, technologies, labels, regions, support schemes")
class TaxonomyController(
    private val query: TaxonomyQuery,
    private val command: TaxonomyCommand,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) {
    /** Code sets drive eligibility and criteria everywhere: middle office maintains them. */
    private fun requireMaintainer() = actorProvider.current().requireRole(Role.MIDDLE_OFFICE)

    /** Children of [parent] (roots without it); `all=true` returns every node of the scheme in path order, for pickers. */
    @GetMapping("/{scheme}")
    fun list(
        @PathVariable scheme: TaxonomyScheme,
        @RequestParam(required = false) parent: String?,
        @RequestParam(defaultValue = "false") all: Boolean,
    ): List<TaxonomyNodeView> =
        if (all) query.children(scheme, null).flatMap { root -> query.subtree(scheme, root.code) } else query.children(scheme, parent)

    @GetMapping("/{scheme}/{code}")
    fun get(
        @PathVariable scheme: TaxonomyScheme,
        @PathVariable code: String,
    ): TaxonomyNodeView = query.require(scheme, code)

    @GetMapping("/{scheme}/{code}/subtree")
    fun subtree(
        @PathVariable scheme: TaxonomyScheme,
        @PathVariable code: String,
    ): List<TaxonomyNodeView> = query.subtree(scheme, code)

    @GetMapping("/REGION/{code}/countries")
    fun regionCountries(
        @PathVariable code: String,
    ): Set<String> = query.regionCountries(code)

    @PostMapping("/{scheme}")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.MIDDLE_OFFICE)
    fun create(
        @PathVariable scheme: TaxonomyScheme,
        @Valid @RequestBody body: CreateNodeRequest,
    ): TaxonomyNodeView = requireMaintainer().let {
        command.create(
            CreateTaxonomyNode(
                scheme = scheme,
                code = body.code,
                parentCode = body.parentCode,
                name = body.name,
                description = body.description,
                externalCodes = body.externalCodes,
                validFrom = body.validFrom ?: LocalDate.now(clock),
                validTo = body.validTo,
            ),
        )
    }

    @PatchMapping("/{scheme}/{code}")
    @AllowedRoles(Role.MIDDLE_OFFICE)
    fun update(
        @PathVariable scheme: TaxonomyScheme,
        @PathVariable code: String,
        @Valid @RequestBody body: UpdateNodeRequest,
    ): TaxonomyNodeView = requireMaintainer().let {
        command.update(scheme, code, UpdateTaxonomyNode(body.name, body.description, body.externalCodes, body.validTo, body.expectedVersion))
    }

    @PutMapping("/REGION/{code}/countries")
    @AllowedRoles(Role.MIDDLE_OFFICE)
    fun setRegionCountries(
        @PathVariable code: String,
        @RequestBody countries: Set<String>,
    ): Set<String> = requireMaintainer().let { command.setRegionMembers(code, countries) }
}

data class CreateNodeRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Z0-9_]{1,60}$") val code: String,
    val parentCode: String? = null,
    @field:NotBlank val name: String,
    val description: String? = null,
    val externalCodes: Map<String, String> = emptyMap(),
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
)

data class UpdateNodeRequest(
    val name: String? = null,
    val description: String? = null,
    val externalCodes: Map<String, String>? = null,
    val validTo: LocalDate? = null,
    val expectedVersion: Int,
)
