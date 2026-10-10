package com.pianonurmi.etrm.taxonomy.adapter.jooq

import com.pianonurmi.etrm.platform.bitemporal.ValidPeriod
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.taxonomy.adapter.jooq.generated.tables.RegionMember.REGION_MEMBER
import com.pianonurmi.etrm.taxonomy.adapter.jooq.generated.tables.TaxonomyNode.TAXONOMY_NODE
import com.pianonurmi.etrm.taxonomy.adapter.jooq.generated.tables.records.TaxonomyNodeRecord
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import com.pianonurmi.etrm.taxonomy.domain.TaxonomyCode
import com.pianonurmi.etrm.taxonomy.domain.TaxonomyNode
import com.pianonurmi.etrm.taxonomy.domain.TaxonomyPath
import com.pianonurmi.etrm.taxonomy.domain.TaxonomyRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.postgres.extensions.types.Ltree
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JooqTaxonomyRepository(private val dsl: DSLContext) : TaxonomyRepository {
    private val parent = TAXONOMY_NODE.`as`("parent")

    override fun find(scheme: TaxonomyScheme, code: TaxonomyCode): TaxonomyNode? = baseSelect()
        .where(TAXONOMY_NODE.SCHEME.eq(scheme.name), TAXONOMY_NODE.CODE.eq(code.value))
        .fetchOne()?.let(::toDomain)

    override fun findByExternalCode(scheme: TaxonomyScheme, system: String, externalCode: String): TaxonomyNode? = baseSelect()
        .where(
            TAXONOMY_NODE.SCHEME.eq(scheme.name),
            DSL.condition("{0} @> {1}::jsonb", TAXONOMY_NODE.EXTERNAL_CODES, DSL.inline(Json.mapper.writeValueAsString(mapOf(system to externalCode)))),
        )
        .fetchOne()?.let(::toDomain)

    override fun children(scheme: TaxonomyScheme, parentCode: TaxonomyCode?): List<TaxonomyNode> = baseSelect()
        .where(TAXONOMY_NODE.SCHEME.eq(scheme.name))
        .and(if (parentCode == null) parent.CODE.isNull else parent.CODE.eq(parentCode.value))
        .orderBy(TAXONOMY_NODE.CODE)
        .fetch(::toDomain)

    override fun subtree(scheme: TaxonomyScheme, ancestorPath: TaxonomyPath): List<TaxonomyNode> = baseSelect()
        .where(TAXONOMY_NODE.SCHEME.eq(scheme.name))
        .and(DSL.condition("{0} <@ {1}::public.ltree", TAXONOMY_NODE.PATH, DSL.inline(ancestorPath.value)))
        .orderBy(TAXONOMY_NODE.PATH)
        .fetch(::toDomain)

    override fun all(scheme: TaxonomyScheme): List<TaxonomyNode> = baseSelect()
        .where(TAXONOMY_NODE.SCHEME.eq(scheme.name))
        .orderBy(TAXONOMY_NODE.PATH)
        .fetch(::toDomain)

    override fun insert(node: TaxonomyNode) {
        dsl.insertInto(TAXONOMY_NODE)
            .set(TAXONOMY_NODE.ID, node.id)
            .set(TAXONOMY_NODE.SCHEME, node.scheme.name)
            .set(TAXONOMY_NODE.CODE, node.code.value)
            .set(TAXONOMY_NODE.PARENT_ID, node.parentId)
            .set(TAXONOMY_NODE.PATH, Ltree.ltree(node.path.value))
            .set(TAXONOMY_NODE.DEPTH, node.depth)
            .set(TAXONOMY_NODE.NAME, node.name)
            .set(TAXONOMY_NODE.DESCRIPTION, node.description)
            .set(TAXONOMY_NODE.EXTERNAL_CODES, JSONB.jsonb(Json.mapper.writeValueAsString(node.externalCodes)))
            .set(TAXONOMY_NODE.VALID_FROM, node.validPeriod.validFrom)
            .set(TAXONOMY_NODE.VALID_TO, node.validPeriod.validTo)
            .set(TAXONOMY_NODE.VERSION, node.version)
            .execute()
    }

    override fun update(node: TaxonomyNode, expectedVersion: Int): Boolean = dsl.update(TAXONOMY_NODE)
        .set(TAXONOMY_NODE.NAME, node.name)
        .set(TAXONOMY_NODE.DESCRIPTION, node.description)
        .set(TAXONOMY_NODE.EXTERNAL_CODES, JSONB.jsonb(Json.mapper.writeValueAsString(node.externalCodes)))
        .set(TAXONOMY_NODE.VALID_TO, node.validPeriod.validTo)
        .set(TAXONOMY_NODE.VERSION, expectedVersion + 1)
        .set(TAXONOMY_NODE.UPDATED_AT, Instant.now())
        .where(TAXONOMY_NODE.ID.eq(node.id), TAXONOMY_NODE.VERSION.eq(expectedVersion))
        .execute() == 1

    override fun regionMembers(regionId: UUID): Set<String> = dsl.select(REGION_MEMBER.COUNTRY)
        .from(REGION_MEMBER)
        .where(REGION_MEMBER.REGION_NODE_ID.eq(regionId))
        .fetchSet(REGION_MEMBER.COUNTRY)

    override fun allRegionMembers(): Map<UUID, Set<String>> = dsl.select(REGION_MEMBER.REGION_NODE_ID, REGION_MEMBER.COUNTRY)
        .from(REGION_MEMBER)
        .fetchGroups(REGION_MEMBER.REGION_NODE_ID, REGION_MEMBER.COUNTRY)
        .mapValues { it.value.toSet() }

    override fun replaceRegionMembers(regionId: UUID, countries: Set<String>) {
        dsl.deleteFrom(REGION_MEMBER).where(REGION_MEMBER.REGION_NODE_ID.eq(regionId)).execute()
        if (countries.isNotEmpty()) {
            val insert = dsl.insertInto(REGION_MEMBER, REGION_MEMBER.REGION_NODE_ID, REGION_MEMBER.COUNTRY)
            countries.sorted().forEach { insert.values(regionId, it) }
            insert.execute()
        }
    }

    private fun baseSelect() = dsl.select(TAXONOMY_NODE.asterisk(), parent.CODE.`as`("parent_code"))
        .from(TAXONOMY_NODE)
        .leftJoin(parent).on(parent.ID.eq(TAXONOMY_NODE.PARENT_ID))

    private fun toDomain(record: org.jooq.Record): TaxonomyNode {
        val row: TaxonomyNodeRecord = record.into(TAXONOMY_NODE)
        val parentCode: String? = record.get("parent_code", String::class.java)
        return TaxonomyNode(
            id = row.id,
            scheme = TaxonomyScheme.valueOf(row.scheme),
            code = TaxonomyCode(row.code),
            parentId = row.parentId,
            parentCode = parentCode?.let(::TaxonomyCode),
            path = TaxonomyPath(row.path.data()),
            name = row.name,
            description = row.description,
            externalCodes = Json.mapper.readValue(row.externalCodes.data(), Map::class.java)
                .entries.associate { it.key.toString() to it.value.toString() },
            validPeriod = ValidPeriod(row.validFrom, row.validTo),
            version = row.version,
        )
    }
}
