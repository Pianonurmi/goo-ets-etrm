package com.pianonurmi.etrm.referencedata.domain

import com.pianonurmi.etrm.referencedata.api.AddressView
import com.pianonurmi.etrm.referencedata.api.BankAccountView
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestStatus
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.VatRegistrationView
import java.util.UUID

interface ChangeRequestRepository {
    fun insert(request: ChangeRequestView)

    fun find(id: UUID): ChangeRequestView?

    fun lock(id: UUID): ChangeRequestView?

    fun pendingFor(entityType: ChangeEntityType, entityKey: String): ChangeRequestView?

    fun list(status: ChangeRequestStatus?, entityType: ChangeEntityType?): List<ChangeRequestView>

    fun update(request: ChangeRequestView): Boolean
}

/** Validates and applies changes of one entity type. Validation runs at request time and again when applying. */
interface ChangeApplier {
    val entityType: ChangeEntityType

    /** Returns a one-line summary of the change, or throws a validation error. */
    fun validate(entityKey: String, change: Map<String, Any?>, expectedVersion: Int?): String

    /** The legal entity the change belongs to, if any (for inbox scoping). */
    fun legalEntityOf(entityKey: String): UUID? = null

    fun apply(request: ChangeRequestView)
}

interface PartyDetailsRepository {
    fun addresses(partyType: PartyType, partyId: UUID): List<AddressView>

    fun upsertAddress(address: AddressView)

    fun vatRegistrations(
        partyType: PartyType,
        partyId: UUID,
    ): List<VatRegistrationView>

    fun upsertVatRegistration(registration: VatRegistrationView)

    fun bankAccounts(partyType: PartyType, partyId: UUID): List<BankAccountView>

    fun bankAccount(id: UUID): BankAccountView?

    fun bankAccountByIban(
        partyType: PartyType,
        partyId: UUID,
        iban: String,
    ): BankAccountView?

    fun insertBankAccount(account: BankAccountView)

    fun updateBankAccount(account: BankAccountView, expectedVersion: Int): Boolean
}
