package com.pianonurmi.etrm.referencedata.adapter.rest

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.referencedata.api.ChangeEntityType
import com.pianonurmi.etrm.referencedata.api.ChangeRequestCommand
import com.pianonurmi.etrm.referencedata.api.ChangeRequestQuery
import com.pianonurmi.etrm.referencedata.api.ChangeRequestStatus
import com.pianonurmi.etrm.referencedata.api.ChangeRequestView
import com.pianonurmi.etrm.referencedata.api.CounterpartyAccountChange
import com.pianonurmi.etrm.referencedata.api.RegistryChange
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CancelChangeRequest(val note: String)

@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Reference data", description = "Legal entities, books, counterparties, registries, registry accounts, production devices")
class ChangeRequestController(private val query: ChangeRequestQuery, private val command: ChangeRequestCommand) {
    /** Requests a change of a registry's integration mode or active flag; applied after a second person's approval in the inbox. */
    @PostMapping("/registries/{code}/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR, Role.ACCOUNTANT)
    fun requestRegistryChange(
        @PathVariable code: String,
        @RequestBody body: RegistryChange,
    ): ChangeRequestView = command.requestRegistryChange(code, body)

    /** Requests a change to a counterparty's registry accounts (add, deactivate, trusted account list); four-eyes. */
    @PostMapping("/counterparties/{id}/registry-accounts/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR, Role.ACCOUNTANT)
    fun requestCounterpartyAccountChange(
        @PathVariable id: UUID,
        @RequestBody body: CounterpartyAccountChange,
    ): ChangeRequestView = command.requestCounterpartyAccountChange(id, body)

    @GetMapping("/change-requests")
    fun changeRequests(
        @RequestParam(required = false) status: ChangeRequestStatus?,
        @RequestParam(required = false) entityType: ChangeEntityType?,
    ): List<ChangeRequestView> = query.changeRequests(status, entityType)

    @GetMapping("/change-requests/{id}")
    fun changeRequest(
        @PathVariable id: UUID,
    ): ChangeRequestView = query.changeRequest(id) ?: throw NotFoundException("ChangeRequest", id)

    @PostMapping("/change-requests/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: CancelChangeRequest,
    ): ChangeRequestView = command.cancel(id, body.note)
}
