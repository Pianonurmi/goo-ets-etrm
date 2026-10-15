package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.BeneficiaryErased
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** An erased beneficiary leaves no name or identifier in the instructions that cancelled for it (S7); idempotent. */
@Component
class InstructionBeneficiaryErasure(private val repository: RegistryInstructionRepository) {
    @EventListener
    fun on(event: BeneficiaryErased) {
        repository.eraseBeneficiary(event.beneficiaryId)
    }
}
