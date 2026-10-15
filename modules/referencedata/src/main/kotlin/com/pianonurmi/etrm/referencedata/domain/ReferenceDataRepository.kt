package com.pianonurmi.etrm.referencedata.domain

import com.pianonurmi.etrm.referencedata.api.BookView
import com.pianonurmi.etrm.referencedata.api.CounterpartyView
import com.pianonurmi.etrm.referencedata.api.HistoryEntry
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceView
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryView
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Persistence port for master data. Master records are plain immutable views; business rules live in
 * [ReferenceDataPolicies] and the application service, which keeps the CRUD-heavy module small.
 */
@Suppress("TooManyFunctions") // one read and one write per master data table
interface ReferenceDataRepository {
    fun findLegalEntity(id: UUID): LegalEntityView?

    fun findLegalEntityByCode(code: String): LegalEntityView?

    fun allLegalEntities(): List<LegalEntityView>

    fun insertLegalEntity(entity: LegalEntityView)

    fun updateLegalEntity(entity: LegalEntityView, expectedVersion: Int): Boolean

    fun findBook(id: UUID): BookView?

    fun booksOf(legalEntityId: UUID): List<BookView>

    fun insertBook(book: BookView)

    fun findCounterparty(id: UUID): CounterpartyView?

    fun findCounterpartyByCode(code: String): CounterpartyView?

    fun allCounterparties(): List<CounterpartyView>

    fun insertCounterparty(counterparty: CounterpartyView)

    fun updateCounterparty(counterparty: CounterpartyView, expectedVersion: Int): Boolean

    fun findRegistry(code: String): RegistryView?

    fun findRegistryById(id: UUID): RegistryView?

    fun allRegistries(): List<RegistryView>

    fun updateRegistry(registry: RegistryView, expectedVersion: Int): Boolean

    fun findRegistryAccount(id: UUID): RegistryAccountView?

    fun findRegistryAccountByNumber(registryId: UUID, accountNumber: String): RegistryAccountView?

    fun registryAccountsOf(holderType: HolderType, holderId: UUID): List<RegistryAccountView>

    fun insertRegistryAccount(account: RegistryAccountView)

    fun updateRegistryAccount(account: RegistryAccountView, expectedVersion: Int): Boolean

    fun findProductionDevice(id: UUID): ProductionDeviceView?

    fun findProductionDeviceByGsrn(gsrn: String): ProductionDeviceView?

    fun allProductionDevices(ownDevicesOnly: Boolean): List<ProductionDeviceView>

    fun insertProductionDevice(device: ProductionDeviceView)

    fun updateProductionDevice(device: ProductionDeviceView, expectedVersion: Int): Boolean

    fun history(table: String, id: UUID): List<HistoryEntry<Map<String, Any?>>>

    fun asOf(table: String, id: UUID, knownAt: Instant): Map<String, Any?>?

    fun holidays(calendarCode: String, from: LocalDate, to: LocalDate): Set<LocalDate>

    fun weekendDays(calendarCode: String): Set<Int>?
}
