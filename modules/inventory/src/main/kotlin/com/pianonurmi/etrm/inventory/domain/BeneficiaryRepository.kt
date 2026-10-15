package com.pianonurmi.etrm.inventory.domain

import com.pianonurmi.etrm.inventory.api.BeneficiaryView
import java.time.Instant
import java.util.UUID

interface BeneficiaryRepository {
    fun insert(id: UUID, name: String?, identifier: String?, recordedBy: String)

    fun find(id: UUID, forUpdate: Boolean = false): BeneficiaryView?

    fun requestErasure(id: UUID, requestedBy: String, reason: String, at: Instant)

    fun withdrawErasure(id: UUID)

    /** Erases the name and identifier from the register and from the lots cancelled for the beneficiary. */
    fun erase(id: UUID, erasedBy: String, at: Instant)
}
