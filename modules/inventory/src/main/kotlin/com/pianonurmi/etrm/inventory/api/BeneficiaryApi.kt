package com.pianonurmi.etrm.inventory.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.time.Instant
import java.util.UUID

/**
 * A cancellation's beneficiary in the register: the one place that keeps the name and identifier, and the one place
 * they are erased from (review finding S7). Everything else that must not change refers to [id].
 */
data class BeneficiaryView(
    val id: UUID,
    val name: String?,
    val identifier: String?,
    val recordedAt: Instant,
    val recordedBy: String?,
    val erasureRequestedAt: Instant?,
    val erasureRequestedBy: String?,
    val erasureReason: String?,
    val erasedAt: Instant?,
    val erasedBy: String?,
)

interface BeneficiaryCommand {
    /**
     * [details] with its beneficiary in the register: a new entry when the details name a beneficiary and carry no
     * reference yet; otherwise the details as they are.
     */
    fun record(details: CancellationDetails): CancellationDetails

    /** Asks for the beneficiary's name and identifier to be erased; a second person approves it in the inbox. */
    fun requestErasure(id: UUID, reason: String): BeneficiaryView
}

interface BeneficiaryQuery {
    fun beneficiary(id: UUID): BeneficiaryView?
}

/** Carries no name or identifier: the event itself goes to the audit trail. */
data class BeneficiaryErasureRequested(val beneficiaryId: UUID, val requestedBy: String, val reason: String, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

data class BeneficiaryErasureWithdrawn(val beneficiaryId: UUID, val reason: String?, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/**
 * The beneficiary's name and identifier are erased from the register and the lots. Modules that keep a copy of the
 * cancellation (registry instructions, obligations) erase theirs in the same transaction.
 */
data class BeneficiaryErased(val beneficiaryId: UUID, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
