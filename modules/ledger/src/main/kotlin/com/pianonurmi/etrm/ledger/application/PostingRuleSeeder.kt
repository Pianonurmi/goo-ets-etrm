package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.domain.PostingRuleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/** Loads the default posting rules once; entity-specific overrides are added through the API. */
@Component
class PostingRuleSeeder(private val repository: PostingRuleRepository, private val ledger: LedgerService) : InitializingBean {
    private val log = LoggerFactory.getLogger(PostingRuleSeeder::class.java)

    override fun afterPropertiesSet() = seed()

    fun seed() {
        val existing = repository.all().filter { it.legalEntityId == null }.map { it.ruleCode }.toSet()
        val added = DefaultLedgerData.postingRules().filter { it.ruleCode.uppercase() !in existing }.onEach { ledger.upsertRule(it) }
        if (added.isNotEmpty()) log.info("Seeded {} default posting rules", added.size)
    }
}
