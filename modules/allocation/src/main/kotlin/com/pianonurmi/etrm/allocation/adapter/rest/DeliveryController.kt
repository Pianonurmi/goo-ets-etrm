package com.pianonurmi.etrm.allocation.adapter.rest

import com.pianonurmi.etrm.allocation.api.DeliverAllowances
import com.pianonurmi.etrm.allocation.api.DeliveryCommand
import com.pianonurmi.etrm.allocation.api.MatchReceipt
import com.pianonurmi.etrm.allocation.api.ReceiptMatch
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.trade.api.TradeQuery
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Deliveries", description = "Allowance sale deliveries and purchase receipt matching")
class DeliveryController(
    private val deliveries: DeliveryCommand,
    private val trades: TradeQuery,
    private val actorProvider: ActorProvider,
) {
    /** Submits the Union Registry transfer for an allowance sale schedule. */
    @PostMapping("/allowances")
    @ResponseStatus(HttpStatus.CREATED)
    fun deliverAllowances(
        @RequestBody body: DeliverAllowances,
    ): RegistryInstructionView {
        authorise(body.scheduleId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
        return deliveries.deliverAllowances(body)
    }

    /** Matches a received lot or allowance movement to a purchase schedule. */
    @PostMapping("/receipts")
    fun matchReceipt(
        @RequestBody body: MatchReceipt,
    ): ReceiptMatch {
        authorise(body.scheduleId, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)
        return deliveries.matchReceipt(body)
    }

    /**
     * The guard sits here because module flows call [DeliveryCommand] as SYSTEM: an operator role, on the legal
     * entity of the trade. An unknown schedule is left to the service, which answers 404.
     */
    private fun authorise(scheduleId: UUID, vararg roles: Role) {
        val actor = actorProvider.current()
        actor.requireRole(*roles)
        trades.obligation(scheduleId)?.let { actor.requireEntity(it.legalEntityId) }
    }
}
