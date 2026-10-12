package com.pianonurmi.etrm.allocation.domain

import com.pianonurmi.etrm.allocation.api.AllocationPlanView
import com.pianonurmi.etrm.allocation.api.ObligationView
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface ObligationRepository {
    fun find(id: UUID): ObligationView?

    fun lock(id: UUID): ObligationView?

    fun bySource(sourceRef: UUID): ObligationView?

    fun open(holderEntityId: UUID): List<ObligationView>

    /** A fingerprint of the entity's open obligations: count, versions, fulfilled MWh and the newest id. */
    fun openStamp(holderEntityId: UUID): String

    fun insert(obligation: ObligationView)

    fun update(obligation: ObligationView): Boolean
}

interface AllocationPlanRepository {
    fun find(id: UUID): AllocationPlanView?

    fun lock(id: UUID): AllocationPlanView?

    fun byKey(idempotencyKey: String): AllocationPlanView?

    fun byEntity(holderEntityId: UUID, activeOnly: Boolean): List<AllocationPlanView>

    fun byInstruction(instructionId: UUID): AllocationPlanView?

    fun insert(plan: AllocationPlanView)

    fun update(plan: AllocationPlanView): Boolean

    fun expiredProposals(now: Instant): List<UUID>

    /** Soft reservations of PROPOSED, unexpired plans other than [excludingPlan], by lot. */
    fun activeSoftReservations(holderEntityId: UUID, now: Instant, excludingPlan: UUID? = null): Map<UUID, Long>

    fun replaceSoftReservations(planId: UUID, reservations: Map<UUID, Long>, expiresAt: Instant)

    fun deleteSoftReservations(planId: UUID)

    /** Takes a transaction-scoped advisory lock serialising commits for one entity. */
    fun lockEntity(holderEntityId: UUID)
}
