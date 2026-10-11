package com.pianonurmi.etrm.inventory.adapter.rest

import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * REST guards for statements and reconciliation breaks: reads by the operations and control roles, imports by
 * registry operators and back office, each limited to accounts held by an entity in the user's scope.
 */
@Component
class ReconciliationAccess(private val actorProvider: ActorProvider, private val referenceData: ReferenceDataQuery) {
    fun reader(): Actor = actorProvider.current().also { it.requireRole(*READERS) }

    fun reader(registryAccountId: UUID?): Actor = reader().also { it.requireEntity(holderOf(registryAccountId)) }

    fun importer(registryAccountId: UUID?): Actor = actorProvider.current().also {
        it.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
        it.requireEntity(holderOf(registryAccountId))
    }

    /** Rows whose account belongs to an entity outside the user's scope are left out. */
    fun <T> visible(rows: List<T>, account: (T) -> UUID?): List<T> {
        val actor = reader()
        if (actor.seesAllEntities) return rows
        val holders = mutableMapOf<UUID?, UUID?>()
        return rows.filter { actor.canAccess(holders.getOrPut(account(it)) { holderOf(account(it)) }) }
    }

    /**
     * The registry accounts a reader may count breaks of: those of [legalEntityId] when given, else those of the
     * user's entities; null when every entity is in scope.
     */
    fun accountsInScope(legalEntityId: UUID?): Set<UUID>? {
        val actor = reader()
        val entities = when {
            legalEntityId != null -> setOf(legalEntityId.also(actor::requireEntity))
            actor.seesAllEntities -> return null
            else -> actor.legalEntityScope
        }
        return entities.flatMap { referenceData.registryAccounts(HolderType.LEGAL_ENTITY, it) }.map { it.id }.toSet()
    }

    private fun holderOf(registryAccountId: UUID?): UUID? {
        val account = registryAccountId?.let(referenceData::requireRegistryAccount) ?: return null
        return account.holderId.takeIf { account.holderType == HolderType.LEGAL_ENTITY }
    }

    companion object {
        private val READERS = arrayOf(
            Role.REGISTRY_OPERATOR,
            Role.BACK_OFFICE,
            Role.MIDDLE_OFFICE,
            Role.ACCOUNTANT,
            Role.RISK,
            Role.AUDITOR,
        )
    }
}
