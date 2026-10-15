package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.domain.ObligationRepository
import com.pianonurmi.etrm.inventory.api.BeneficiaryErased
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** An erased beneficiary leaves no name or identifier in the obligations that named it (S7); idempotent. */
@Component
class ObligationBeneficiaryErasure(private val repository: ObligationRepository) {
    @EventListener
    fun on(event: BeneficiaryErased) {
        repository.eraseBeneficiary(event.beneficiaryId)
    }
}
