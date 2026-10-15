package com.pianonurmi.etrm.referencedata.adapter.jooq

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.BankAccount.BANK_ACCOUNT
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.PartyAddress.PARTY_ADDRESS
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.VatRegistration.VAT_REGISTRATION
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.BankAccountRecord
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.PartyAddressRecord
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.VatRegistrationRecord
import com.pianonurmi.etrm.referencedata.api.AddressType
import com.pianonurmi.etrm.referencedata.api.AddressView
import com.pianonurmi.etrm.referencedata.api.BankAccountView
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.VatRegistrationView
import com.pianonurmi.etrm.referencedata.domain.PartyDetailsRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class JooqPartyDetailsRepository(private val dsl: DSLContext) : PartyDetailsRepository {
    override fun addresses(partyType: PartyType, partyId: UUID): List<AddressView> = dsl.selectFrom(PARTY_ADDRESS)
        .where(PARTY_ADDRESS.PARTY_TYPE.eq(partyType.name), PARTY_ADDRESS.PARTY_ID.eq(partyId))
        .orderBy(PARTY_ADDRESS.ADDRESS_TYPE)
        .fetch(::toAddress)

    override fun upsertAddress(address: AddressView) {
        dsl.insertInto(PARTY_ADDRESS)
            .set(PARTY_ADDRESS.ID, Ids.newId())
            .set(PARTY_ADDRESS.PARTY_TYPE, address.partyType.name)
            .set(PARTY_ADDRESS.PARTY_ID, address.partyId)
            .set(PARTY_ADDRESS.ADDRESS_TYPE, address.addressType.name)
            .set(PARTY_ADDRESS.LINE1, address.line1)
            .set(PARTY_ADDRESS.LINE2, address.line2)
            .set(PARTY_ADDRESS.POSTAL_CODE, address.postalCode)
            .set(PARTY_ADDRESS.CITY, address.city)
            .set(PARTY_ADDRESS.COUNTRY, address.country)
            .onConflict(PARTY_ADDRESS.PARTY_TYPE, PARTY_ADDRESS.PARTY_ID, PARTY_ADDRESS.ADDRESS_TYPE)
            .doUpdate()
            .set(PARTY_ADDRESS.LINE1, address.line1)
            .set(PARTY_ADDRESS.LINE2, address.line2)
            .set(PARTY_ADDRESS.POSTAL_CODE, address.postalCode)
            .set(PARTY_ADDRESS.CITY, address.city)
            .set(PARTY_ADDRESS.COUNTRY, address.country)
            .set(PARTY_ADDRESS.VERSION, PARTY_ADDRESS.VERSION.plus(1))
            .set(PARTY_ADDRESS.UPDATED_AT, DSL.currentInstant())
            .execute()
    }

    override fun vatRegistrations(partyType: PartyType, partyId: UUID): List<VatRegistrationView> = dsl.selectFrom(VAT_REGISTRATION)
        .where(VAT_REGISTRATION.PARTY_TYPE.eq(partyType.name), VAT_REGISTRATION.PARTY_ID.eq(partyId))
        .orderBy(VAT_REGISTRATION.COUNTRY)
        .fetch(::toVat)

    override fun upsertVatRegistration(registration: VatRegistrationView) {
        dsl.insertInto(VAT_REGISTRATION)
            .set(VAT_REGISTRATION.ID, registration.id)
            .set(VAT_REGISTRATION.PARTY_TYPE, registration.partyType.name)
            .set(VAT_REGISTRATION.PARTY_ID, registration.partyId)
            .set(VAT_REGISTRATION.COUNTRY, registration.country)
            .set(VAT_REGISTRATION.VAT_NUMBER, registration.vatNumber)
            .set(VAT_REGISTRATION.VALID_FROM, registration.validFrom)
            .set(VAT_REGISTRATION.VALID_TO, registration.validTo)
            .onConflict(VAT_REGISTRATION.PARTY_TYPE, VAT_REGISTRATION.PARTY_ID, VAT_REGISTRATION.COUNTRY)
            .doUpdate()
            .set(VAT_REGISTRATION.VAT_NUMBER, registration.vatNumber)
            .set(VAT_REGISTRATION.VALID_FROM, registration.validFrom)
            .set(VAT_REGISTRATION.VALID_TO, registration.validTo)
            .set(VAT_REGISTRATION.VERSION, VAT_REGISTRATION.VERSION.plus(1))
            .set(VAT_REGISTRATION.UPDATED_AT, DSL.currentInstant())
            .execute()
    }

    override fun bankAccounts(partyType: PartyType, partyId: UUID): List<BankAccountView> = dsl.selectFrom(BANK_ACCOUNT)
        .where(BANK_ACCOUNT.PARTY_TYPE.eq(partyType.name), BANK_ACCOUNT.PARTY_ID.eq(partyId))
        .orderBy(BANK_ACCOUNT.CREATED_AT)
        .fetch(::toBank)

    override fun bankAccount(id: UUID): BankAccountView? = dsl.selectFrom(BANK_ACCOUNT).where(BANK_ACCOUNT.ID.eq(id)).fetchOne()?.let(::toBank)

    override fun insertBankAccount(account: BankAccountView) {
        dsl.insertInto(BANK_ACCOUNT)
            .set(BANK_ACCOUNT.ID, account.id)
            .set(BANK_ACCOUNT.PARTY_TYPE, account.partyType.name)
            .set(BANK_ACCOUNT.PARTY_ID, account.partyId)
            .set(BANK_ACCOUNT.CURRENCY, account.currency)
            .set(BANK_ACCOUNT.IBAN, account.iban)
            .set(BANK_ACCOUNT.BIC, account.bic)
            .set(BANK_ACCOUNT.ACCOUNT_NAME, account.accountName)
            .set(BANK_ACCOUNT.ACTIVE, account.active)
            .set(BANK_ACCOUNT.VERSION, account.version)
            .execute()
    }

    override fun updateBankAccount(account: BankAccountView, expectedVersion: Int): Boolean = dsl.update(BANK_ACCOUNT)
        .set(BANK_ACCOUNT.ACTIVE, account.active)
        .set(BANK_ACCOUNT.VERSION, account.version)
        .set(BANK_ACCOUNT.UPDATED_AT, DSL.currentInstant())
        .where(BANK_ACCOUNT.ID.eq(account.id), BANK_ACCOUNT.VERSION.eq(expectedVersion))
        .execute() == 1

    override fun bankAccountByIban(partyType: PartyType, partyId: UUID, iban: String): BankAccountView? = dsl.selectFrom(BANK_ACCOUNT)
        .where(BANK_ACCOUNT.PARTY_TYPE.eq(partyType.name), BANK_ACCOUNT.PARTY_ID.eq(partyId), BANK_ACCOUNT.IBAN.eq(iban))
        .fetchOne()?.let(::toBank)

    private fun toAddress(r: PartyAddressRecord) = AddressView(
        PartyType.valueOf(r.partyType),
        r.partyId,
        AddressType.valueOf(r.addressType),
        r.line1,
        r.line2,
        r.postalCode,
        r.city,
        r.country,
    )

    private fun toVat(r: VatRegistrationRecord) =
        VatRegistrationView(r.id, PartyType.valueOf(r.partyType), r.partyId, r.country, r.vatNumber, r.validFrom, r.validTo)

    private fun toBank(r: BankAccountRecord) =
        BankAccountView(r.id, PartyType.valueOf(r.partyType), r.partyId, r.currency, r.iban, r.bic, r.accountName, r.active, r.version)
}
