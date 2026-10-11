package com.pianonurmi.etrm.referencedata.adapter.rest

import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.AddressView
import com.pianonurmi.etrm.referencedata.api.BankAccountChange
import com.pianonurmi.etrm.referencedata.api.BankAccountView
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.api.PartyDetailsCommand
import com.pianonurmi.etrm.referencedata.api.PartyDetailsQuery
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.UpsertAddress
import com.pianonurmi.etrm.referencedata.api.UpsertVatRegistration
import com.pianonurmi.etrm.referencedata.api.VatRegistrationView
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Addresses, VAT registrations and bank accounts of legal entities (`LEGAL_ENTITY`) and counterparties (`COUNTERPARTY`). */
@RestController
@RequestMapping("/api/v1/reference/parties/{partyType}/{partyId}")
@Tag(name = "Party details", description = "Addresses, VAT registrations and bank accounts")
class PartyDetailsController(
    private val command: PartyDetailsCommand,
    private val query: PartyDetailsQuery,
    private val actorProvider: ActorProvider,
) {
    /** Addresses and VAT numbers feed invoices and tax reporting; a legal entity's own details stay within scope. */
    private fun requireMaintainer(partyType: PartyType, partyId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.ACCOUNTANT)
        if (partyType == PartyType.LEGAL_ENTITY) actor.requireEntity(partyId)
    }

    @GetMapping("/addresses")
    fun addresses(
        @PathVariable partyType: PartyType,
        @PathVariable partyId: UUID,
    ): List<AddressView> = query.addresses(partyType, partyId)

    @PutMapping("/addresses")
    fun upsertAddress(
        @PathVariable partyType: PartyType,
        @PathVariable partyId: UUID,
        @RequestBody body: UpsertAddress,
    ): AddressView {
        requireMaintainer(partyType, partyId)
        return command.upsertAddress(partyType, partyId, body)
    }

    @GetMapping("/vat-registrations")
    fun vatRegistrations(
        @PathVariable partyType: PartyType,
        @PathVariable partyId: UUID,
    ): List<VatRegistrationView> = query.vatRegistrations(partyType, partyId)

    @PutMapping("/vat-registrations")
    fun upsertVatRegistration(
        @PathVariable partyType: PartyType,
        @PathVariable partyId: UUID,
        @RequestBody body: UpsertVatRegistration,
    ): VatRegistrationView {
        requireMaintainer(partyType, partyId)
        return command.upsertVatRegistration(partyType, partyId, body)
    }

    @GetMapping("/bank-accounts")
    fun bankAccounts(
        @PathVariable partyType: PartyType,
        @PathVariable partyId: UUID,
    ): List<BankAccountView> = query.bankAccounts(partyType, partyId)

    /** Bank account changes apply only when a second person approves the REFERENCE_DATA_CHANGE task. */
    @PostMapping("/bank-accounts/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestBankAccountChange(
        @PathVariable partyType: PartyType,
        @PathVariable partyId: UUID,
        @RequestBody body: BankAccountChange,
    ): ChangeRequestView = command.requestBankAccountChange(partyType, partyId, body)
}
