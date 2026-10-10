package com.pianonurmi.etrm.referencedata.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.time.LocalDate
import java.util.UUID

/** Our legal entities and our counterparties share address, VAT and bank details. */
enum class PartyType { LEGAL_ENTITY, COUNTERPARTY }

enum class AddressType { REGISTERED, BILLING }

data class AddressView(
    val partyType: PartyType,
    val partyId: UUID,
    val addressType: AddressType,
    val line1: String,
    val line2: String?,
    val postalCode: String,
    val city: String,
    val country: String,
) {
    /** Single-line form for documents. */
    val text: String get() = listOfNotNull(line1, line2, "$postalCode $city", country).joinToString(", ")
}

data class UpsertAddress(
    val addressType: AddressType,
    val line1: String,
    val line2: String? = null,
    val postalCode: String,
    val city: String,
    val country: String,
)

/** A VAT registration in one country; a party may be registered in several. */
data class VatRegistrationView(
    val id: UUID,
    val partyType: PartyType,
    val partyId: UUID,
    val country: String,
    val vatNumber: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
) {
    fun validOn(date: LocalDate): Boolean = date >= validFrom && (validTo == null || date < validTo)
}

data class UpsertVatRegistration(val country: String, val vatNumber: String, val validFrom: LocalDate, val validTo: LocalDate? = null)

data class BankAccountView(
    val id: UUID,
    val partyType: PartyType,
    val partyId: UUID,
    val currency: String,
    val iban: String,
    val bic: String,
    val accountName: String,
    val active: Boolean,
    val version: Int,
)

enum class BankAccountAction { CREATE, DEACTIVATE }

/** A change to a bank account; payments go there, so every change needs a second person. CREATE needs all fields. */
data class BankAccountChange(
    val action: BankAccountAction,
    val reason: String,
    val accountId: UUID? = null,
    val currency: String? = null,
    val iban: String? = null,
    val bic: String? = null,
    val accountName: String? = null,
)

interface PartyDetailsQuery {
    fun addresses(partyType: PartyType, partyId: UUID): List<AddressView>

    /** The billing address, else the registered one. */
    fun billingAddress(partyType: PartyType, partyId: UUID): AddressView?

    fun vatRegistrations(partyType: PartyType, partyId: UUID): List<VatRegistrationView>

    /** The party's VAT registration valid in [country] on [date]. */
    fun vatRegistration(partyType: PartyType, partyId: UUID, country: String, date: LocalDate): VatRegistrationView?

    fun bankAccounts(partyType: PartyType, partyId: UUID): List<BankAccountView>

    fun bankAccount(id: UUID): BankAccountView?

    /** The active account of the party in [currency], if exactly one. */
    fun bankAccountFor(partyType: PartyType, partyId: UUID, currency: String): BankAccountView?
}

interface PartyDetailsCommand {
    fun upsertAddress(partyType: PartyType, partyId: UUID, command: UpsertAddress): AddressView

    fun upsertVatRegistration(partyType: PartyType, partyId: UUID, command: UpsertVatRegistration): VatRegistrationView

    /** Opens a four-eyes change request (`BANK_ACCOUNT`); the change applies when a second person approves it. */
    fun requestBankAccountChange(partyType: PartyType, partyId: UUID, change: BankAccountChange): ChangeRequestView
}

data class BankAccountChanged(val account: BankAccountView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
