package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.domain.PostingRuleRepository
import com.pianonurmi.etrm.ledger.domain.SeedRepository
import com.pianonurmi.etrm.platform.ids.Ids
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/**
 * Applies the default posting rules shipped with the release. When the resource's version is newer than the one last
 * applied, default rules (those without a legal entity) are brought in line with it and defaults no longer shipped are
 * deactivated. Entity-specific overrides are never touched.
 */
@Component
class PostingRuleSeeder(private val repository: PostingRuleRepository, private val seeds: SeedRepository) : InitializingBean {
    private val log = LoggerFactory.getLogger(PostingRuleSeeder::class.java)

    /** Idempotent: each statement commits on its own and the version is recorded last. */
    override fun afterPropertiesSet() = seed()

    fun seed() {
        val shipped = DefaultLedgerData.postingRules()
        val applied = seeds.version(SEED_NAME)
        if (applied != null && applied >= shipped.version) return
        val defaults = repository.all().filter { it.legalEntityId == null }.associateBy { it.ruleCode }
        shipped.rules.forEach { rule ->
            val code = rule.ruleCode.uppercase()
            val current = defaults[code]
            val view = PostingRuleView(
                current?.id ?: Ids.newId(),
                null,
                code,
                rule.eventType,
                rule.conditions,
                rule.lines,
                rule.description,
                rule.priority,
                rule.effectiveFrom,
                rule.active,
                current?.version ?: 1,
            )
            if (current == null || current.copy(version = 0) != view.copy(version = 0)) repository.upsert(view)
        }
        val shippedCodes = shipped.rules.map { it.ruleCode.uppercase() }.toSet()
        defaults.values.filter { it.ruleCode !in shippedCodes && it.active }.forEach { repository.upsert(it.copy(active = false)) }
        seeds.record(SEED_NAME, shipped.version)
        log.info("Applied default posting rules version {} (previously {})", shipped.version, applied ?: "none")
    }

    private companion object {
        const val SEED_NAME = "posting-rules"
    }
}
