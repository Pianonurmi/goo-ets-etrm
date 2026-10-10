package com.pianonurmi.etrm.taxonomy.api

import com.pianonurmi.etrm.platform.bitemporal.ValidPeriod
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.time.LocalDate
import java.util.UUID

/** Code sets managed by the taxonomy module. */
enum class TaxonomyScheme {
    ENERGY_SOURCE,
    TECHNOLOGY,
    LABEL,
    REGION,
    SUPPORT_SCHEME,
}

data class TaxonomyNodeView(
    val id: UUID,
    val scheme: TaxonomyScheme,
    val code: String,
    val parentCode: String?,
    /** ltree path, e.g. `RENEWABLE.WIND.WIND_ONSHORE`. */
    val path: String,
    val depth: Int,
    val name: String,
    val description: String?,
    val externalCodes: Map<String, String>,
    val validPeriod: ValidPeriod,
    val version: Int,
)

/** Read side used by product criteria, inventory, allocation and reporting. */
interface TaxonomyQuery {
    fun node(scheme: TaxonomyScheme, code: String): TaxonomyNodeView?

    fun require(scheme: TaxonomyScheme, code: String): TaxonomyNodeView

    fun children(scheme: TaxonomyScheme, parentCode: String?): List<TaxonomyNodeView>

    /** The node itself plus all descendants, in path order. */
    fun subtree(scheme: TaxonomyScheme, ancestorCode: String): List<TaxonomyNodeView>

    fun isDescendantOrSelf(scheme: TaxonomyScheme, code: String, ancestorCode: String): Boolean

    fun regionCountries(regionCode: String): Set<String>

    /** Snapshot of a whole scheme for in-memory evaluation (allocation runs, criteria binding). */
    fun snapshot(scheme: TaxonomyScheme): TaxonomySnapshot

    fun byExternalCode(scheme: TaxonomyScheme, system: String, externalCode: String): TaxonomyNodeView?
}

/** Immutable in-memory view of one scheme; `pathOf` and `isDescendantOrSelf` are O(1)/O(depth). */
class TaxonomySnapshot(val scheme: TaxonomyScheme, nodes: Collection<TaxonomyNodeView>, regionMembers: Map<String, Set<String>> = emptyMap()) {
    private val byCode: Map<String, TaxonomyNodeView> = nodes.associateBy { it.code }
    private val regions: Map<String, Set<String>> = regionMembers

    val codes: Set<String> get() = byCode.keys

    fun node(code: String): TaxonomyNodeView? = byCode[code]

    fun pathOf(code: String): String? = byCode[code]?.path

    fun isDescendantOrSelf(code: String, ancestorCode: String): Boolean {
        val path = pathOf(code) ?: return false
        val ancestorPath = pathOf(ancestorCode) ?: return false
        return path == ancestorPath || path.startsWith("$ancestorPath.")
    }

    fun descendantsOrSelf(ancestorCode: String): Set<String> {
        val ancestorPath = pathOf(ancestorCode) ?: return emptySet()
        return byCode.values.filter { it.path == ancestorPath || it.path.startsWith("$ancestorPath.") }.map { it.code }.toSet()
    }

    fun regionCountries(regionCode: String): Set<String> = regions[regionCode] ?: emptySet()
}

data class CreateTaxonomyNode(
    val scheme: TaxonomyScheme,
    val code: String,
    val parentCode: String?,
    val name: String,
    val description: String? = null,
    val externalCodes: Map<String, String> = emptyMap(),
    val validFrom: LocalDate,
    val validTo: LocalDate? = null,
)

data class UpdateTaxonomyNode(
    val name: String? = null,
    val description: String? = null,
    val externalCodes: Map<String, String>? = null,
    val validTo: LocalDate? = null,
    val expectedVersion: Int,
)

/** Write side; administrative, four-eyes controlled through the workflow module in later increments. */
interface TaxonomyCommand {
    fun create(command: CreateTaxonomyNode): TaxonomyNodeView

    fun update(scheme: TaxonomyScheme, code: String, command: UpdateTaxonomyNode): TaxonomyNodeView

    fun setRegionMembers(regionCode: String, countries: Set<String>): Set<String>
}

/** Published whenever a taxonomy scheme changes so caches and snapshots can refresh. */
data class TaxonomyChanged(
    val scheme: TaxonomyScheme,
    val code: String,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata
