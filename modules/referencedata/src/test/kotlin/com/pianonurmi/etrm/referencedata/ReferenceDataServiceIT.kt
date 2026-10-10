package com.pianonurmi.etrm.referencedata

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CreateBook
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateProductionDevice
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.EmirClassification
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.OwnerType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.api.UpdateCounterparty
import com.pianonurmi.etrm.referencedata.api.UpdateLegalEntity
import com.pianonurmi.etrm.referencedata.api.UpdateProductionDevice
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataPolicies
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceDataServiceIT {
    private lateinit var service: ReferenceDataService

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata")
        val publisher = ApplicationEventPublisher { }
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(TestDatabase.dsl), publisher)
        service = ReferenceDataService(JooqReferenceDataRepository(TestDatabase.dsl), taxonomy, publisher)
    }

    @Test
    fun `counterparties carry their EMIR classification and group parent, without cycles`() {
        val parent = service.createCounterparty(CreateCounterparty("GRP-PARENT", "Group Parent AG", "DE", emirClassification = EmirClassification.FC))
        val child = service.createCounterparty(CreateCounterparty("GRP-CHILD", "Group Trading GmbH", "DE", parentCounterpartyId = parent.id))
        child.parentCounterpartyId shouldBe parent.id
        shouldThrow<ValidationException> {
            service.updateCounterparty(parent.id, UpdateCounterparty(expectedVersion = parent.version, parentCounterpartyId = child.id))
        }
        service.updateCounterparty(child.id, UpdateCounterparty(expectedVersion = child.version, emirClassification = EmirClassification.NFC_PLUS))
            .emirClassification shouldBe EmirClassification.NFC_PLUS
        service.requireCounterparty(parent.id).emirClassification shouldBe EmirClassification.FC
    }

    @Test
    fun `legal entity lifecycle with transaction-time history`() {
        val entity = service.createLegalEntity(
            CreateLegalEntity(code = "nordic-power", name = "Nordic Power Oy", country = "fi", validFrom = LocalDate.of(2026, 1, 1)),
        )
        entity.code shouldBe "NORDIC-POWER"
        entity.country shouldBe "FI"
        val beforeUpdate = Instant.now()
        Thread.sleep(5)
        val updated = service.updateLegalEntity(entity.id, UpdateLegalEntity(name = "Nordic Power Group Oy", expectedVersion = 1))
        updated.version shouldBe 2

        shouldThrow<StaleVersionException> { service.updateLegalEntity(entity.id, UpdateLegalEntity(name = "x", expectedVersion = 1)) }
        shouldThrow<ConflictException> { service.createLegalEntity(CreateLegalEntity("NORDIC-POWER", "dup", "FI", validFrom = LocalDate.of(2026, 1, 1))) }

        val history = service.history("legal_entity", entity.id)
        history shouldHaveSize 2
        history[0].recordedTo shouldNotBe null
        history[1].recordedTo shouldBe null
        service.asOf("legal_entity", entity.id, beforeUpdate)?.get("name") shouldBe "Nordic Power Oy"
        service.asOf("legal_entity", entity.id, Instant.now())?.get("name") shouldBe "Nordic Power Group Oy"
    }

    @Test
    fun `books, counterparties, registry accounts and devices validate against taxonomy and registries`() {
        val entity = service.createLegalEntity(CreateLegalEntity("TRADING-CO", "Trading Co", "SE", validFrom = LocalDate.of(2026, 1, 1)))
        val book = service.createBook(CreateBook(entity.id, "goo-trading", "GoO trading", BookType.TRADING))
        service.books(entity.id).map { it.code } shouldBe listOf("GOO-TRADING")
        shouldThrow<ConflictException> { service.createBook(CreateBook(entity.id, "GOO-TRADING", "dup", BookType.TRADING)) }
        book.bookType shouldBe BookType.TRADING

        shouldThrow<ValidationException> {
            service.createCounterparty(CreateCounterparty("ICO", "Intercompany", "FI", counterpartyType = CounterpartyType.INTERCOMPANY))
        }
        val cp = service.createCounterparty(CreateCounterparty("STATKRAFT", "Statkraft AS", "NO", lei = "5493000J2N45DDNE4Y28"))
        cp.lei shouldBe "5493000J2N45DDNE4Y28"
        shouldThrow<ValidationException> { service.createCounterparty(CreateCounterparty("BADLEI", "Bad", "NO", lei = "5493000J2N45DDNE4Y29")) }

        val account = service.createRegistryAccount(
            CreateRegistryAccount("FINGRID", "FI-0001", "Main holding", RegistryAccountType.HOLDING, HolderType.LEGAL_ENTITY, entity.id),
        )
        account.registryCode shouldBe "FINGRID"
        shouldThrow<ValidationException> {
            service.createRegistryAccount(
                CreateRegistryAccount("FINGRID", "FI-0002", "Wrong type", RegistryAccountType.OPERATOR_HOLDING, HolderType.LEGAL_ENTITY, entity.id),
            )
        }
        service.registryAccountByNumber("fingrid", "FI-0001")?.id shouldBe account.id

        val device = service.createProductionDevice(
            CreateProductionDevice(
                gsrn = "643000000000000016",
                name = "Tuulipuisto 1",
                country = "FI",
                capacityKw = BigDecimal("45000"),
                commissioningDate = LocalDate.of(2021, 6, 1),
                energySourceCode = "WIND_ONSHORE",
                technologyCode = "WIND_TURBINE_ONSHORE",
                labelCodes = setOf("EKOENERGY"),
                registryCode = "FINGRID",
                ownerType = OwnerType.LEGAL_ENTITY,
                ownerId = entity.id,
                ownDevice = true,
            ),
        )
        device.labelCodes shouldBe setOf("EKOENERGY")
        service.productionDevices(ownDevicesOnly = true).map { it.gsrn } shouldBe listOf("643000000000000016")
        shouldThrow<com.pianonurmi.etrm.platform.errors.NotFoundException> {
            service.createProductionDevice(
                CreateProductionDevice(
                    gsrn = "643000000000000023",
                    name = "Bad",
                    country = "FI",
                    capacityKw = BigDecimal.ONE,
                    commissioningDate = LocalDate.of(2020, 1, 1),
                    energySourceCode = "UNICORN",
                    technologyCode = "WIND_TURBINE_ONSHORE",
                    ownerType = OwnerType.THIRD_PARTY,
                ),
            )
        }
        val updatedDevice = service.updateProductionDevice(
            device.id,
            UpdateProductionDevice(labelCodes = setOf("EKOENERGY", "BRA_MILJOVAL"), expectedVersion = 1),
        )
        updatedDevice.version shouldBe 2
        service.history("production_device", device.id) shouldHaveSize 2
    }

    @Test
    fun `GSRN check digit validation follows GS1 mod 10`() {
        ReferenceDataPolicies.gs1CheckDigitValid("643000000000000016") shouldBe true
        ReferenceDataPolicies.gs1CheckDigitValid("643000000000000012") shouldBe false
        shouldThrow<ValidationException> { ReferenceDataPolicies.gsrn("643000000000000012") }
    }

    @Test
    fun `business day arithmetic honours weekends and TARGET2 holidays`() {
        service.isBusinessDay("TARGET2", LocalDate.of(2026, 4, 3)) shouldBe false
        service.isBusinessDay("TARGET2", LocalDate.of(2026, 4, 4)) shouldBe false
        service.isBusinessDay("TARGET2", LocalDate.of(2026, 4, 7)) shouldBe true
        service.addBusinessDays("TARGET2", LocalDate.of(2026, 4, 2), 1) shouldBe LocalDate.of(2026, 4, 7)
    }
}
