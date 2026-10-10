package com.pianonurmi.etrm.referencedata.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.referencedata.api.AddressType
import com.pianonurmi.etrm.referencedata.api.AddressView
import com.pianonurmi.etrm.referencedata.api.BankAccountAction
import com.pianonurmi.etrm.referencedata.api.BankAccountChange
import com.pianonurmi.etrm.referencedata.api.BankAccountChanged
import com.pianonurmi.etrm.referencedata.api.BankAccountView
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestCommand
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.api.PartyDetailsCommand
import com.pianonurmi.etrm.referencedata.api.PartyDetailsQuery
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.RequestChange
import com.pianonurmi.etrm.referencedata.api.UpsertAddress
import com.pianonurmi.etrm.referencedata.api.UpsertVatRegistration
import com.pianonurmi.etrm.referencedata.api.VatRegistrationView
import com.pianonurmi.etrm.referencedata.domain.ChangeApplier
import com.pianonurmi.etrm.referencedata.domain.PartyDetailsRepository
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataPolicies
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID

/** Addresses and VAT registrations change directly; bank accounts only through a four-eyes change request. */
@Service
@Transactional
class PartyDetailsService(
    private val repository: PartyDetailsRepository,
    private val referenceData: ReferenceDataService,
    @Lazy private val changes: ChangeRequestCommand,
) : PartyDetailsCommand, PartyDetailsQuery {
    override fun upsertAddress(partyType: PartyType, partyId: UUID, command: UpsertAddress): AddressView {
        requireParty(partyType, partyId)
        val address = AddressView(
            partyType,
            partyId,
            command.addressType,
            command.line1.trim().ifBlank { throw ValidationException("line1 is required") },
            command.line2?.trim()?.ifBlank { null },
            command.postalCode.trim().ifBlank { throw ValidationException("postalCode is required") },
            command.city.trim().ifBlank { throw ValidationException("city is required") },
            ReferenceDataPolicies.country(command.country),
        )
        repository.upsertAddress(address)
        return address
    }

    override fun upsertVatRegistration(partyType: PartyType, partyId: UUID, command: UpsertVatRegistration): VatRegistrationView {
        requireParty(partyType, partyId)
        val country = ReferenceDataPolicies.country(command.country)
        val number = command.vatNumber.replace(" ", "").uppercase()
        if (!Regex("^[A-Z]{2}[A-Z0-9+*]{2,13}$").matches(number)) throw ValidationException("VAT number $number is not a valid VAT identification number")
        if (command.validTo != null && command.validTo <= command.validFrom) throw ValidationException("validTo must follow validFrom")
        val existing = repository.vatRegistrations(partyType, partyId).firstOrNull { it.country == country }
        val registration = VatRegistrationView(existing?.id ?: Ids.newId(), partyType, partyId, country, number, command.validFrom, command.validTo)
        repository.upsertVatRegistration(registration)
        return registration
    }

    override fun requestBankAccountChange(partyType: PartyType, partyId: UUID, change: BankAccountChange): ChangeRequestView {
        requireParty(partyType, partyId)
        val key = when (change.action) {
            BankAccountAction.CREATE -> "$partyType/$partyId/${change.iban?.let(::normaliseIban)}"
            BankAccountAction.DEACTIVATE -> change.accountId?.toString() ?: throw ValidationException("DEACTIVATE needs accountId")
        }
        return changes.request(
            RequestChange(
                entityType = ChangeEntityType.BANK_ACCOUNT,
                entityKey = key,
                change = buildMap {
                    put("action", change.action.name)
                    put("partyType", partyType.name)
                    put("partyId", partyId.toString())
                    change.accountId?.let { put("accountId", it.toString()) }
                    change.currency?.let { put("currency", it.uppercase()) }
                    change.iban?.let { put("iban", normaliseIban(it)) }
                    change.bic?.let { put("bic", it.replace(" ", "").uppercase()) }
                    change.accountName?.let { put("accountName", it.trim()) }
                },
                reason = change.reason,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun addresses(partyType: PartyType, partyId: UUID): List<AddressView> = repository.addresses(partyType, partyId)

    @Transactional(readOnly = true)
    override fun billingAddress(partyType: PartyType, partyId: UUID): AddressView? = repository.addresses(partyType, partyId).let { all ->
        all.firstOrNull { it.addressType == AddressType.BILLING } ?: all.firstOrNull { it.addressType == AddressType.REGISTERED }
    }

    @Transactional(readOnly = true)
    override fun vatRegistrations(partyType: PartyType, partyId: UUID): List<VatRegistrationView> = repository.vatRegistrations(partyType, partyId)

    @Transactional(readOnly = true)
    override fun vatRegistration(partyType: PartyType, partyId: UUID, country: String, date: LocalDate): VatRegistrationView? =
        repository.vatRegistrations(partyType, partyId).firstOrNull { it.country == country.uppercase() && it.validOn(date) }

    @Transactional(readOnly = true)
    override fun bankAccounts(partyType: PartyType, partyId: UUID): List<BankAccountView> = repository.bankAccounts(partyType, partyId)

    @Transactional(readOnly = true)
    override fun bankAccount(id: UUID): BankAccountView? = repository.bankAccount(id)

    @Transactional(readOnly = true)
    override fun bankAccountFor(partyType: PartyType, partyId: UUID, currency: String): BankAccountView? =
        repository.bankAccounts(partyType, partyId).filter { it.active && it.currency == currency.uppercase() }.singleOrNull()

    fun requireParty(partyType: PartyType, partyId: UUID) {
        when (partyType) {
            PartyType.LEGAL_ENTITY -> referenceData.requireLegalEntity(partyId)
            PartyType.COUNTERPARTY -> referenceData.requireCounterparty(partyId)
        }
    }

    companion object {
        fun normaliseIban(iban: String): String = iban.replace(" ", "").uppercase()

        /** ISO 13616: move the first four characters to the end, letters to numbers, remainder mod 97 must be 1. */
        fun validIban(iban: String): Boolean {
            if (!Regex("^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$").matches(iban)) return false
            val digits = (iban.drop(4) + iban.take(4)).map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString("")
            return BigInteger(digits).mod(BigInteger.valueOf(97)) == BigInteger.ONE
        }

        fun validBic(bic: String): Boolean = Regex("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$").matches(bic)
    }
}

/** Bank accounts receive and pay money: they are created and deactivated only with a second person's approval. */
@Component
class BankAccountChangeApplier(
    private val repository: PartyDetailsRepository,
    private val parties: PartyDetailsService,
    private val events: ApplicationEventPublisher,
) : ChangeApplier {
    override val entityType = ChangeEntityType.BANK_ACCOUNT

    override fun validate(entityKey: String, change: Map<String, Any?>, expectedVersion: Int?): String {
        val partyType = PartyType.valueOf(text(change, "partyType"))
        val partyId = UUID.fromString(text(change, "partyId"))
        parties.requireParty(partyType, partyId)
        return when (BankAccountAction.valueOf(text(change, "action"))) {
            BankAccountAction.CREATE -> {
                val iban = text(change, "iban")
                if (!PartyDetailsService.validIban(iban)) throw ValidationException("IBAN $iban is not valid")
                val bic = text(change, "bic")
                if (!PartyDetailsService.validBic(bic)) throw ValidationException("BIC $bic is not valid")
                ReferenceDataPolicies.currency(text(change, "currency"))
                text(change, "accountName")
                if (repository.bankAccountByIban(partyType, partyId, iban) != null) throw ConflictException("Account $iban is already registered")
                "${partyType.name.lowercase().replace('_', ' ')} $partyId: add bank account $iban (${text(change, "currency")})"
            }
            BankAccountAction.DEACTIVATE -> {
                val account = account(change)
                if (account.partyType != partyType || account.partyId != partyId) throw ValidationException("Account ${account.iban} belongs to another party")
                if (!account.active) throw ValidationException("Account ${account.iban} is already inactive")
                "${partyType.name.lowercase().replace('_', ' ')} $partyId: deactivate bank account ${account.iban}"
            }
        }
    }

    override fun legalEntityOf(entityKey: String): UUID? = null

    override fun apply(request: ChangeRequestView) {
        val change = request.change
        val account = when (BankAccountAction.valueOf(text(change, "action"))) {
            BankAccountAction.CREATE -> BankAccountView(
                id = Ids.newId(),
                partyType = PartyType.valueOf(text(change, "partyType")),
                partyId = UUID.fromString(text(change, "partyId")),
                currency = text(change, "currency"),
                iban = text(change, "iban"),
                bic = text(change, "bic"),
                accountName = text(change, "accountName"),
                active = true,
                version = 1,
            ).also(repository::insertBankAccount)
            BankAccountAction.DEACTIVATE -> {
                val current = account(change)
                val next = current.copy(active = false, version = current.version + 1)
                if (!repository.updateBankAccount(next, current.version)) throw StaleVersionException("BankAccount", current.id, current.version, next.version)
                next
            }
        }
        events.publishEvent(BankAccountChanged(account))
    }

    private fun account(change: Map<String, Any?>): BankAccountView {
        val id = UUID.fromString(text(change, "accountId"))
        return repository.bankAccount(id) ?: throw NotFoundException("BankAccount", id)
    }

    private fun text(change: Map<String, Any?>, key: String): String =
        change[key]?.toString()?.takeIf { it.isNotBlank() } ?: throw ValidationException("$key is required")
}
