package com.pianonurmi.etrm.registry.adapter.rest

import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.security.Roles
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * REST guards for registry operations. Allocation, delivery and the dispatcher call the services directly as the
 * system, so people are checked here: who may read, submit and operate instructions, and on which legal entity's
 * accounts.
 */
@Component
class RegistryAccess(
    private val actorProvider: ActorProvider,
    private val lots: LotQuery,
    private val referenceData: ReferenceDataQuery,
) {
    fun reader(): Actor = actorProvider.current().also { it.requireRole(*READERS) }

    fun reader(holderEntityId: UUID?): Actor = reader().also { it.requireEntity(holderEntityId) }

    /** Confirm, fail, cancel and retry: registry operators and back office on the holder's instructions. */
    fun operator(holderEntityId: UUID?): Actor = actorProvider.current().also {
        it.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
        it.requireEntity(holderEntityId)
    }

    /** New manual instructions: also middle office, on lots or accounts the user's entities hold. */
    fun submitterForLot(lotId: UUID): Actor = submitter(lots.requireLot(lotId).holderEntityId)

    fun submitterForAccount(accountId: UUID): Actor = submitter(holderOf(accountId))

    /** Transfer routes and other settings shared by every entity. */
    fun maintainer(): Actor = actorProvider.current().also { it.requireRole(Roles.OPERATIONS) }

    /** A statement of one of the entity's accounts. */
    fun importer(accountId: UUID?): Actor = actorProvider.current().also {
        it.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
        it.requireEntity(holderOf(accountId))
    }

    /** A registry-wide transaction export confirms instructions of every entity, so it needs access to all of them. */
    fun registryWideImporter(): Actor = registryWideOperator("A registry transaction export")

    /** [what] acts on instructions of every legal entity at one registry, so it needs access to all of them. */
    fun registryWideOperator(what: String): Actor = actorProvider.current().also {
        it.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
        if (!it.seesAllEntities) throw ForbiddenException("$what affects every legal entity; user ${it.userId} sees only some")
    }

    private fun submitter(holderEntityId: UUID?): Actor = actorProvider.current().also {
        it.requireRole(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE, Role.MIDDLE_OFFICE)
        it.requireEntity(holderEntityId)
    }

    private fun holderOf(accountId: UUID?): UUID? {
        val account = accountId?.let(referenceData::requireRegistryAccount) ?: return null
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
