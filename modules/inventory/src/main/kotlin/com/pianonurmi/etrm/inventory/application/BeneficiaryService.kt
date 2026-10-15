package com.pianonurmi.etrm.inventory.application

import com.pianonurmi.etrm.inventory.api.BeneficiaryCommand
import com.pianonurmi.etrm.inventory.api.BeneficiaryErased
import com.pianonurmi.etrm.inventory.api.BeneficiaryErasureRequested
import com.pianonurmi.etrm.inventory.api.BeneficiaryErasureWithdrawn
import com.pianonurmi.etrm.inventory.api.BeneficiaryQuery
import com.pianonurmi.etrm.inventory.api.BeneficiaryView
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.domain.BeneficiaryRepository
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The beneficiary register (review finding S7, `docs/operations/data-protection.md`). Cancellations record their
 * beneficiary here and carry the reference; an erasure is requested by an administrator with a reason and applied
 * when a second person (an auditor) approves it in the inbox.
 */
@Service
@Transactional
class BeneficiaryService(
    private val repository: BeneficiaryRepository,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : BeneficiaryCommand, BeneficiaryQuery {
    override fun record(details: CancellationDetails): CancellationDetails {
        if (details.beneficiaryRef != null || !details.namesBeneficiary()) return details
        val id = Ids.newId()
        repository.insert(id, details.beneficiaryName, details.beneficiaryId, actorProvider.current().userId)
        return details.copy(beneficiaryRef = id)
    }

    override fun requestErasure(id: UUID, reason: String): BeneficiaryView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ADMIN)
        if (reason.isBlank()) throw ValidationException("An erasure needs a reason")
        val beneficiary = require(id)
        if (beneficiary.erasedAt != null) throw ConflictException("Beneficiary $id is already erased")
        if (beneficiary.erasureRequestedAt != null) throw ConflictException("Erasure of beneficiary $id is already requested")
        repository.requestErasure(id, actor.userId, reason, clock.instant())
        events.publishEvent(BeneficiaryErasureRequested(id, actor.userId, reason))
        return require(id)
    }

    /** Applies an approved erasure; the approver is never the person who asked for it. */
    fun erase(id: UUID, approvedBy: String): BeneficiaryView {
        val beneficiary = require(id)
        if (beneficiary.erasedAt != null) return beneficiary
        val requestedBy = beneficiary.erasureRequestedBy ?: throw ConflictException("Erasure of beneficiary $id was not requested")
        if (requestedBy == approvedBy) throw ValidationException("The erasure of beneficiary $id needs a second person")
        repository.erase(id, approvedBy, clock.instant())
        events.publishEvent(BeneficiaryErased(id))
        return require(id)
    }

    fun withdrawErasure(id: UUID, reason: String?): BeneficiaryView {
        val beneficiary = require(id)
        if (beneficiary.erasedAt != null) throw ConflictException("Beneficiary $id is already erased")
        repository.withdrawErasure(id)
        events.publishEvent(BeneficiaryErasureWithdrawn(id, reason))
        return require(id)
    }

    @Transactional(readOnly = true)
    override fun beneficiary(id: UUID): BeneficiaryView? = repository.find(id)

    private fun require(id: UUID) = repository.find(id, forUpdate = true) ?: throw NotFoundException("Beneficiary", id)
}
