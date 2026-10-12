package com.pianonurmi.etrm.referencedata.application

import com.pianonurmi.etrm.platform.bitemporal.ValidPeriod
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.referencedata.api.BookView
import com.pianonurmi.etrm.referencedata.api.CounterpartyChanged
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CounterpartyView
import com.pianonurmi.etrm.referencedata.api.CreateBook
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateProductionDevice
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HistoryEntry
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.LegalEntityChanged
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceChanged
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataCommand
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountChanged
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryType
import com.pianonurmi.etrm.referencedata.api.RegistryView
import com.pianonurmi.etrm.referencedata.api.UpdateCounterparty
import com.pianonurmi.etrm.referencedata.api.UpdateLegalEntity
import com.pianonurmi.etrm.referencedata.api.UpdateProductionDevice
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataPolicies
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataRepository
import com.pianonurmi.etrm.taxonomy.api.TaxonomyQuery
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
@Transactional(readOnly = true)
class ReferenceDataService(
    private val repository: ReferenceDataRepository,
    private val taxonomy: TaxonomyQuery,
    private val events: ApplicationEventPublisher,
) : ReferenceDataQuery, ReferenceDataCommand {
    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    override fun legalEntity(id: UUID): LegalEntityView? = repository.findLegalEntity(id)

    override fun requireLegalEntity(id: UUID): LegalEntityView = legalEntity(id) ?: throw NotFoundException("LegalEntity", id)

    override fun legalEntityByCode(code: String): LegalEntityView? = repository.findLegalEntityByCode(code.uppercase())

    override fun legalEntities(): List<LegalEntityView> = repository.allLegalEntities()

    override fun book(id: UUID): BookView? = repository.findBook(id)

    override fun requireBook(id: UUID): BookView = book(id) ?: throw NotFoundException("Book", id)

    override fun books(legalEntityId: UUID): List<BookView> = repository.booksOf(legalEntityId)

    override fun counterparty(id: UUID): CounterpartyView? = repository.findCounterparty(id)

    override fun requireCounterparty(id: UUID): CounterpartyView = counterparty(id) ?: throw NotFoundException("Counterparty", id)

    override fun counterpartyByCode(code: String): CounterpartyView? = repository.findCounterpartyByCode(code.uppercase())

    override fun counterparties(): List<CounterpartyView> = repository.allCounterparties()

    override fun registry(code: String): RegistryView? = repository.findRegistry(code.uppercase())

    override fun requireRegistry(code: String): RegistryView = registry(code) ?: throw NotFoundException("Registry", code)

    override fun registries(): List<RegistryView> = repository.allRegistries()

    override fun registryAccount(id: UUID): RegistryAccountView? = repository.findRegistryAccount(id)

    override fun requireRegistryAccount(id: UUID): RegistryAccountView = registryAccount(id) ?: throw NotFoundException("RegistryAccount", id)

    override fun registryAccounts(holderType: HolderType, holderId: UUID): List<RegistryAccountView> = repository.registryAccountsOf(holderType, holderId)

    override fun registryAccountByNumber(registryCode: String, accountNumber: String): RegistryAccountView? {
        val registry = registry(registryCode) ?: return null
        return repository.findRegistryAccountByNumber(registry.id, accountNumber)
    }

    override fun productionDevice(id: UUID): ProductionDeviceView? = repository.findProductionDevice(id)

    override fun requireProductionDevice(id: UUID): ProductionDeviceView = productionDevice(id) ?: throw NotFoundException("ProductionDevice", id)

    override fun productionDeviceByGsrn(gsrn: String): ProductionDeviceView? = repository.findProductionDeviceByGsrn(gsrn.trim())

    override fun productionDevices(ownDevicesOnly: Boolean): List<ProductionDeviceView> = repository.allProductionDevices(ownDevicesOnly)

    override fun history(table: String, id: UUID): List<HistoryEntry<Map<String, Any?>>> = repository.history(table, id)

    override fun asOf(table: String, id: UUID, knownAt: Instant): Map<String, Any?>? = repository.asOf(table, id, knownAt)

    override fun isBusinessDay(calendarCode: String, date: LocalDate): Boolean {
        val weekend = repository.weekendDays(calendarCode) ?: throw NotFoundException("Calendar", calendarCode)
        if (date.dayOfWeek.value in weekend) return false
        return date !in repository.holidays(calendarCode, date, date.plusDays(1))
    }

    override fun addBusinessDays(calendarCode: String, from: LocalDate, days: Int): LocalDate {
        require(days >= 0) { "days must be non-negative" }
        val weekend = repository.weekendDays(calendarCode) ?: throw NotFoundException("Calendar", calendarCode)
        val horizon = from.plusDays(days * 3L + 14)
        val holidays = repository.holidays(calendarCode, from, horizon)
        var date = from
        var remaining = days
        while (remaining > 0) {
            date = date.plusDays(1)
            if (date.dayOfWeek.value !in weekend && date !in holidays) remaining--
        }
        return date
    }

    // ----------------------------------------------------------------------------------------------------------
    // Commands
    // ----------------------------------------------------------------------------------------------------------

    @Transactional
    override fun createLegalEntity(command: CreateLegalEntity): LegalEntityView {
        val code = ReferenceDataPolicies.code(command.code)
        if (repository.findLegalEntityByCode(code) != null) throw ConflictException("Legal entity $code already exists")
        val entity = LegalEntityView(
            id = Ids.newId(),
            code = code,
            name = command.name.trim().also { if (it.isBlank()) throw ValidationException("name must not be blank") },
            country = ReferenceDataPolicies.country(command.country),
            lei = ReferenceDataPolicies.lei(command.lei),
            vatNumber = command.vatNumber?.trim(),
            baseCurrency = ReferenceDataPolicies.currency(command.baseCurrency),
            timeZone = ReferenceDataPolicies.timeZone(command.timeZone),
            accountingPolicy = command.accountingPolicy,
            validPeriod = ValidPeriod(command.validFrom),
            active = true,
            version = 1,
            obligationsOpenAt = command.obligationsOpenAt,
            emirClassification = command.emirClassification,
        )
        repository.insertLegalEntity(entity)
        events.publishEvent(LegalEntityChanged(entity.id))
        return entity
    }

    @Transactional
    override fun updateLegalEntity(id: UUID, command: UpdateLegalEntity): LegalEntityView {
        val current = requireLegalEntity(id)
        checkVersion("LegalEntity", id, current.version, command.expectedVersion)
        val updated = current.copy(
            name = command.name?.trim() ?: current.name,
            lei = command.lei?.let(ReferenceDataPolicies::lei) ?: current.lei,
            vatNumber = command.vatNumber ?: current.vatNumber,
            accountingPolicy = command.accountingPolicy ?: current.accountingPolicy,
            obligationsOpenAt = command.obligationsOpenAt ?: current.obligationsOpenAt,
            emirClassification = command.emirClassification ?: current.emirClassification,
            active = command.active ?: current.active,
            validPeriod = command.validTo?.let { current.validPeriod.endingAt(it) } ?: current.validPeriod,
            version = current.version + 1,
        )
        if (!repository.updateLegalEntity(updated, command.expectedVersion)) {
            throw StaleVersionException("LegalEntity", id, command.expectedVersion, current.version)
        }
        events.publishEvent(LegalEntityChanged(id))
        return updated
    }

    @Transactional
    override fun createBook(command: CreateBook): BookView {
        requireLegalEntity(command.legalEntityId)
        val code = ReferenceDataPolicies.code(command.code)
        if (repository.booksOf(command.legalEntityId).any { it.code == code }) {
            throw ConflictException("Book $code already exists in legal entity ${command.legalEntityId}")
        }
        command.parentBookId?.let { parentId ->
            val parent = requireBook(parentId)
            if (parent.legalEntityId != command.legalEntityId) throw ValidationException("Parent book belongs to another legal entity")
        }
        val book = BookView(
            id = Ids.newId(),
            legalEntityId = command.legalEntityId,
            code = code,
            name = command.name.trim(),
            bookType = command.bookType,
            parentBookId = command.parentBookId,
            strategy = command.strategy,
            active = true,
            version = 1,
        )
        repository.insertBook(book)
        return book
    }

    @Transactional
    override fun createCounterparty(command: CreateCounterparty): CounterpartyView {
        val code = ReferenceDataPolicies.code(command.code)
        if (repository.findCounterpartyByCode(code) != null) throw ConflictException("Counterparty $code already exists")
        if (command.counterpartyType == CounterpartyType.INTERCOMPANY) {
            val linked = command.linkedLegalEntityId ?: throw ValidationException("Intercompany counterparty requires linkedLegalEntityId")
            requireLegalEntity(linked)
        }
        val counterparty = CounterpartyView(
            id = Ids.newId(),
            code = code,
            name = command.name.trim(),
            country = ReferenceDataPolicies.country(command.country),
            lei = ReferenceDataPolicies.lei(command.lei),
            vatNumber = command.vatNumber?.trim(),
            counterpartyType = command.counterpartyType,
            linkedLegalEntityId = command.linkedLegalEntityId,
            masterAgreements = command.masterAgreements,
            paymentTermsDays = command.paymentTermsDays.also { if (it < 0) throw ValidationException("paymentTermsDays must be >= 0") },
            settlementCurrency = ReferenceDataPolicies.currency(command.settlementCurrency),
            creditLimit = command.creditLimit?.let { throw ValidationException(CREDIT_LIMIT_MOVED) },
            active = true,
            version = 1,
            nettingAgreed = command.nettingAgreed,
            selfBilling = command.selfBilling,
            invoiceEmail = command.invoiceEmail?.trim(),
            emirClassification = command.emirClassification,
            parentCounterpartyId = command.parentCounterpartyId?.also { requireParent(it, null) },
        )
        repository.insertCounterparty(counterparty)
        events.publishEvent(CounterpartyChanged(counterparty.id))
        return counterparty
    }

    @Transactional
    override fun updateCounterparty(id: UUID, command: UpdateCounterparty): CounterpartyView {
        val current = requireCounterparty(id)
        checkVersion("Counterparty", id, current.version, command.expectedVersion)
        val updated = current.copy(
            name = command.name?.trim() ?: current.name,
            lei = command.lei?.let(ReferenceDataPolicies::lei) ?: current.lei,
            vatNumber = command.vatNumber ?: current.vatNumber,
            masterAgreements = command.masterAgreements ?: current.masterAgreements,
            paymentTermsDays = command.paymentTermsDays ?: current.paymentTermsDays,
            creditLimit = command.creditLimit?.let { throw ValidationException(CREDIT_LIMIT_MOVED) } ?: current.creditLimit,
            active = command.active ?: current.active,
            nettingAgreed = command.nettingAgreed ?: current.nettingAgreed,
            selfBilling = command.selfBilling ?: current.selfBilling,
            invoiceEmail = command.invoiceEmail?.trim() ?: current.invoiceEmail,
            emirClassification = command.emirClassification ?: current.emirClassification,
            parentCounterpartyId = command.parentCounterpartyId?.also { requireParent(it, id) } ?: current.parentCounterpartyId,
            version = current.version + 1,
        )
        if (!repository.updateCounterparty(updated, command.expectedVersion)) {
            throw StaleVersionException("Counterparty", id, command.expectedVersion, current.version)
        }
        events.publishEvent(CounterpartyChanged(id))
        return updated
    }

    /** A group parent must exist and must not be the counterparty itself or one of its descendants. */
    private fun requireParent(parentId: UUID, childId: UUID?) {
        var cursor: UUID? = parentId
        val seen = mutableSetOf<UUID>()
        while (cursor != null) {
            if (cursor == childId || !seen.add(cursor)) throw ValidationException("Counterparty groups cannot form a cycle")
            cursor = requireCounterparty(cursor).parentCounterpartyId
        }
    }

    @Transactional
    override fun createRegistryAccount(command: CreateRegistryAccount): RegistryAccountView {
        val registry = requireRegistry(command.registryCode)
        validateAccountType(registry, command.accountType)
        when (command.holderType) {
            HolderType.LEGAL_ENTITY -> requireLegalEntity(command.holderId)
            // Counterparty accounts are where our certificates and allowances go: four-eyes only.
            HolderType.COUNTERPARTY -> throw ValidationException("Counterparty registry accounts are added through a change request")
        }
        if (repository.findRegistryAccountByNumber(registry.id, command.accountNumber.trim()) != null) {
            throw ConflictException("Account ${command.accountNumber} already exists in registry ${registry.code}")
        }
        val account = RegistryAccountView(
            id = Ids.newId(),
            registryId = registry.id,
            registryCode = registry.code,
            accountNumber = command.accountNumber.trim(),
            name = command.name.trim(),
            accountType = command.accountType,
            holderType = command.holderType,
            holderId = command.holderId,
            canImport = command.canImport,
            canExport = command.canExport,
            trusted = command.trusted,
            active = true,
            version = 1,
            defaultBookId = command.defaultBookId?.also { requireOwnBook(command.holderId, it) },
        )
        repository.insertRegistryAccount(account)
        events.publishEvent(RegistryAccountChanged(account.id))
        return account
    }

    @Transactional
    override fun assignDefaultBook(accountId: UUID, bookId: UUID?): RegistryAccountView {
        val current = requireRegistryAccount(accountId)
        if (current.holderType != HolderType.LEGAL_ENTITY) throw ValidationException("Only an entity's own accounts belong to its books")
        bookId?.let { requireOwnBook(current.holderId, it) }
        val next = current.copy(defaultBookId = bookId, version = current.version + 1)
        if (!repository.updateRegistryAccount(next, current.version)) throw StaleVersionException("RegistryAccount", accountId, current.version, next.version)
        events.publishEvent(RegistryAccountChanged(accountId))
        return next
    }

    private fun requireOwnBook(legalEntityId: UUID, bookId: UUID) {
        if (requireBook(bookId).legalEntityId != legalEntityId) throw ValidationException("Book $bookId belongs to another legal entity")
    }

    @Transactional
    override fun createProductionDevice(command: CreateProductionDevice): ProductionDeviceView {
        val gsrn = ReferenceDataPolicies.gsrn(command.gsrn)
        if (repository.findProductionDeviceByGsrn(gsrn) != null) throw ConflictException("Production device $gsrn already exists")
        taxonomy.require(TaxonomyScheme.ENERGY_SOURCE, command.energySourceCode)
        taxonomy.require(TaxonomyScheme.TECHNOLOGY, command.technologyCode)
        command.labelCodes.forEach { taxonomy.require(TaxonomyScheme.LABEL, it) }
        command.supportSchemeCodes.forEach { taxonomy.require(TaxonomyScheme.SUPPORT_SCHEME, it) }
        if (command.capacityKw.signum() <= 0) throw ValidationException("capacityKw must be positive")
        command.decommissioningDate?.let {
            if (!it.isAfter(command.commissioningDate)) throw ValidationException("decommissioningDate must follow commissioningDate")
        }
        val registryId = command.registryCode?.let { requireRegistry(it).id }
        requireIssuanceLag(command.issuanceLagDays)
        val device = ProductionDeviceView(
            id = Ids.newId(),
            gsrn = gsrn,
            name = command.name.trim(),
            country = ReferenceDataPolicies.country(command.country),
            regionCode = command.regionCode,
            latitude = command.latitude,
            longitude = command.longitude,
            capacityKw = command.capacityKw,
            commissioningDate = command.commissioningDate,
            decommissioningDate = command.decommissioningDate,
            energySourceCode = command.energySourceCode,
            technologyCode = command.technologyCode,
            supportSchemeCodes = command.supportSchemeCodes,
            labelCodes = command.labelCodes,
            registryId = registryId,
            ownerType = command.ownerType,
            ownerId = command.ownerId,
            ownDevice = command.ownDevice,
            externalCodes = command.externalCodes,
            active = true,
            version = 1,
            issuanceLagDays = command.issuanceLagDays,
        )
        repository.insertProductionDevice(device)
        events.publishEvent(ProductionDeviceChanged(device.id, device.gsrn))
        return device
    }

    @Transactional
    override fun updateProductionDevice(id: UUID, command: UpdateProductionDevice): ProductionDeviceView {
        val current = requireProductionDevice(id)
        checkVersion("ProductionDevice", id, current.version, command.expectedVersion)
        command.labelCodes?.forEach { taxonomy.require(TaxonomyScheme.LABEL, it) }
        command.supportSchemeCodes?.forEach { taxonomy.require(TaxonomyScheme.SUPPORT_SCHEME, it) }
        command.issuanceLagDays?.let(::requireIssuanceLag)
        val updated = current.copy(
            name = command.name?.trim() ?: current.name,
            capacityKw = command.capacityKw ?: current.capacityKw,
            decommissioningDate = command.decommissioningDate ?: current.decommissioningDate,
            supportSchemeCodes = command.supportSchemeCodes ?: current.supportSchemeCodes,
            labelCodes = command.labelCodes ?: current.labelCodes,
            externalCodes = command.externalCodes ?: current.externalCodes,
            active = command.active ?: current.active,
            issuanceLagDays = command.issuanceLagDays ?: current.issuanceLagDays,
            version = current.version + 1,
        )
        if (!repository.updateProductionDevice(updated, command.expectedVersion)) {
            throw StaleVersionException("ProductionDevice", id, command.expectedVersion, current.version)
        }
        events.publishEvent(ProductionDeviceChanged(id, updated.gsrn))
        return updated
    }

    private fun requireIssuanceLag(days: Int) {
        if (days < 0) throw ValidationException("issuanceLagDays must be >= 0")
    }

    override fun registryAccountByRef(ref: String): RegistryAccountView? {
        val parts = ref.split(":", limit = 2).map { it.trim() }
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        return registryAccountByNumber(parts[0].uppercase(), parts[1])
    }

    fun validateAccountType(registry: RegistryView, type: RegistryAccountType) {
        val gooTypes = setOf(RegistryAccountType.HOLDING, RegistryAccountType.PRODUCTION, RegistryAccountType.CANCELLATION_ONLY)
        val valid = when (registry.registryType) {
            RegistryType.GOO -> type in gooTypes
            RegistryType.EMISSION_ALLOWANCE -> type !in gooTypes
        }
        if (!valid) throw ValidationException("Account type $type is not valid for a ${registry.registryType} registry")
    }

    private fun checkVersion(entity: String, id: UUID, actual: Int, expected: Int) {
        if (actual != expected) throw StaleVersionException(entity, id, expected, actual)
    }

    companion object {
        private const val CREDIT_LIMIT_MOVED = "Credit limits are risk limits (CREDIT_EXPOSURE) maintained in the risk module"

        @Suppress("unused")
        private val WEEKEND = setOf(DayOfWeek.SATURDAY.value, DayOfWeek.SUNDAY.value)
    }
}
