package com.pianonurmi.etrm.referencedata

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqChangeRequestRepository
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.AccountChangeAction
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestStatus
import com.pianonurmi.etrm.referencedata.api.CounterpartyAccountChange
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataChangeRequested
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.api.RegistryChange
import com.pianonurmi.etrm.referencedata.api.RegistryChanged
import com.pianonurmi.etrm.referencedata.api.RegistryIntegration
import com.pianonurmi.etrm.referencedata.api.RequestChange
import com.pianonurmi.etrm.referencedata.application.ChangeRequestService
import com.pianonurmi.etrm.referencedata.application.CounterpartyAccountChangeApplier
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.referencedata.application.RegistryChangeApplier
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangeRequestServiceIT {
    private val published = mutableListOf<Any>()
    private val clock = Clock.fixed(java.time.Instant.parse("2026-10-09T09:00:00Z"), java.time.ZoneOffset.UTC) // a Friday
    private var actor = Actor("ops", "Ops", setOf(Role.REGISTRY_OPERATOR), allEntities = true)
    private lateinit var repository: JooqReferenceDataRepository
    private lateinit var referenceData: ReferenceDataService
    private lateinit var changes: ChangeRequestService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata")
        val publisher = ApplicationEventPublisher { published += it }
        repository = JooqReferenceDataRepository(TestDatabase.dsl)
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(TestDatabase.dsl), publisher)
        referenceData = ReferenceDataService(repository, taxonomy, publisher)
        changes = ChangeRequestService(
            JooqChangeRequestRepository(TestDatabase.dsl),
            listOf(
                RegistryChangeApplier(repository, publisher),
                CounterpartyAccountChangeApplier(repository, referenceData, publisher, clock),
            ),
            { actor },
            publisher,
            clock,
        )
    }

    @Test
    fun `registry changes are validated on request and applied only on approval`() {
        val before = repository.findRegistry("HKNR")!!
        before.integration shouldBe RegistryIntegration.FILE

        shouldThrow<ValidationException> { changes.requestRegistryChange("HKNR", RegistryChange(reason = "nothing", expectedVersion = before.version)) }
        shouldThrow<StaleVersionException> {
            changes.requestRegistryChange("HKNR", RegistryChange(RegistryIntegration.MANUAL, reason = "outage", expectedVersion = before.version + 5))
        }
        shouldThrow<ValidationException> {
            changes.request(RequestChange(ChangeEntityType.REGISTRY, "HKNR", mapOf("name" to "renamed"), "rename", before.version))
        }
        val request = changes.requestRegistryChange(
            "HKNR",
            RegistryChange(RegistryIntegration.MANUAL, reason = "file transfer outage", expectedVersion = before.version),
        )
        request.status shouldBe ChangeRequestStatus.PENDING
        request.summary shouldBe "Registry HKNR: integration FILE -> MANUAL"
        published.filterIsInstance<ReferenceDataChangeRequested>().last().request.id shouldBe request.id
        repository.findRegistry("HKNR")!!.integration shouldBe RegistryIntegration.FILE
        shouldThrow<ConflictException> {
            changes.requestRegistryChange("HKNR", RegistryChange(active = false, reason = "second", expectedVersion = before.version))
        }

        val applied = changes.approve(request.id, "supervisor", "ok")
        applied.status shouldBe ChangeRequestStatus.APPLIED
        applied.decidedBy shouldBe "supervisor"
        repository.findRegistry("HKNR")!!.let {
            it.integration shouldBe RegistryIntegration.MANUAL
            it.version shouldBe before.version + 1
        }
        published.filterIsInstance<RegistryChanged>().last().previous.integration shouldBe RegistryIntegration.FILE
        shouldThrow<ConflictException> { changes.approve(request.id, "supervisor", null) }

        val revert = changes.requestRegistryChange(
            "HKNR",
            RegistryChange(RegistryIntegration.FILE, reason = "outage over", expectedVersion = before.version + 1),
        )
        changes.reject(revert.id, "supervisor", "keep manual for now").status shouldBe ChangeRequestStatus.REJECTED
        changes.changeRequests(ChangeRequestStatus.REJECTED, ChangeEntityType.REGISTRY).map { it.id } shouldBe listOf(revert.id)

        val cancelMe = changes.requestRegistryChange("HKNR", RegistryChange(active = false, reason = "decommission", expectedVersion = before.version + 1))
        actor = Actor("someone", "Someone", setOf(Role.BACK_OFFICE), allEntities = true)
        shouldThrow<ForbiddenException> { changes.cancel(cancelMe.id, "not mine") }
        actor = Actor("ops", "Ops", setOf(Role.REGISTRY_OPERATOR), allEntities = true)
        changes.cancel(cancelMe.id, "changed my mind").status shouldBe ChangeRequestStatus.CANCELLED
        actor = Actor("trader", "Trader", setOf(Role.TRADER), allEntities = true)
        shouldThrow<ForbiddenException> {
            changes.requestRegistryChange("HKNR", RegistryChange(active = false, reason = "x", expectedVersion = before.version + 1))
        }
    }

    @Test
    fun `counterparty registry accounts are added and put on the trusted list only with four eyes`() {
        val counterparty = referenceData.createCounterparty(CreateCounterparty("CP-ACC", "Account Holder GmbH", "DE"))
        shouldThrow<ValidationException> {
            referenceData.createRegistryAccount(
                CreateRegistryAccount("UNION_REGISTRY", "EU-100-777", "Direct", RegistryAccountType.TRADING, HolderType.COUNTERPARTY, counterparty.id),
            )
        }
        actor = Actor("ops", "Ops", setOf(Role.REGISTRY_OPERATOR), allEntities = true)
        shouldThrow<ValidationException> {
            changes.requestCounterpartyAccountChange(
                counterparty.id,
                CounterpartyAccountChange(
                    AccountChangeAction.CREATE,
                    "new account",
                    registryCode = "UNION_REGISTRY",
                    accountNumber = "EU-100-777",
                    name = "Trading",
                    accountType = RegistryAccountType.HOLDING,
                ),
            )
        }
        val create = changes.requestCounterpartyAccountChange(
            counterparty.id,
            CounterpartyAccountChange(
                AccountChangeAction.CREATE,
                "onboarding",
                registryCode = "UNION_REGISTRY",
                accountNumber = "EU-100-777",
                name = "Trading",
                accountType = RegistryAccountType.TRADING,
            ),
        )
        create.summary shouldBe "Counterparty CP-ACC: add UNION_REGISTRY account EU-100-777 (TRADING)"
        referenceData.registryAccountByRef("UNION_REGISTRY:EU-100-777") shouldBe null
        changes.approve(create.id, "supervisor", null)
        val account = referenceData.registryAccountByRef("union_registry:EU-100-777")!!
        account.holderType shouldBe HolderType.COUNTERPARTY
        account.holderId shouldBe counterparty.id
        account.canExport shouldBe false
        account.trusted shouldBe false

        val trust = changes.requestCounterpartyAccountChange(
            counterparty.id,
            CounterpartyAccountChange(AccountChangeAction.ADD_TO_TRUSTED_LIST, "regular buyer", accountId = account.id),
        )
        changes.approve(trust.id, "supervisor", null)
        referenceData.requireRegistryAccount(account.id).let {
            it.trusted shouldBe true
            // Friday 9 October + four TARGET2 working days = Thursday 15 October.
            it.trustedSince shouldBe LocalDate.of(2026, 10, 15)
            it.trustedOn(LocalDate.of(2026, 10, 14)) shouldBe false
            it.trustedOn(LocalDate.of(2026, 10, 15)) shouldBe true
        }
        shouldThrow<ValidationException> {
            changes.requestCounterpartyAccountChange(
                counterparty.id,
                CounterpartyAccountChange(AccountChangeAction.ADD_TO_TRUSTED_LIST, "again", accountId = account.id),
            )
        }
        val other = referenceData.createCounterparty(CreateCounterparty("CP-OTHER", "Other AG", "DE"))
        shouldThrow<ValidationException> {
            changes.requestCounterpartyAccountChange(other.id, CounterpartyAccountChange(AccountChangeAction.DEACTIVATE, "not theirs", accountId = account.id))
        }
        changes.approve(
            changes.requestCounterpartyAccountChange(
                counterparty.id,
                CounterpartyAccountChange(AccountChangeAction.DEACTIVATE, "closed", accountId = account.id),
            ).id,
            "supervisor",
            null,
        )
        referenceData.requireRegistryAccount(account.id).active shouldBe false
    }
}
