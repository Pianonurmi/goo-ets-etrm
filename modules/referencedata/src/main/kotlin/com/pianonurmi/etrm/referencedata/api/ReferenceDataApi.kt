package com.pianonurmi.etrm.referencedata.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ReferenceDataQuery {
    fun legalEntity(id: UUID): LegalEntityView?

    fun requireLegalEntity(id: UUID): LegalEntityView

    fun legalEntityByCode(code: String): LegalEntityView?

    fun legalEntities(): List<LegalEntityView>

    fun book(id: UUID): BookView?

    fun requireBook(id: UUID): BookView

    fun books(legalEntityId: UUID): List<BookView>

    fun counterparty(id: UUID): CounterpartyView?

    fun requireCounterparty(id: UUID): CounterpartyView

    fun counterpartyByCode(code: String): CounterpartyView?

    fun counterparties(): List<CounterpartyView>

    fun registry(code: String): RegistryView?

    fun requireRegistry(code: String): RegistryView

    fun registries(): List<RegistryView>

    fun registryAccount(id: UUID): RegistryAccountView?

    fun requireRegistryAccount(id: UUID): RegistryAccountView

    fun registryAccounts(holderType: HolderType, holderId: UUID): List<RegistryAccountView>

    fun registryAccountByNumber(registryCode: String, accountNumber: String): RegistryAccountView?

    fun productionDevice(id: UUID): ProductionDeviceView?

    fun requireProductionDevice(id: UUID): ProductionDeviceView

    fun productionDeviceByGsrn(gsrn: String): ProductionDeviceView?

    fun productionDevices(ownDevicesOnly: Boolean = false): List<ProductionDeviceView>

    /** Transaction-time history of any master record. */
    fun history(table: String, id: UUID): List<HistoryEntry<Map<String, Any?>>>

    /** The record as the system knew it at [knownAt]. */
    fun asOf(table: String, id: UUID, knownAt: Instant): Map<String, Any?>?

    fun isBusinessDay(calendarCode: String, date: LocalDate): Boolean

    fun addBusinessDays(calendarCode: String, from: LocalDate, days: Int): LocalDate
}

interface ReferenceDataCommand {
    fun createLegalEntity(command: CreateLegalEntity): LegalEntityView

    fun updateLegalEntity(id: UUID, command: UpdateLegalEntity): LegalEntityView

    fun createBook(command: CreateBook): BookView

    fun createCounterparty(command: CreateCounterparty): CounterpartyView

    fun updateCounterparty(id: UUID, command: UpdateCounterparty): CounterpartyView

    fun createRegistryAccount(command: CreateRegistryAccount): RegistryAccountView

    fun createProductionDevice(command: CreateProductionDevice): ProductionDeviceView

    fun updateProductionDevice(id: UUID, command: UpdateProductionDevice): ProductionDeviceView
}
