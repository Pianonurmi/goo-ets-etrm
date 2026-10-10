package com.pianonurmi.etrm.allocation.adapter.rest

import com.pianonurmi.etrm.allocation.api.DeliverAllowances
import com.pianonurmi.etrm.allocation.api.DeliveryCommand
import com.pianonurmi.etrm.allocation.api.MatchReceipt
import com.pianonurmi.etrm.allocation.api.ReceiptMatch
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Deliveries", description = "Allowance sale deliveries and purchase receipt matching")
class DeliveryController(private val deliveries: DeliveryCommand) {
    /** Submits the Union Registry transfer for an allowance sale schedule. */
    @PostMapping("/allowances")
    @ResponseStatus(HttpStatus.CREATED)
    fun deliverAllowances(
        @RequestBody body: DeliverAllowances,
    ): RegistryInstructionView = deliveries.deliverAllowances(body)

    /** Matches a received lot or allowance movement to a purchase schedule. */
    @PostMapping("/receipts")
    fun matchReceipt(
        @RequestBody body: MatchReceipt,
    ): ReceiptMatch = deliveries.matchReceipt(body)
}
