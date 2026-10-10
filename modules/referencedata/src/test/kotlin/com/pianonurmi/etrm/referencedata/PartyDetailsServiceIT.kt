package com.pianonurmi.etrm.referencedata

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqChangeRequestRepository
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqPartyDetailsRepository
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.AddressType
import com.pianonurmi.etrm.referencedata.api.BankAccountAction
import com.pianonurmi.etrm.referencedata.api.BankAccountChange
import com.pianonurmi.etrm.referencedata.api.ChangeRequestCommand
import com.pianonurmi.etrm.referencedata.api.ChangeRequestStatus
import com.pianonurmi.etrm.referencedata.api.CounterpartyAccountChange
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.PartyType
import com.pianonurmi.etrm.referencedata.api.RegistryChange
import com.pianonurmi.etrm.referencedata.api.RequestChange
import com.pianonurmi.etrm.referencedata.api.UpdateCounterparty
import com.pianonurmi.etrm.referencedata.api.UpsertAddress
import com.pianonurmi.etrm.referencedata.api.UpsertVatRegistration
import com.pianonurmi.etrm.referencedata.application.BankAccountChangeApplier
import com.pianonurmi.etrm.referencedata.application.ChangeRequestService
import com.pianonurmi.etrm.referencedata.application.PartyDetailsService
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartyDetailsServiceIT {
    private var actor = Actor("ops", "Ops", setOf(Role.BACK_OFFICE), allEntities = true)
    private lateinit var referenceData: ReferenceDataService
    private lateinit var parties: PartyDetailsService
    private lateinit var changes: ChangeRequestService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata")
        val publisher = ApplicationEventPublisher { }
        val repository = JooqReferenceDataRepository(TestDatabase.dsl)
        referenceData = ReferenceDataService(repository, TaxonomyService(JooqTaxonomyRepository(TestDatabase.dsl), publisher), publisher)
        // The change request service needs the applier, which needs the party service, which opens change requests.
        val deferred = object : ChangeRequestCommand {
            override fun request(command: RequestChange) = changes.request(command)

            override fun requestRegistryChange(code: String, change: RegistryChange) = changes.requestRegistryChange(code, change)

            override fun requestCounterpartyAccountChange(counterpartyId: UUID, change: CounterpartyAccountChange) =
                changes.requestCounterpartyAccountChange(counterpartyId, change)

            override fun cancel(id: UUID, note: String) = changes.cancel(id, note)
        }
        val details = JooqPartyDetailsRepository(TestDatabase.dsl)
        parties = PartyDetailsService(details, referenceData, deferred)
        changes = ChangeRequestService(
            JooqChangeRequestRepository(TestDatabase.dsl),
            listOf(BankAccountChangeApplier(details, parties, publisher)),
            { actor },
            publisher,
            Clock.systemUTC(),
        )
    }

    @Test
    fun `addresses and VAT registrations are kept per party, bank accounts only change with four eyes`() {
        val cp = referenceData.createCounterparty(CreateCounterparty("PD-CP", "Payer GmbH", "DE", selfBilling = true)).id
        referenceData.requireCounterparty(cp).selfBilling shouldBe true
        referenceData.updateCounterparty(cp, UpdateCounterparty(nettingAgreed = true, expectedVersion = 1)).nettingAgreed shouldBe true

        parties.upsertAddress(PartyType.COUNTERPARTY, cp, UpsertAddress(AddressType.REGISTERED, "Hauptstr. 1", null, "10115", "Berlin", "de"))
        parties.billingAddress(PartyType.COUNTERPARTY, cp)!!.text shouldBe "Hauptstr. 1, 10115 Berlin, DE"
        parties.upsertAddress(PartyType.COUNTERPARTY, cp, UpsertAddress(AddressType.BILLING, "Postfach 9", null, "10117", "Berlin", "DE"))
        parties.billingAddress(PartyType.COUNTERPARTY, cp)!!.line1 shouldBe "Postfach 9"

        parties.upsertVatRegistration(PartyType.COUNTERPARTY, cp, UpsertVatRegistration("DE", "DE 123456789", LocalDate.of(2020, 1, 1)))
        parties.upsertVatRegistration(PartyType.COUNTERPARTY, cp, UpsertVatRegistration("FI", "FI12345678", LocalDate.of(2026, 1, 1)))
        parties.vatRegistration(PartyType.COUNTERPARTY, cp, "de", LocalDate.of(2026, 6, 1))!!.vatNumber shouldBe "DE123456789"
        parties.vatRegistration(PartyType.COUNTERPARTY, cp, "FI", LocalDate.of(2025, 6, 1)) shouldBe null
        shouldThrow<ValidationException> {
            parties.upsertVatRegistration(PartyType.COUNTERPARTY, cp, UpsertVatRegistration("SE", "12-34", LocalDate.of(2026, 1, 1)))
        }

        shouldThrow<ValidationException> {
            parties.requestBankAccountChange(
                PartyType.COUNTERPARTY,
                cp,
                BankAccountChange(
                    BankAccountAction.CREATE,
                    "onboarding",
                    currency = "EUR",
                    iban = "DE89370400440532013001",
                    bic = "COBADEFFXXX",
                    accountName = "Payer",
                ),
            )
        }
        val request = parties.requestBankAccountChange(
            PartyType.COUNTERPARTY,
            cp,
            BankAccountChange(
                BankAccountAction.CREATE,
                "onboarding",
                currency = "eur",
                iban = "DE89 3704 0044 0532 0130 00",
                bic = "COBADEFFXXX",
                accountName = "Payer",
            ),
        )
        parties.bankAccounts(PartyType.COUNTERPARTY, cp).shouldBeEmpty()
        shouldThrow<SegregationOfDutiesException> { changes.approve(request.id, "ops", null) }
        changes.approve(request.id, "supervisor", null).status shouldBe ChangeRequestStatus.APPLIED
        val account = parties.bankAccountFor(PartyType.COUNTERPARTY, cp, "EUR")!!
        account.iban shouldBe "DE89370400440532013000"
        shouldThrow<ConflictException> {
            parties.requestBankAccountChange(
                PartyType.COUNTERPARTY,
                cp,
                BankAccountChange(
                    BankAccountAction.CREATE,
                    "again",
                    currency = "EUR",
                    iban = "DE89370400440532013000",
                    bic = "COBADEFFXXX",
                    accountName = "Payer",
                ),
            )
        }
        val deactivate = parties.requestBankAccountChange(
            PartyType.COUNTERPARTY,
            cp,
            BankAccountChange(BankAccountAction.DEACTIVATE, "closed", accountId = account.id),
        )
        changes.approve(deactivate.id, "supervisor", null)
        parties.bankAccountFor(PartyType.COUNTERPARTY, cp, "EUR") shouldBe null
    }
}
