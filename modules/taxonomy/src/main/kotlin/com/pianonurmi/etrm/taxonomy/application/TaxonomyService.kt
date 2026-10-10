package com.pianonurmi.etrm.taxonomy.application

import com.pianonurmi.etrm.platform.bitemporal.ValidPeriod
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.taxonomy.api.CreateTaxonomyNode
import com.pianonurmi.etrm.taxonomy.api.TaxonomyChanged
import com.pianonurmi.etrm.taxonomy.api.TaxonomyCommand
import com.pianonurmi.etrm.taxonomy.api.TaxonomyNodeView
import com.pianonurmi.etrm.taxonomy.api.TaxonomyQuery
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import com.pianonurmi.etrm.taxonomy.api.TaxonomySnapshot
import com.pianonurmi.etrm.taxonomy.api.UpdateTaxonomyNode
import com.pianonurmi.etrm.taxonomy.domain.TaxonomyCode
import com.pianonurmi.etrm.taxonomy.domain.TaxonomyNode
import com.pianonurmi.etrm.taxonomy.domain.TaxonomyRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class TaxonomyService(
    private val repository: TaxonomyRepository,
    private val events: ApplicationEventPublisher,
) : TaxonomyQuery, TaxonomyCommand {
    override fun node(scheme: TaxonomyScheme, code: String): TaxonomyNodeView? = repository.find(scheme, TaxonomyCode(code))?.toView()

    override fun require(scheme: TaxonomyScheme, code: String): TaxonomyNodeView =
        node(scheme, code) ?: throw NotFoundException("TaxonomyNode", "$scheme/$code")

    override fun children(scheme: TaxonomyScheme, parentCode: String?): List<TaxonomyNodeView> =
        repository.children(scheme, parentCode?.let(::TaxonomyCode)).map { it.toView() }

    override fun subtree(scheme: TaxonomyScheme, ancestorCode: String): List<TaxonomyNodeView> {
        val ancestor = repository.find(scheme, TaxonomyCode(ancestorCode)) ?: throw NotFoundException("TaxonomyNode", "$scheme/$ancestorCode")
        return repository.subtree(scheme, ancestor.path).map { it.toView() }
    }

    override fun isDescendantOrSelf(scheme: TaxonomyScheme, code: String, ancestorCode: String): Boolean {
        val node = repository.find(scheme, TaxonomyCode(code)) ?: return false
        val ancestor = repository.find(scheme, TaxonomyCode(ancestorCode)) ?: return false
        return node.path.isDescendantOrSelf(ancestor.path)
    }

    override fun regionCountries(regionCode: String): Set<String> {
        val region = repository.find(TaxonomyScheme.REGION, TaxonomyCode(regionCode)) ?: throw NotFoundException("Region", regionCode)
        return repository.regionMembers(region.id)
    }

    override fun snapshot(scheme: TaxonomyScheme): TaxonomySnapshot {
        val nodes = repository.all(scheme)
        val regions = if (scheme == TaxonomyScheme.REGION) {
            val members = repository.allRegionMembers()
            nodes.associate { it.code.value to (members[it.id] ?: emptySet()) }
        } else {
            emptyMap()
        }
        return TaxonomySnapshot(scheme, nodes.map { it.toView() }, regions)
    }

    override fun byExternalCode(scheme: TaxonomyScheme, system: String, externalCode: String): TaxonomyNodeView? =
        repository.findByExternalCode(scheme, system, externalCode)?.toView()

    @Transactional
    override fun create(command: CreateTaxonomyNode): TaxonomyNodeView {
        val code = TaxonomyCode(command.code)
        if (repository.find(command.scheme, code) != null) {
            throw ConflictException("Taxonomy node ${command.scheme}/${command.code} already exists")
        }
        val validPeriod = ValidPeriod(command.validFrom, command.validTo)
        val node = if (command.parentCode == null) {
            TaxonomyNode.root(command.scheme, code, command.name, command.description, command.externalCodes, validPeriod)
        } else {
            val parent = repository.find(command.scheme, TaxonomyCode(command.parentCode))
                ?: throw NotFoundException("TaxonomyNode", "${command.scheme}/${command.parentCode}")
            TaxonomyNode.childOf(parent, code, command.name, command.description, command.externalCodes, validPeriod)
        }
        if (node.name.isBlank()) throw ValidationException("Name must not be blank")
        repository.insert(node)
        events.publishEvent(TaxonomyChanged(node.scheme, node.code.value))
        return node.toView()
    }

    @Transactional
    override fun update(scheme: TaxonomyScheme, code: String, command: UpdateTaxonomyNode): TaxonomyNodeView {
        val node = repository.find(scheme, TaxonomyCode(code)) ?: throw NotFoundException("TaxonomyNode", "$scheme/$code")
        if (node.version != command.expectedVersion) {
            throw StaleVersionException("TaxonomyNode", "$scheme/$code", command.expectedVersion, node.version)
        }
        command.name?.let(node::rename)
        command.description?.let { node.description = it }
        command.externalCodes?.let { node.externalCodes = it }
        command.validTo?.let(node::endValidity)
        if (!repository.update(node, command.expectedVersion)) {
            throw StaleVersionException("TaxonomyNode", "$scheme/$code", command.expectedVersion, node.version)
        }
        events.publishEvent(TaxonomyChanged(scheme, code))
        return node.copyWithVersion(command.expectedVersion + 1).toView()
    }

    @Transactional
    override fun setRegionMembers(regionCode: String, countries: Set<String>): Set<String> {
        val region = repository.find(TaxonomyScheme.REGION, TaxonomyCode(regionCode)) ?: throw NotFoundException("Region", regionCode)
        val normalised = countries.map { it.trim().uppercase() }.toSet()
        normalised.firstOrNull { !Regex("^[A-Z]{2}$").matches(it) }?.let {
            throw ValidationException("'$it' is not an ISO 3166-1 alpha-2 country code")
        }
        repository.replaceRegionMembers(region.id, normalised)
        events.publishEvent(TaxonomyChanged(TaxonomyScheme.REGION, regionCode))
        return normalised
    }

    private fun TaxonomyNode.copyWithVersion(newVersion: Int): TaxonomyNode = also { it.version = newVersion }
}
