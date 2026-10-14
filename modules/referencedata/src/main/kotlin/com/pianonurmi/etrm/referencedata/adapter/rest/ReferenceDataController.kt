package com.pianonurmi.etrm.referencedata.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.BookView
import com.pianonurmi.etrm.referencedata.api.CounterpartyView
import com.pianonurmi.etrm.referencedata.api.CreateBook
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.api.CreateProductionDevice
import com.pianonurmi.etrm.referencedata.api.CreateRegistryAccount
import com.pianonurmi.etrm.referencedata.api.HistoryEntry
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataCommand
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryView
import com.pianonurmi.etrm.referencedata.api.UpdateCounterparty
import com.pianonurmi.etrm.referencedata.api.UpdateLegalEntity
import com.pianonurmi.etrm.referencedata.api.UpdateProductionDevice
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Reference data", description = "Legal entities, books, counterparties, registries, registry accounts, production devices")
class ReferenceDataController(
    private val query: ReferenceDataQuery,
    private val command: ReferenceDataCommand,
    private val actorProvider: ActorProvider,
) {
    /** Master data is maintained by middle office (and administrators); registry data also by operations. */
    private fun requireMaintainer(vararg extra: Role) = actorProvider.current().requireRole(Role.MIDDLE_OFFICE, *extra)

    @GetMapping("/legal-entities")
    fun legalEntities(): List<LegalEntityView> = query.legalEntities()

    @GetMapping("/legal-entities/{id}")
    fun legalEntity(
        @PathVariable id: UUID,
    ): LegalEntityView = query.requireLegalEntity(id)

    @PostMapping("/legal-entities")
    @ResponseStatus(HttpStatus.CREATED)
    fun createLegalEntity(
        @Valid @RequestBody body: CreateLegalEntity,
    ): LegalEntityView = requireMaintainer().let { command.createLegalEntity(body) }

    @PatchMapping("/legal-entities/{id}")
    fun updateLegalEntity(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateLegalEntity,
    ): LegalEntityView {
        requireMaintainer()
        actorProvider.current().requireEntity(id)
        // The accounting policy changes through month end (a policy change approved by a second accountant).
        if (body.accountingPolicy != null && body.accountingPolicy != query.requireLegalEntity(id).accountingPolicy) {
            throw ValidationException("The accounting policy changes through a policy change in month end, approved by a second accountant")
        }
        return command.updateLegalEntity(id, body)
    }

    @GetMapping("/legal-entities/{id}/books")
    fun books(
        @PathVariable id: UUID,
    ): List<BookView> = actorProvider.current().requireEntity(id).let { query.books(id) }

    @PostMapping("/books")
    @ResponseStatus(HttpStatus.CREATED)
    fun createBook(
        @Valid @RequestBody body: CreateBook,
    ): BookView {
        requireMaintainer()
        actorProvider.current().requireEntity(body.legalEntityId)
        return command.createBook(body)
    }

    @GetMapping("/books/{id}")
    fun book(
        @PathVariable id: UUID,
    ): BookView = query.requireBook(id).also { actorProvider.current().requireEntity(it.legalEntityId) }

    @GetMapping("/counterparties")
    fun counterparties(): List<CounterpartyView> = query.counterparties()

    @GetMapping("/counterparties/{id}")
    fun counterparty(
        @PathVariable id: UUID,
    ): CounterpartyView = query.requireCounterparty(id)

    @PostMapping("/counterparties")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCounterparty(
        @Valid @RequestBody body: CreateCounterparty,
    ): CounterpartyView {
        requireMaintainer()
        return command.createCounterparty(body)
    }

    @PatchMapping("/counterparties/{id}")
    fun updateCounterparty(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateCounterparty,
    ): CounterpartyView {
        requireMaintainer()
        return command.updateCounterparty(id, body)
    }

    @GetMapping("/registries")
    fun registries(): List<RegistryView> = query.registries()

    @GetMapping("/registry-accounts")
    fun registryAccounts(
        @RequestParam holderType: HolderType,
        @RequestParam holderId: UUID,
    ): List<RegistryAccountView> {
        if (holderType == HolderType.LEGAL_ENTITY) actorProvider.current().requireEntity(holderId)
        return query.registryAccounts(holderType, holderId)
    }

    @GetMapping("/registry-accounts/{id}")
    fun registryAccount(
        @PathVariable id: UUID,
    ): RegistryAccountView = query.requireRegistryAccount(id)

    @PostMapping("/registry-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRegistryAccount(
        @Valid @RequestBody body: CreateRegistryAccount,
    ): RegistryAccountView {
        requireMaintainer(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
        if (body.holderType == HolderType.LEGAL_ENTITY) actorProvider.current().requireEntity(body.holderId)
        return command.createRegistryAccount(body)
    }

    @PutMapping("/registry-accounts/{id}/default-book")
    fun assignDefaultBook(
        @PathVariable id: UUID,
        @RequestBody body: DefaultBookRequest,
    ): RegistryAccountView {
        requireMaintainer(Role.BACK_OFFICE)
        actorProvider.current().requireEntity(query.requireRegistryAccount(id).holderId)
        return command.assignDefaultBook(id, body.bookId)
    }

    @GetMapping("/production-devices")
    fun productionDevices(
        @RequestParam(defaultValue = "false") own: Boolean,
    ): List<ProductionDeviceView> = query.productionDevices(own)

    @GetMapping("/production-devices/{id}")
    fun productionDevice(
        @PathVariable id: UUID,
    ): ProductionDeviceView = query.requireProductionDevice(id)

    @PostMapping("/production-devices")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProductionDevice(
        @Valid @RequestBody body: CreateProductionDevice,
    ): ProductionDeviceView = requireMaintainer(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR).let { command.createProductionDevice(body) }

    @PatchMapping("/production-devices/{id}")
    fun updateProductionDevice(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateProductionDevice,
    ): ProductionDeviceView = requireMaintainer(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR).let { command.updateProductionDevice(id, body) }

    @GetMapping("/history/{table}/{id}")
    fun history(
        @PathVariable table: String,
        @PathVariable id: UUID,
    ): List<HistoryEntry<Map<String, Any?>>> = requireHistoryReader(table, id).let { query.history(table, id) }

    @GetMapping("/history/{table}/{id}/as-of")
    fun asOf(
        @PathVariable table: String,
        @PathVariable id: UUID,
        @RequestParam knownAt: Instant,
    ): Map<String, Any?>? = requireHistoryReader(table, id).let { query.asOf(table, id, knownAt) }

    /**
     * History is kept for the master data tables below. Party details (addresses, VAT numbers, bank accounts) carry
     * payment and personal data, so their history is read only by the roles that maintain them.
     */
    private fun requireHistoryReader(table: String, id: UUID) {
        val actor = actorProvider.current()
        when (table) {
            in PUBLIC_HISTORY -> if (table == "legal_entity") actor.requireEntity(id)
            in PARTY_HISTORY -> actor.requireRole(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR, Role.ACCOUNTANT)
            else -> throw NotFoundException("History", table)
        }
    }

    private companion object {
        val PUBLIC_HISTORY = setOf("legal_entity", "book", "counterparty", "registry", "registry_account", "production_device")
        val PARTY_HISTORY = setOf("party_address", "vat_registration", "bank_account")
    }
}

data class DefaultBookRequest(val bookId: UUID?)
