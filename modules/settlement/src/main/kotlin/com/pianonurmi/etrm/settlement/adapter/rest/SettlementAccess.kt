package com.pianonurmi.etrm.settlement.adapter.rest

import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * REST guards for settlement. Module flows (deliveries, ledger postings, reports) call the services directly as the
 * system, so roles and legal-entity scope for people are checked here: reads by the finance and control roles,
 * every record limited to the entities in the user's scope.
 */
@Component
class SettlementAccess(private val actorProvider: ActorProvider) {
    fun reader(): Actor = actorProvider.current().also { it.requireRole(*READERS) }

    /** A reader who may see [legalEntityId]. */
    fun reader(legalEntityId: UUID?): Actor = reader().also { it.requireEntity(legalEntityId) }

    /** Rows of entities outside the user's scope are left out of a list. */
    fun <T> visible(rows: List<T>, entity: (T) -> UUID?): List<T> {
        val actor = reader()
        return rows.filter { actor.canAccess(entity(it)) }
    }

    /** Re-evaluating an item: finance and middle office, on the item's entity (the system re-evaluates without a role). */
    fun operator(legalEntityId: UUID?): Actor = actorProvider.current().also {
        it.requireRole(Role.ACCOUNTANT, Role.BACK_OFFICE, Role.MIDDLE_OFFICE)
        it.requireEntity(legalEntityId)
    }

    /** A command on data of [legalEntityId]: the service checks the role, this checks the scope. */
    fun inScope(legalEntityId: UUID?) = actorProvider.current().requireEntity(legalEntityId)

    companion object {
        private val READERS = arrayOf(Role.BACK_OFFICE, Role.ACCOUNTANT, Role.MIDDLE_OFFICE, Role.RISK, Role.AUDITOR)
    }
}
