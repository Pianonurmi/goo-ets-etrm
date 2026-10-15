package com.pianonurmi.etrm.referencedata.adapter.jooq

import com.pianonurmi.etrm.platform.bitemporal.ValidPeriod
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.Book.BOOK
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.Calendar.CALENDAR
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.CalendarHoliday.CALENDAR_HOLIDAY
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.Counterparty.COUNTERPARTY
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.LegalEntity.LEGAL_ENTITY
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.ProductionDevice.PRODUCTION_DEVICE
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.ReferenceHistory.REFERENCE_HISTORY
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.Registry.REGISTRY
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.RegistryAccount.REGISTRY_ACCOUNT
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.BookRecord
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.CounterpartyRecord
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.LegalEntityRecord
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.ProductionDeviceRecord
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.RegistryRecord
import com.pianonurmi.etrm.referencedata.api.AccountingPolicy
import com.pianonurmi.etrm.referencedata.api.AllowanceClassification
import com.pianonurmi.etrm.referencedata.api.AllowanceCostingMethod
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.BookView
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CounterpartyView
import com.pianonurmi.etrm.referencedata.api.EmirClassification
import com.pianonurmi.etrm.referencedata.api.FreeAllocationPolicy
import com.pianonurmi.etrm.referencedata.api.GooCostingMethod
import com.pianonurmi.etrm.referencedata.api.HistoryEntry
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.MasterAgreement
import com.pianonurmi.etrm.referencedata.api.ObligationsOpenAt
import com.pianonurmi.etrm.referencedata.api.OwnerType
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceView
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryIntegration
import com.pianonurmi.etrm.referencedata.api.RegistryType
import com.pianonurmi.etrm.referencedata.api.RegistryView
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // one query per master data table, mirroring ReferenceDataRepository
class JooqReferenceDataRepository(private val dsl: DSLContext) : ReferenceDataRepository {
    // ----------------------------------------------------------------------------------------------------------
    // Legal entities
    // ----------------------------------------------------------------------------------------------------------

    override fun findLegalEntity(id: UUID): LegalEntityView? =
        dsl.selectFrom(LEGAL_ENTITY).where(LEGAL_ENTITY.ID.eq(id)).fetchOne()?.let(::toLegalEntity)

    override fun findLegalEntityByCode(code: String): LegalEntityView? =
        dsl.selectFrom(LEGAL_ENTITY).where(LEGAL_ENTITY.CODE.eq(code)).fetchOne()?.let(::toLegalEntity)

    override fun allLegalEntities(): List<LegalEntityView> = dsl.selectFrom(LEGAL_ENTITY).orderBy(LEGAL_ENTITY.CODE).fetch(::toLegalEntity)

    override fun insertLegalEntity(entity: LegalEntityView) {
        dsl.insertInto(LEGAL_ENTITY)
            .set(LEGAL_ENTITY.ID, entity.id)
            .set(LEGAL_ENTITY.CODE, entity.code)
            .set(legalEntityValues(entity))
            .set(LEGAL_ENTITY.COUNTRY, entity.country)
            .set(LEGAL_ENTITY.BASE_CURRENCY, entity.baseCurrency.currencyCode)
            .set(LEGAL_ENTITY.TIME_ZONE, entity.timeZone)
            .set(LEGAL_ENTITY.VALID_FROM, entity.validPeriod.validFrom)
            .set(LEGAL_ENTITY.VERSION, 1)
            .execute()
    }

    override fun updateLegalEntity(entity: LegalEntityView, expectedVersion: Int): Boolean = dsl.update(LEGAL_ENTITY)
        .set(legalEntityValues(entity))
        .set(LEGAL_ENTITY.VERSION, entity.version)
        .set(LEGAL_ENTITY.UPDATED_AT, DSL.currentInstant())
        .where(LEGAL_ENTITY.ID.eq(entity.id), LEGAL_ENTITY.VERSION.eq(expectedVersion))
        .execute() == 1

    private fun legalEntityValues(entity: LegalEntityView): LegalEntityRecord = LegalEntityRecord().apply {
        name = entity.name
        lei = entity.lei
        vatNumber = entity.vatNumber
        gooCostingMethod = entity.accountingPolicy.gooCostingMethod.name
        allowanceCostingMethod = entity.accountingPolicy.allowanceCostingMethod.name
        allowanceClassification = entity.accountingPolicy.allowanceClassification.name
        freeAllocationPolicy = entity.accountingPolicy.freeAllocationPolicy.name
        obligationsOpenAt = entity.obligationsOpenAt.name
        emirClassification = entity.emirClassification?.name
        validTo = entity.validPeriod.validTo
        active = entity.active
    }

    private fun toLegalEntity(r: LegalEntityRecord) = LegalEntityView(
        id = r.id,
        code = r.code,
        name = r.name,
        country = r.country,
        lei = r.lei,
        vatNumber = r.vatNumber,
        baseCurrency = Currency.getInstance(r.baseCurrency),
        timeZone = r.timeZone,
        accountingPolicy = AccountingPolicy(
            GooCostingMethod.valueOf(r.gooCostingMethod),
            AllowanceCostingMethod.valueOf(r.allowanceCostingMethod),
            AllowanceClassification.valueOf(r.allowanceClassification),
            FreeAllocationPolicy.valueOf(r.freeAllocationPolicy),
        ),
        validPeriod = ValidPeriod(r.validFrom, r.validTo),
        active = r.active,
        version = r.version,
        obligationsOpenAt = ObligationsOpenAt.valueOf(r.obligationsOpenAt),
        emirClassification = r.emirClassification?.let(EmirClassification::valueOf),
    )

    // ----------------------------------------------------------------------------------------------------------
    // Books
    // ----------------------------------------------------------------------------------------------------------

    override fun findBook(id: UUID): BookView? = dsl.selectFrom(BOOK).where(BOOK.ID.eq(id)).fetchOne()?.let(::toBook)

    override fun booksOf(legalEntityId: UUID): List<BookView> =
        dsl.selectFrom(BOOK).where(BOOK.LEGAL_ENTITY_ID.eq(legalEntityId)).orderBy(BOOK.CODE).fetch(::toBook)

    override fun insertBook(book: BookView) {
        dsl.insertInto(BOOK)
            .set(BOOK.ID, book.id)
            .set(BOOK.LEGAL_ENTITY_ID, book.legalEntityId)
            .set(BOOK.CODE, book.code)
            .set(BOOK.NAME, book.name)
            .set(BOOK.BOOK_TYPE, book.bookType.name)
            .set(BOOK.PARENT_BOOK_ID, book.parentBookId)
            .set(BOOK.STRATEGY, book.strategy)
            .execute()
    }

    private fun toBook(r: BookRecord) = BookView(
        r.id,
        r.legalEntityId,
        r.code,
        r.name,
        BookType.valueOf(r.bookType),
        r.parentBookId,
        r.strategy,
        r.active,
        r.version,
    )

    // ----------------------------------------------------------------------------------------------------------
    // Counterparties
    // ----------------------------------------------------------------------------------------------------------

    override fun findCounterparty(id: UUID): CounterpartyView? =
        dsl.selectFrom(COUNTERPARTY).where(COUNTERPARTY.ID.eq(id)).fetchOne()?.let(::toCounterparty)

    override fun findCounterpartyByCode(code: String): CounterpartyView? =
        dsl.selectFrom(COUNTERPARTY).where(COUNTERPARTY.CODE.eq(code)).fetchOne()?.let(::toCounterparty)

    override fun allCounterparties(): List<CounterpartyView> = dsl.selectFrom(COUNTERPARTY).orderBy(COUNTERPARTY.CODE).fetch(::toCounterparty)

    override fun insertCounterparty(counterparty: CounterpartyView) {
        dsl.insertInto(COUNTERPARTY)
            .set(COUNTERPARTY.ID, counterparty.id)
            .set(COUNTERPARTY.CODE, counterparty.code)
            .set(COUNTERPARTY.COUNTRY, counterparty.country)
            .set(COUNTERPARTY.COUNTERPARTY_TYPE, counterparty.counterpartyType.name)
            .set(COUNTERPARTY.LINKED_LEGAL_ENTITY_ID, counterparty.linkedLegalEntityId)
            .set(COUNTERPARTY.SETTLEMENT_CURRENCY, counterparty.settlementCurrency.currencyCode)
            .set(counterpartyValues(counterparty))
            .execute()
    }

    override fun updateCounterparty(counterparty: CounterpartyView, expectedVersion: Int): Boolean = dsl.update(COUNTERPARTY)
        .set(counterpartyValues(counterparty))
        .set(COUNTERPARTY.VERSION, counterparty.version)
        .set(COUNTERPARTY.UPDATED_AT, DSL.currentInstant())
        .where(COUNTERPARTY.ID.eq(counterparty.id), COUNTERPARTY.VERSION.eq(expectedVersion))
        .execute() == 1

    private fun counterpartyValues(c: CounterpartyView): CounterpartyRecord = CounterpartyRecord().apply {
        name = c.name
        lei = c.lei
        vatNumber = c.vatNumber
        masterAgreements = JSONB.jsonb(Json.mapper.writeValueAsString(c.masterAgreements))
        paymentTermsDays = c.paymentTermsDays
        creditLimitAmount = c.creditLimit
        active = c.active
        nettingAgreed = c.nettingAgreed
        selfBilling = c.selfBilling
        invoiceEmail = c.invoiceEmail
        emirClassification = c.emirClassification?.name
        parentCounterpartyId = c.parentCounterpartyId
    }

    private fun toCounterparty(r: CounterpartyRecord) = CounterpartyView(
        id = r.id,
        code = r.code,
        name = r.name,
        country = r.country,
        lei = r.lei,
        vatNumber = r.vatNumber,
        counterpartyType = CounterpartyType.valueOf(r.counterpartyType),
        linkedLegalEntityId = r.linkedLegalEntityId,
        masterAgreements = Json.mapper.readValue(r.masterAgreements.data(), object : TypeReference<List<MasterAgreement>>() {}),
        paymentTermsDays = r.paymentTermsDays,
        settlementCurrency = Currency.getInstance(r.settlementCurrency),
        creditLimit = r.creditLimitAmount,
        active = r.active,
        version = r.version,
        nettingAgreed = r.nettingAgreed,
        selfBilling = r.selfBilling,
        invoiceEmail = r.invoiceEmail,
        emirClassification = r.emirClassification?.let(EmirClassification::valueOf),
        parentCounterpartyId = r.parentCounterpartyId,
    )

    // ----------------------------------------------------------------------------------------------------------
    // Registries and accounts
    // ----------------------------------------------------------------------------------------------------------

    override fun findRegistry(code: String): RegistryView? = dsl.selectFrom(REGISTRY).where(REGISTRY.CODE.eq(code)).fetchOne()?.let(::toRegistry)

    override fun findRegistryById(id: UUID): RegistryView? = dsl.selectFrom(REGISTRY).where(REGISTRY.ID.eq(id)).fetchOne()?.let(::toRegistry)

    override fun allRegistries(): List<RegistryView> = dsl.selectFrom(REGISTRY).orderBy(REGISTRY.CODE).fetch(::toRegistry)

    override fun updateRegistry(registry: RegistryView, expectedVersion: Int): Boolean = dsl.update(REGISTRY)
        .set(REGISTRY.INTEGRATION, registry.integration.name)
        .set(REGISTRY.ACTIVE, registry.active)
        .set(REGISTRY.VERSION, registry.version)
        .set(REGISTRY.UPDATED_AT, DSL.currentInstant())
        .where(REGISTRY.ID.eq(registry.id), REGISTRY.VERSION.eq(expectedVersion))
        .execute() == 1

    private fun toRegistry(r: RegistryRecord) = RegistryView(
        r.id,
        r.code,
        r.name,
        RegistryType.valueOf(r.registryType),
        r.country,
        r.hubMember,
        r.operator,
        RegistryIntegration.valueOf(r.integration),
        r.active,
        r.version,
    )

    private fun accountSelect() = dsl.select(REGISTRY_ACCOUNT.asterisk(), REGISTRY.CODE.`as`("registry_code"))
        .from(REGISTRY_ACCOUNT).join(REGISTRY).on(REGISTRY.ID.eq(REGISTRY_ACCOUNT.REGISTRY_ID))

    override fun findRegistryAccount(id: UUID): RegistryAccountView? = accountSelect().where(REGISTRY_ACCOUNT.ID.eq(id)).fetchOne()?.let(::toAccount)

    override fun findRegistryAccountByNumber(registryId: UUID, accountNumber: String): RegistryAccountView? = accountSelect()
        .where(REGISTRY_ACCOUNT.REGISTRY_ID.eq(registryId), REGISTRY_ACCOUNT.ACCOUNT_NUMBER.eq(accountNumber))
        .fetchOne()?.let(::toAccount)

    override fun registryAccountsOf(holderType: HolderType, holderId: UUID): List<RegistryAccountView> = accountSelect()
        .where(REGISTRY_ACCOUNT.HOLDER_TYPE.eq(holderType.name), REGISTRY_ACCOUNT.HOLDER_ID.eq(holderId))
        .orderBy(REGISTRY.CODE, REGISTRY_ACCOUNT.ACCOUNT_NUMBER)
        .fetch(::toAccount)

    override fun insertRegistryAccount(account: RegistryAccountView) {
        dsl.insertInto(REGISTRY_ACCOUNT)
            .set(REGISTRY_ACCOUNT.ID, account.id)
            .set(REGISTRY_ACCOUNT.REGISTRY_ID, account.registryId)
            .set(REGISTRY_ACCOUNT.ACCOUNT_NUMBER, account.accountNumber)
            .set(REGISTRY_ACCOUNT.NAME, account.name)
            .set(REGISTRY_ACCOUNT.ACCOUNT_TYPE, account.accountType.name)
            .set(REGISTRY_ACCOUNT.HOLDER_TYPE, account.holderType.name)
            .set(REGISTRY_ACCOUNT.HOLDER_ID, account.holderId)
            .set(REGISTRY_ACCOUNT.CAN_IMPORT, account.canImport)
            .set(REGISTRY_ACCOUNT.CAN_EXPORT, account.canExport)
            .set(REGISTRY_ACCOUNT.TRUSTED, account.trusted)
            .set(REGISTRY_ACCOUNT.TRUSTED_SINCE, account.trustedSince)
            .set(REGISTRY_ACCOUNT.ACTIVE, account.active)
            .set(REGISTRY_ACCOUNT.DEFAULT_BOOK_ID, account.defaultBookId)
            .execute()
    }

    override fun updateRegistryAccount(account: RegistryAccountView, expectedVersion: Int): Boolean = dsl.update(REGISTRY_ACCOUNT)
        .set(REGISTRY_ACCOUNT.NAME, account.name)
        .set(REGISTRY_ACCOUNT.CAN_IMPORT, account.canImport)
        .set(REGISTRY_ACCOUNT.CAN_EXPORT, account.canExport)
        .set(REGISTRY_ACCOUNT.TRUSTED, account.trusted)
        .set(REGISTRY_ACCOUNT.TRUSTED_SINCE, account.trustedSince)
        .set(REGISTRY_ACCOUNT.ACTIVE, account.active)
        .set(REGISTRY_ACCOUNT.DEFAULT_BOOK_ID, account.defaultBookId)
        .set(REGISTRY_ACCOUNT.VERSION, account.version)
        .set(REGISTRY_ACCOUNT.UPDATED_AT, DSL.currentInstant())
        .where(REGISTRY_ACCOUNT.ID.eq(account.id), REGISTRY_ACCOUNT.VERSION.eq(expectedVersion))
        .execute() == 1

    private fun toAccount(r: Record): RegistryAccountView {
        val a = r.into(REGISTRY_ACCOUNT)
        return RegistryAccountView(
            id = a.id,
            registryId = a.registryId,
            registryCode = r.get("registry_code", String::class.java),
            accountNumber = a.accountNumber,
            name = a.name,
            accountType = RegistryAccountType.valueOf(a.accountType),
            holderType = HolderType.valueOf(a.holderType),
            holderId = a.holderId,
            canImport = a.canImport,
            canExport = a.canExport,
            trusted = a.trusted,
            active = a.active,
            version = a.version,
            trustedSince = a.trustedSince,
            defaultBookId = a.defaultBookId,
        )
    }

    // ----------------------------------------------------------------------------------------------------------
    // Production devices
    // ----------------------------------------------------------------------------------------------------------

    override fun findProductionDevice(id: UUID): ProductionDeviceView? =
        dsl.selectFrom(PRODUCTION_DEVICE).where(PRODUCTION_DEVICE.ID.eq(id)).fetchOne()?.let(::toDevice)

    override fun findProductionDeviceByGsrn(gsrn: String): ProductionDeviceView? =
        dsl.selectFrom(PRODUCTION_DEVICE).where(PRODUCTION_DEVICE.GSRN.eq(gsrn)).fetchOne()?.let(::toDevice)

    override fun allProductionDevices(ownDevicesOnly: Boolean): List<ProductionDeviceView> = dsl.selectFrom(PRODUCTION_DEVICE)
        .where(if (ownDevicesOnly) PRODUCTION_DEVICE.OWN_DEVICE.isTrue else DSL.noCondition())
        .orderBy(PRODUCTION_DEVICE.GSRN)
        .fetch(::toDevice)

    override fun insertProductionDevice(device: ProductionDeviceView) {
        dsl.insertInto(PRODUCTION_DEVICE)
            .set(PRODUCTION_DEVICE.ID, device.id)
            .set(PRODUCTION_DEVICE.GSRN, device.gsrn)
            .set(PRODUCTION_DEVICE.COUNTRY, device.country)
            .set(PRODUCTION_DEVICE.REGION_CODE, device.regionCode)
            .set(PRODUCTION_DEVICE.LATITUDE, device.latitude)
            .set(PRODUCTION_DEVICE.LONGITUDE, device.longitude)
            .set(PRODUCTION_DEVICE.COMMISSIONING_DATE, device.commissioningDate)
            .set(PRODUCTION_DEVICE.ENERGY_SOURCE_CODE, device.energySourceCode)
            .set(PRODUCTION_DEVICE.TECHNOLOGY_CODE, device.technologyCode)
            .set(PRODUCTION_DEVICE.REGISTRY_ID, device.registryId)
            .set(PRODUCTION_DEVICE.OWNER_TYPE, device.ownerType.name)
            .set(PRODUCTION_DEVICE.OWNER_ID, device.ownerId)
            .set(PRODUCTION_DEVICE.OWN_DEVICE, device.ownDevice)
            .set(deviceValues(device))
            .execute()
    }

    override fun updateProductionDevice(device: ProductionDeviceView, expectedVersion: Int): Boolean = dsl.update(PRODUCTION_DEVICE)
        .set(deviceValues(device))
        .set(PRODUCTION_DEVICE.VERSION, device.version)
        .set(PRODUCTION_DEVICE.UPDATED_AT, DSL.currentInstant())
        .where(PRODUCTION_DEVICE.ID.eq(device.id), PRODUCTION_DEVICE.VERSION.eq(expectedVersion))
        .execute() == 1

    private fun deviceValues(d: ProductionDeviceView): ProductionDeviceRecord = ProductionDeviceRecord().apply {
        name = d.name
        capacityKw = d.capacityKw
        decommissioningDate = d.decommissioningDate
        supportSchemeCodes = d.supportSchemeCodes.sorted().toTypedArray()
        labelCodes = d.labelCodes.sorted().toTypedArray()
        externalCodes = JSONB.jsonb(Json.mapper.writeValueAsString(d.externalCodes))
        active = d.active
        issuanceLagDays = d.issuanceLagDays
    }

    private fun toDevice(r: ProductionDeviceRecord) = ProductionDeviceView(
        id = r.id,
        gsrn = r.gsrn,
        name = r.name,
        country = r.country,
        regionCode = r.regionCode,
        latitude = r.latitude,
        longitude = r.longitude,
        capacityKw = r.capacityKw,
        commissioningDate = r.commissioningDate,
        decommissioningDate = r.decommissioningDate,
        energySourceCode = r.energySourceCode,
        technologyCode = r.technologyCode,
        supportSchemeCodes = r.supportSchemeCodes.filterNotNull().toSet(),
        labelCodes = r.labelCodes.filterNotNull().toSet(),
        registryId = r.registryId,
        ownerType = OwnerType.valueOf(r.ownerType),
        ownerId = r.ownerId,
        ownDevice = r.ownDevice,
        externalCodes = Json.mapper.readValue(r.externalCodes.data(), object : TypeReference<Map<String, String>>() {}),
        active = r.active,
        version = r.version,
        issuanceLagDays = r.issuanceLagDays,
    )

    // ----------------------------------------------------------------------------------------------------------
    // History and calendars
    // ----------------------------------------------------------------------------------------------------------

    override fun history(table: String, id: UUID): List<HistoryEntry<Map<String, Any?>>> = dsl.selectFrom(REFERENCE_HISTORY)
        .where(REFERENCE_HISTORY.TABLE_NAME.eq(table), REFERENCE_HISTORY.ENTITY_ID.eq(id))
        .orderBy(REFERENCE_HISTORY.VERSION)
        .fetch { r ->
            val period = r.sysPeriod
            HistoryEntry(
                version = r.version,
                recordedFrom = period.lower()!!.toInstant(),
                recordedTo = period.upper()?.toInstant(),
                actor = r.actor,
                data = Json.mapper.readValue(r.data.data(), object : TypeReference<Map<String, Any?>>() {}),
            )
        }

    override fun asOf(table: String, id: UUID, knownAt: Instant): Map<String, Any?>? = dsl.select(REFERENCE_HISTORY.DATA)
        .from(REFERENCE_HISTORY)
        .where(REFERENCE_HISTORY.TABLE_NAME.eq(table), REFERENCE_HISTORY.ENTITY_ID.eq(id))
        .and(DSL.condition("{0} @> {1}::timestamptz", REFERENCE_HISTORY.SYS_PERIOD, DSL.inline(knownAt.toString())))
        .fetchOne(REFERENCE_HISTORY.DATA)
        ?.let { Json.mapper.readValue(it.data(), object : TypeReference<Map<String, Any?>>() {}) }

    override fun holidays(calendarCode: String, from: LocalDate, to: LocalDate): Set<LocalDate> = dsl.select(CALENDAR_HOLIDAY.HOLIDAY)
        .from(CALENDAR_HOLIDAY)
        .where(CALENDAR_HOLIDAY.CALENDAR_CODE.eq(calendarCode), CALENDAR_HOLIDAY.HOLIDAY.between(from, to))
        .fetchSet(CALENDAR_HOLIDAY.HOLIDAY)

    override fun weekendDays(calendarCode: String): Set<Int>? = dsl.select(CALENDAR.WEEKEND_DAYS)
        .from(CALENDAR)
        .where(CALENDAR.CODE.eq(calendarCode))
        .fetchOne(CALENDAR.WEEKEND_DAYS)
        ?.filterNotNull()?.toSet()
}
