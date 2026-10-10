package com.pianonurmi.etrm.referencedata.application

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.referencedata.api.AccountChangeAction
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountChanged
import com.pianonurmi.etrm.referencedata.api.RegistryAccountType
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryType
import com.pianonurmi.etrm.referencedata.domain.ChangeApplier
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Counterparty registry accounts: where our certificates and allowances are delivered, so every change needs a
 * second person. Adding a Union Registry account to the trusted account list takes effect after the registry's
 * activation delay of four working days.
 */
@Component
class CounterpartyAccountChangeApplier(
    private val repository: ReferenceDataRepository,
    private val referenceData: ReferenceDataService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : ChangeApplier {
    override val entityType = ChangeEntityType.COUNTERPARTY_REGISTRY_ACCOUNT

    override fun validate(entityKey: String, change: Map<String, Any?>, expectedVersion: Int?): String {
        val action = action(change)
        val counterparty = referenceData.requireCounterparty(uuid(change, "counterpartyId"))
        return when (action) {
            AccountChangeAction.CREATE -> {
                val registry = referenceData.requireRegistry(text(change, "registryCode"))
                val number = text(change, "accountNumber")
                val type = RegistryAccountType.valueOf(text(change, "accountType"))
                text(change, "name")
                referenceData.validateAccountType(registry, type)
                if (repository.findRegistryAccountByNumber(registry.id, number) != null) {
                    throw ConflictException("Account $number already exists in registry ${registry.code}")
                }
                "Counterparty ${counterparty.code}: add ${registry.code} account $number ($type)"
            }
            else -> {
                val account = account(change)
                if (account.holderType != HolderType.COUNTERPARTY || account.holderId != counterparty.id) {
                    throw ValidationException("Account ${account.accountNumber} is not held by counterparty ${counterparty.code}")
                }
                when (action) {
                    AccountChangeAction.DEACTIVATE -> if (!account.active) throw ValidationException("Account ${account.accountNumber} is already inactive")
                    AccountChangeAction.REACTIVATE -> if (account.active) throw ValidationException("Account ${account.accountNumber} is already active")
                    AccountChangeAction.ADD_TO_TRUSTED_LIST -> if (account.trusted) {
                        throw ValidationException(
                            "Account ${account.accountNumber} is already trusted",
                        )
                    }
                    AccountChangeAction.REMOVE_FROM_TRUSTED_LIST -> if (!account.trusted) {
                        throw ValidationException(
                            "Account ${account.accountNumber} is not trusted",
                        )
                    }
                    AccountChangeAction.CREATE -> Unit
                }
                "Counterparty ${counterparty.code}: ${action.name.lowercase().replace('_', ' ')} ${account.registryCode} account ${account.accountNumber}"
            }
        }
    }

    override fun apply(request: ChangeRequestView) {
        val change = request.change
        when (val action = action(change)) {
            AccountChangeAction.CREATE -> {
                val registry = referenceData.requireRegistry(text(change, "registryCode"))
                val account = RegistryAccountView(
                    id = Ids.newId(),
                    registryId = registry.id,
                    registryCode = registry.code,
                    accountNumber = text(change, "accountNumber"),
                    name = text(change, "name"),
                    accountType = RegistryAccountType.valueOf(text(change, "accountType")),
                    holderType = HolderType.COUNTERPARTY,
                    holderId = uuid(change, "counterpartyId"),
                    canImport = true,
                    canExport = false,
                    trusted = false,
                    active = true,
                    version = 1,
                )
                repository.insertRegistryAccount(account)
                events.publishEvent(RegistryAccountChanged(account.id))
            }
            else -> {
                val current = account(change)
                val today = LocalDate.now(clock)
                val updated = when (action) {
                    AccountChangeAction.DEACTIVATE -> current.copy(active = false)
                    AccountChangeAction.REACTIVATE -> current.copy(active = true)
                    AccountChangeAction.ADD_TO_TRUSTED_LIST -> current.copy(trusted = true, trustedSince = trustedFrom(current, today))
                    AccountChangeAction.REMOVE_FROM_TRUSTED_LIST -> current.copy(trusted = false, trustedSince = null)
                    AccountChangeAction.CREATE -> current
                }.copy(version = current.version + 1)
                if (!repository.updateRegistryAccount(updated, current.version)) {
                    throw StaleVersionException("RegistryAccount", current.id, current.version, current.version + 1)
                }
                events.publishEvent(RegistryAccountChanged(current.id))
            }
        }
    }

    /** Union Registry trusted accounts activate after four working days; other registries have no such delay. */
    private fun trustedFrom(account: RegistryAccountView, today: LocalDate): LocalDate {
        val registry = referenceData.requireRegistry(account.registryCode)
        return if (registry.registryType == RegistryType.EMISSION_ALLOWANCE) referenceData.addBusinessDays(CALENDAR, today, ACTIVATION_DAYS) else today
    }

    private fun account(change: Map<String, Any?>): RegistryAccountView {
        val id = uuid(change, "accountId")
        return repository.findRegistryAccount(id) ?: throw NotFoundException("RegistryAccount", id)
    }

    private fun action(change: Map<String, Any?>): AccountChangeAction =
        runCatching { AccountChangeAction.valueOf(text(change, "action")) }.getOrElse { throw ValidationException("Unknown account change action") }

    private fun text(change: Map<String, Any?>, key: String): String =
        change[key]?.toString()?.takeIf { it.isNotBlank() } ?: throw ValidationException("$key is required")

    private fun uuid(change: Map<String, Any?>, key: String): UUID =
        runCatching { UUID.fromString(text(change, key)) }.getOrElse { throw ValidationException("$key must be an id") }

    companion object {
        const val CALENDAR = "TARGET2"
        const val ACTIVATION_DAYS = 4
    }
}
