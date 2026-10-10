package com.pianonurmi.etrm.taxonomy.domain

import com.pianonurmi.etrm.platform.bitemporal.ValidPeriod
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.taxonomy.api.TaxonomyNodeView
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import java.time.LocalDate
import java.util.UUID

/** Codes double as ltree labels, so they are restricted to upper-case alphanumerics and underscore. */
@JvmInline
value class TaxonomyCode(val value: String) {
    init {
        if (!PATTERN.matches(value)) {
            throw ValidationException("Taxonomy code '$value' must match ${PATTERN.pattern}")
        }
    }

    override fun toString(): String = value

    companion object {
        val PATTERN = Regex("^[A-Z0-9_]{1,60}$")
    }
}

/** ltree path of a node: parent path plus own code. */
@JvmInline
value class TaxonomyPath(val value: String) {
    fun child(code: TaxonomyCode): TaxonomyPath = TaxonomyPath("$value.${code.value}")

    val depth: Int get() = value.count { it == '.' }

    fun isDescendantOrSelf(ancestor: TaxonomyPath): Boolean = value == ancestor.value || value.startsWith("${ancestor.value}.")

    override fun toString(): String = value

    companion object {
        fun root(code: TaxonomyCode): TaxonomyPath = TaxonomyPath(code.value)
    }
}

class TaxonomyNode(
    val id: UUID,
    val scheme: TaxonomyScheme,
    val code: TaxonomyCode,
    val parentId: UUID?,
    val parentCode: TaxonomyCode?,
    val path: TaxonomyPath,
    var name: String,
    var description: String?,
    var externalCodes: Map<String, String>,
    var validPeriod: ValidPeriod,
    var version: Int,
) {
    val depth: Int get() = path.depth

    fun rename(newName: String) {
        if (newName.isBlank()) throw ValidationException("Name must not be blank")
        name = newName
    }

    fun endValidity(validTo: LocalDate) {
        validPeriod = validPeriod.endingAt(validTo)
    }

    fun toView(): TaxonomyNodeView = TaxonomyNodeView(
        id = id,
        scheme = scheme,
        code = code.value,
        parentCode = parentCode?.value,
        path = path.value,
        depth = depth,
        name = name,
        description = description,
        externalCodes = externalCodes,
        validPeriod = validPeriod,
        version = version,
    )

    companion object {
        fun root(
            scheme: TaxonomyScheme,
            code: TaxonomyCode,
            name: String,
            description: String?,
            externalCodes: Map<String, String>,
            validPeriod: ValidPeriod,
        ) = TaxonomyNode(Ids.newId(), scheme, code, null, null, TaxonomyPath.root(code), name, description, externalCodes, validPeriod, 1)

        fun childOf(
            parent: TaxonomyNode,
            code: TaxonomyCode,
            name: String,
            description: String?,
            externalCodes: Map<String, String>,
            validPeriod: ValidPeriod,
        ): TaxonomyNode {
            if (!parent.validPeriod.isValidOn(validPeriod.validFrom)) {
                throw ValidationException("Parent ${parent.code} is not valid on ${validPeriod.validFrom}")
            }
            return TaxonomyNode(
                Ids.newId(),
                parent.scheme,
                code,
                parent.id,
                parent.code,
                parent.path.child(code),
                name,
                description,
                externalCodes,
                validPeriod,
                1,
            )
        }
    }
}

/** Persistence port. */
interface TaxonomyRepository {
    fun find(scheme: TaxonomyScheme, code: TaxonomyCode): TaxonomyNode?

    fun findByExternalCode(scheme: TaxonomyScheme, system: String, externalCode: String): TaxonomyNode?

    fun children(scheme: TaxonomyScheme, parentCode: TaxonomyCode?): List<TaxonomyNode>

    fun subtree(scheme: TaxonomyScheme, ancestorPath: TaxonomyPath): List<TaxonomyNode>

    fun all(scheme: TaxonomyScheme): List<TaxonomyNode>

    fun insert(node: TaxonomyNode)

    /** Optimistic update; returns false when the stored version differs. */
    fun update(node: TaxonomyNode, expectedVersion: Int): Boolean

    fun regionMembers(regionId: UUID): Set<String>

    fun allRegionMembers(): Map<UUID, Set<String>>

    fun replaceRegionMembers(regionId: UUID, countries: Set<String>)
}
