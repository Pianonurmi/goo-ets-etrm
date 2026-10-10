package com.pianonurmi.etrm.inventory.adapter.rest

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotCorrection
import com.pianonurmi.etrm.inventory.api.LotEventView
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.ReconciliationCommand
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.inventory.api.SplitResult
import com.pianonurmi.etrm.inventory.api.StatementView
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.product.api.criteria.Criterion
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Certificate lots, allowance holdings and registry reconciliation")
class InventoryController(
    private val lotCommand: LotCommand,
    private val lotQuery: LotQuery,
    private val criteria: CriteriaService,
    private val holdingCommand: AllowanceHoldingCommand,
    private val holdingQuery: AllowanceHoldingQuery,
    private val reconciliationCommand: ReconciliationCommand,
    private val reconciliationQuery: ReconciliationQuery,
) {
    @GetMapping("/lots")
    fun lots(
        @RequestParam(required = false) holderEntityId: UUID?,
        @RequestParam(required = false) registryAccountId: UUID?,
        @RequestParam(required = false) status: Set<LotStatus>?,
        @RequestParam(required = false) deviceGsrn: String?,
        @RequestParam(required = false) expiringBefore: LocalDate?,
        @RequestParam(defaultValue = "500") limit: Int,
    ): List<LotView> = lotQuery.lots(LotFilter(holderEntityId, registryAccountId, status, deviceGsrn, expiringBefore, limit = limit))

    @GetMapping("/lots/{id}")
    fun lot(
        @PathVariable id: UUID,
    ): LotView = lotQuery.requireLot(id)

    @GetMapping("/lots/{id}/history")
    fun history(
        @PathVariable id: UUID,
    ): List<LotEventView> = lotQuery.history(id)

    @GetMapping("/lots/{id}/as-of")
    fun asOf(
        @PathVariable id: UUID,
        @RequestParam effectiveAt: Instant,
        @RequestParam knownAt: Instant,
    ): Map<String, Any?>? = lotQuery.asOf(id, effectiveAt, knownAt)

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    fun receive(
        @Valid @RequestBody body: ReceiveLots,
    ): LotView = lotCommand.receive(body)

    @PostMapping("/lots/search")
    fun search(
        @RequestBody body: LotSearchRequest,
    ): List<LotView> =
        lotQuery.search(
            body.holderEntityId,
            criteria.bind(body.criterion, criteria.contextFor(body.deliveryDate ?: LocalDate.now())),
            body.statuses,
            body.limit,
        )

    @PostMapping("/lots/{id}/split")
    fun split(
        @PathVariable id: UUID,
        @RequestBody body: QuantityRequest,
    ): SplitResult = lotCommand.split(id, body.quantity)

    @PostMapping("/lots/{id}/reserve")
    fun reserve(
        @PathVariable id: UUID,
        @RequestBody body: ReservationRequest,
    ): LotView = lotCommand.reserve(id, body.quantity, body.reservationRef)

    @PostMapping("/lots/{id}/release")
    fun release(
        @PathVariable id: UUID,
        @RequestBody body: ReservationRequest,
    ): LotView = lotCommand.release(id, body.reservationRef)

    @PostMapping("/lots/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: CancelRequest,
    ): LotView = lotCommand.cancel(id, body.quantity, body.details)

    @PostMapping("/lots/{id}/withdraw")
    fun withdraw(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): LotView = lotCommand.withdraw(id, body.reason)

    @PostMapping("/lots/{id}/correct")
    fun correct(
        @PathVariable id: UUID,
        @RequestBody body: LotCorrection,
    ): LotView = lotCommand.correct(id, body)

    @PostMapping("/jobs/expire")
    fun expire(
        @RequestParam(required = false) asOf: LocalDate?,
    ): Map<String, Int> = mapOf("expired" to lotCommand.expireDueLots(asOf ?: LocalDate.now()))

    @GetMapping("/expiry-ladder")
    fun expiryLadder(
        @RequestParam holderEntityId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(defaultValue = "18") months: Int,
    ) =
        lotQuery.expiryLadder(holderEntityId, from ?: LocalDate.now(), months)

    @GetMapping("/journal/verify")
    fun verifyJournal(): Map<String, Any> = lotQuery.verifyJournal().let { mapOf("brokenLots" to it, "ok" to it.isEmpty()) }

    @GetMapping("/holdings")
    fun holdings(
        @RequestParam holderEntityId: UUID,
    ): List<AllowanceHoldingView> = holdingQuery.holdings(holderEntityId)

    @GetMapping("/holdings/{id}/movements")
    fun movements(
        @PathVariable id: UUID,
    ): List<AllowanceMovementView> = holdingQuery.movements(id)

    @PostMapping("/holdings/movements")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordMovement(
        @Valid @RequestBody body: RecordAllowanceMovement,
    ): AllowanceHoldingView = holdingCommand.record(body)

    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.CREATED)
    fun importStatement(
        @Valid @RequestBody body: ImportStatement,
    ): StatementView = reconciliationCommand.importStatement(body)

    @PostMapping("/statements/{id}/reconcile")
    fun reconcile(
        @PathVariable id: UUID,
    ): StatementView = reconciliationCommand.reconcile(id)

    @GetMapping("/statements/{id}")
    fun statement(
        @PathVariable id: UUID,
    ): StatementView? = reconciliationQuery.statement(id)

    @GetMapping("/breaks")
    fun openBreaks(
        @RequestParam(required = false) registryAccountId: UUID?,
    ): List<ReconciliationBreakView> = reconciliationQuery.openBreaks(registryAccountId)

    @PostMapping("/breaks/{id}/resolve")
    fun resolveBreak(
        @PathVariable id: UUID,
        @RequestBody body: ResolveBreakRequest,
    ): ReconciliationBreakView =
        reconciliationCommand.resolveBreak(id, body.status, body.resolution)
}

data class LotSearchRequest(
    val holderEntityId: UUID,
    val criterion: Criterion,
    val deliveryDate: LocalDate? = null,
    val statuses: Set<LotStatus> = setOf(LotStatus.HELD),
    val limit: Int = 1000,
)

data class QuantityRequest(val quantity: Long)

data class ReservationRequest(val reservationRef: UUID, val quantity: Long? = null)

data class CancelRequest(val quantity: Long? = null, val details: CancellationDetails)

data class ReasonRequest(val reason: String)

data class ResolveBreakRequest(val status: BreakStatus, val resolution: String)
