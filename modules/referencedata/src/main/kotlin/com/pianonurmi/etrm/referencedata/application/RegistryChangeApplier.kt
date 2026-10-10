package com.pianonurmi.etrm.referencedata.application

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.api.RegistryChanged
import com.pianonurmi.etrm.referencedata.api.RegistryIntegration
import com.pianonurmi.etrm.referencedata.domain.ChangeApplier
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/** Registry integration mode and active flag. In-flight instructions keep the channel they were dispatched with. */
@Component
class RegistryChangeApplier(
    private val repository: ReferenceDataRepository,
    private val events: ApplicationEventPublisher,
) : ChangeApplier {
    override val entityType = ChangeEntityType.REGISTRY

    override fun validate(entityKey: String, change: Map<String, Any?>, expectedVersion: Int?): String {
        val registry = repository.findRegistry(entityKey) ?: throw NotFoundException("Registry", entityKey)
        val unknown = change.keys - setOf("integration", "active")
        if (unknown.isNotEmpty()) throw ValidationException("Registry changes may only set integration and active, not $unknown")
        if (expectedVersion != null && expectedVersion != registry.version) {
            throw StaleVersionException("Registry", entityKey, expectedVersion, registry.version)
        }
        val integration = change["integration"]?.let { value ->
            runCatching { RegistryIntegration.valueOf(value.toString()) }.getOrElse { throw ValidationException("Unknown integration mode $value") }
        }
        val active = change["active"]?.let { it as? Boolean ?: throw ValidationException("active must be true or false") }
        return buildList {
            integration?.let { add("integration ${registry.integration} -> $it") }
            active?.let { add("active ${registry.active} -> $it") }
        }.joinToString(prefix = "Registry ${registry.code}: ")
    }

    override fun apply(request: ChangeRequestView) {
        val current = repository.findRegistry(request.entityKey) ?: throw NotFoundException("Registry", request.entityKey)
        val updated = current.copy(
            integration = request.change["integration"]?.let { RegistryIntegration.valueOf(it.toString()) } ?: current.integration,
            active = request.change["active"] as? Boolean ?: current.active,
            version = current.version + 1,
        )
        if (!repository.updateRegistry(updated, current.version)) throw StaleVersionException("Registry", current.code, current.version, current.version + 1)
        events.publishEvent(RegistryChanged(updated, current))
    }
}
