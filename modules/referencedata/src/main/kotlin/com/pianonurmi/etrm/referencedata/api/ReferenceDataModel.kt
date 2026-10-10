package com.pianonurmi.etrm.referencedata.api

import com.pianonurmi.etrm.platform.bitemporal.ValidPeriod
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

enum class GooCostingMethod { SPECIFIC_IDENTIFICATION, FIFO, WEIGHTED_AVERAGE }

enum class AllowanceCostingMethod { FIFO, WEIGHTED_AVERAGE }

enum class AllowanceClassification { INTANGIBLE_ASSET, INVENTORY }

enum class FreeAllocationPolicy { NOMINAL, FAIR_VALUE }

/** Accounting policies elected per legal entity; the ledger's posting rules and costing engine read these. */
data class AccountingPolicy(
    val gooCostingMethod: GooCostingMethod = GooCostingMethod.SPECIFIC_IDENTIFICATION,
    val allowanceCostingMethod: AllowanceCostingMethod = AllowanceCostingMethod.FIFO,
    val allowanceClassification: AllowanceClassification = AllowanceClassification.INTANGIBLE_ASSET,
    val freeAllocationPolicy: FreeAllocationPolicy = FreeAllocationPolicy.NOMINAL,
)

/** The trade status from which a legal entity's trades create delivery obligations. */
enum class ObligationsOpenAt { APPROVED, CONFIRMED }

data class LegalEntityView(
    val id: UUID,
    val code: String,
    val name: String,
    val country: String,
    val lei: String?,
    val vatNumber: String?,
    val baseCurrency: Currency,
    val timeZone: String,
    val accountingPolicy: AccountingPolicy,
    val validPeriod: ValidPeriod,
    val active: Boolean,
    val version: Int,
    val obligationsOpenAt: ObligationsOpenAt = ObligationsOpenAt.CONFIRMED,
)

enum class BookType { TRADING, OWN_USE, HEDGING, COMPLIANCE }

data class BookView(
    val id: UUID,
    val legalEntityId: UUID,
    val code: String,
    val name: String,
    val bookType: BookType,
    val parentBookId: UUID?,
    val strategy: String?,
    val active: Boolean,
    val version: Int,
)

enum class CounterpartyType { EXTERNAL, INTERCOMPANY, BROKER, EXCHANGE, CLEARING_HOUSE }

data class MasterAgreement(val type: String, val reference: String, val signedOn: LocalDate? = null)

data class CounterpartyView(
    val id: UUID,
    val code: String,
    val name: String,
    val country: String,
    val lei: String?,
    val vatNumber: String?,
    val counterpartyType: CounterpartyType,
    val linkedLegalEntityId: UUID?,
    val masterAgreements: List<MasterAgreement>,
    val paymentTermsDays: Int,
    val settlementCurrency: Currency,
    val creditLimit: BigDecimal?,
    val active: Boolean,
    val version: Int,
)

enum class RegistryType { GOO, EMISSION_ALLOWANCE }

enum class RegistryIntegration { MANUAL, FILE, API, SIMULATOR }

data class RegistryView(
    val id: UUID,
    val code: String,
    val name: String,
    val registryType: RegistryType,
    val country: String?,
    val hubMember: Boolean,
    val operator: String?,
    val integration: RegistryIntegration,
    val active: Boolean,
    val version: Int,
)

enum class RegistryAccountType {
    HOLDING,
    PRODUCTION,
    CANCELLATION_ONLY,
    OPERATOR_HOLDING,
    AIRCRAFT_OPERATOR_HOLDING,
    TRADING,
    PERSON_HOLDING,
}

enum class HolderType { LEGAL_ENTITY, COUNTERPARTY }

data class RegistryAccountView(
    val id: UUID,
    val registryId: UUID,
    val registryCode: String,
    val accountNumber: String,
    val name: String,
    val accountType: RegistryAccountType,
    val holderType: HolderType,
    val holderId: UUID,
    val canImport: Boolean,
    val canExport: Boolean,
    val trusted: Boolean,
    val active: Boolean,
    val version: Int,
    /** First day the account counts as trusted (Union Registry trusted account list activation). */
    val trustedSince: java.time.LocalDate? = null,
) {
    fun trustedOn(date: java.time.LocalDate): Boolean = trusted && trustedSince != null && !date.isBefore(trustedSince)
}

enum class OwnerType { LEGAL_ENTITY, COUNTERPARTY, THIRD_PARTY }

data class ProductionDeviceView(
    val id: UUID,
    val gsrn: String,
    val name: String,
    val country: String,
    val regionCode: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val capacityKw: BigDecimal,
    val commissioningDate: LocalDate,
    val decommissioningDate: LocalDate?,
    val energySourceCode: String,
    val technologyCode: String,
    val supportSchemeCodes: Set<String>,
    val labelCodes: Set<String>,
    val registryId: UUID?,
    val ownerType: OwnerType,
    val ownerId: UUID?,
    val ownDevice: Boolean,
    val externalCodes: Map<String, String>,
    val active: Boolean,
    val version: Int,
)

data class HistoryEntry<T>(val version: Int, val recordedFrom: java.time.Instant, val recordedTo: java.time.Instant?, val actor: String?, val data: T)

// --------------------------------------------------------------------------------------------------------------
// Commands
// --------------------------------------------------------------------------------------------------------------

data class CreateLegalEntity(
    val code: String,
    val name: String,
    val country: String,
    val lei: String? = null,
    val vatNumber: String? = null,
    val baseCurrency: String = "EUR",
    val timeZone: String = "Europe/Helsinki",
    val accountingPolicy: AccountingPolicy = AccountingPolicy(),
    val validFrom: LocalDate,
    val obligationsOpenAt: ObligationsOpenAt = ObligationsOpenAt.CONFIRMED,
)

data class UpdateLegalEntity(
    val name: String? = null,
    val lei: String? = null,
    val vatNumber: String? = null,
    val accountingPolicy: AccountingPolicy? = null,
    val active: Boolean? = null,
    val validTo: LocalDate? = null,
    val expectedVersion: Int,
    val obligationsOpenAt: ObligationsOpenAt? = null,
)

data class CreateBook(
    val legalEntityId: UUID,
    val code: String,
    val name: String,
    val bookType: BookType,
    val parentBookId: UUID? = null,
    val strategy: String? = null,
)

data class CreateCounterparty(
    val code: String,
    val name: String,
    val country: String,
    val lei: String? = null,
    val vatNumber: String? = null,
    val counterpartyType: CounterpartyType = CounterpartyType.EXTERNAL,
    val linkedLegalEntityId: UUID? = null,
    val masterAgreements: List<MasterAgreement> = emptyList(),
    val paymentTermsDays: Int = 14,
    val settlementCurrency: String = "EUR",
    val creditLimit: BigDecimal? = null,
)

data class UpdateCounterparty(
    val name: String? = null,
    val lei: String? = null,
    val vatNumber: String? = null,
    val masterAgreements: List<MasterAgreement>? = null,
    val paymentTermsDays: Int? = null,
    val creditLimit: BigDecimal? = null,
    val active: Boolean? = null,
    val expectedVersion: Int,
)

data class CreateRegistryAccount(
    val registryCode: String,
    val accountNumber: String,
    val name: String,
    val accountType: RegistryAccountType,
    val holderType: HolderType,
    val holderId: UUID,
    val canImport: Boolean = true,
    val canExport: Boolean = true,
    val trusted: Boolean = false,
)

data class CreateProductionDevice(
    val gsrn: String,
    val name: String,
    val country: String,
    val regionCode: String? = null,
    val latitude: BigDecimal? = null,
    val longitude: BigDecimal? = null,
    val capacityKw: BigDecimal,
    val commissioningDate: LocalDate,
    val decommissioningDate: LocalDate? = null,
    val energySourceCode: String,
    val technologyCode: String,
    val supportSchemeCodes: Set<String> = emptySet(),
    val labelCodes: Set<String> = emptySet(),
    val registryCode: String? = null,
    val ownerType: OwnerType,
    val ownerId: UUID? = null,
    val ownDevice: Boolean = false,
    val externalCodes: Map<String, String> = emptyMap(),
)

data class UpdateProductionDevice(
    val name: String? = null,
    val capacityKw: BigDecimal? = null,
    val decommissioningDate: LocalDate? = null,
    val supportSchemeCodes: Set<String>? = null,
    val labelCodes: Set<String>? = null,
    val externalCodes: Map<String, String>? = null,
    val active: Boolean? = null,
    val expectedVersion: Int,
)

// --------------------------------------------------------------------------------------------------------------
// Events
// --------------------------------------------------------------------------------------------------------------

data class LegalEntityChanged(val legalEntityId: UUID, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class CounterpartyChanged(val counterpartyId: UUID, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class RegistryAccountChanged(val registryAccountId: UUID, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/** Inventory listens to this to re-sync denormalised device attributes on certificate lots. */
data class ProductionDeviceChanged(val productionDeviceId: UUID, val gsrn: String, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata
