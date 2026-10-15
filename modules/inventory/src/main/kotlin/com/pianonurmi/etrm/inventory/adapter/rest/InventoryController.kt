package com.pianonurmi.etrm.inventory.adapter.rest

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.BreakAgeing
import com.pianonurmi.etrm.inventory.api.BreakFilter
import com.pianonurmi.etrm.inventory.api.BreakStatus
import com.pianonurmi.etrm.inventory.api.BreakType
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
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val JOURNAL_READERS = arrayOf(Role.AUDITOR, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK)

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
    private val reconciliationAccess: ReconciliationAccess,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) {
    @GetMapping("/lots")
    fun lots(
        @RequestParam(required = false) holderEntityId: UUID?,
        @RequestParam(required = false) registryAccountId: UUID?,
        @RequestParam(required = false) status: Set<LotStatus>?,
        @RequestParam(required = false) deviceGsrn: String?,
        @RequestParam(required = false) expiringBefore: LocalDate?,
        @RequestParam(defaultValue = "500") limit: Int,
    ): List<LotView> = lotQuery.visibleLots(LotFilter(holderEntityId, registryAccountId, status, deviceGsrn, expiringBefore, limit = limit))

    @GetMapping("/lots/{id}")
    fun lot(
        @PathVariable id: UUID,
    ): LotView = lotQuery.requireVisibleLot(id)

    @GetMapping("/lots/{id}/history")
    fun history(
        @PathVariable id: UUID,
    ): List<LotEventView> = lotQuery.requireVisibleLot(id).let { lotQuery.history(id) }

    @GetMapping("/lots/{id}/as-of")
    fun asOf(
        @PathVariable id: UUID,
        @RequestParam effectiveAt: Instant,
        @RequestParam knownAt: Instant,
    ): Map<String, Any?>? = lotQuery.requireVisibleLot(id).let { lotQuery.asOf(id, effectiveAt, knownAt) }

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun receive(
        @Valid @RequestBody body: ReceiveLots,
    ): LotView = lotCommand.requireLotOperator(body.holderEntityId).let { lotCommand.receive(body) }

    @PostMapping("/lots/search")
    fun search(
        @RequestBody body: LotSearchRequest,
    ): List<LotView> =
        lotQuery.search(
            body.holderEntityId,
            criteria.bind(body.criterion, criteria.contextFor(body.deliveryDate ?: LocalDate.now(clock))),
            body.statuses,
            body.limit,
        )

    @PostMapping("/lots/{id}/split")
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun split(
        @PathVariable id: UUID,
        @RequestBody body: QuantityRequest,
    ): SplitResult = operate(id) { lotCommand.split(id, body.quantity) }

    @PostMapping("/lots/{id}/reserve")
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun reserve(
        @PathVariable id: UUID,
        @RequestBody body: ReservationRequest,
    ): LotView = operate(id) { lotCommand.reserve(id, body.quantity, body.reservationRef) }

    @PostMapping("/lots/{id}/release")
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun release(
        @PathVariable id: UUID,
        @RequestBody body: ReservationRequest,
    ): LotView = operate(id) { lotCommand.release(id, body.reservationRef) }

    @PostMapping("/lots/{id}/cancel")
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: LotCancelRequest,
    ): LotView = operate(id) { lotCommand.cancel(id, body.quantity, body.details) }

    @PostMapping("/lots/{id}/withdraw")
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun withdraw(
        @PathVariable id: UUID,
        @RequestBody body: ReasonRequest,
    ): LotView = operate(id) { lotCommand.withdraw(id, body.reason) }

    @PostMapping("/lots/{id}/correct")
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun correct(
        @PathVariable id: UUID,
        @RequestBody body: LotCorrection,
    ): LotView = operate(id) { lotCommand.correct(id, body) }

    /** Manual lot operations need back office or registry operator rights on the holder (module flows do not). */
    private fun <T> operate(lotId: UUID, operation: () -> T): T {
        lotCommand.requireLotOperator(lotQuery.requireVisibleLot(lotId).holderEntityId)
        return operation()
    }

    /**
     * Runs the nightly expiry sweep by hand. It touches every entity's lots, so it needs back office or registry
     * operator rights on all entities, and it never expires ahead of today.
     */
    @PostMapping("/jobs/expire")
    fun expire(
        @RequestParam(required = false) asOf: LocalDate?,
    ): Map<String, Int> {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
        if (!actor.seesAllEntities) throw ForbiddenException("The expiry sweep covers every legal entity; ${actor.userId} does not see them all")
        val today = LocalDate.now(clock)
        if (asOf != null && asOf.isAfter(today)) throw ValidationException("Lots cannot be expired ahead of today ($today)")
        return mapOf("expired" to lotCommand.expireDueLots(asOf ?: today))
    }

    @GetMapping("/expiry-ladder")
    fun expiryLadder(
        @RequestParam holderEntityId: UUID,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(defaultValue = "18") months: Int,
    ) =
        lotQuery.expiryLadder(holderEntityId, from ?: LocalDate.now(clock), months)

    /**
     * Checks the hash chains of the lot journal and the allowance movements; broken lots and holdings are named only
     * within the user's legal entities.
     */
    @GetMapping("/journal/verify")
    @AllowedRoles(Role.AUDITOR, Role.BACK_OFFICE, Role.REGISTRY_OPERATOR, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK)
    fun verifyJournal(): Map<String, Any> {
        val actor = actorProvider.current()
        actor.requireRole(*JOURNAL_READERS)
        val broken = lotQuery.verifyJournal()
        val visible = broken.filter { actor.canAccess(lotQuery.lot(it)?.holderEntityId) }
        val brokenHoldings = holdingQuery.verifyMovements()
        val visibleHoldings = brokenHoldings.filter { actor.canAccess(holdingQuery.holdingById(it)?.holderEntityId) }
        return mapOf("brokenLots" to visible, "brokenHoldings" to visibleHoldings, "ok" to (broken.isEmpty() && brokenHoldings.isEmpty()))
    }

    @GetMapping("/holdings")
    fun holdings(
        @RequestParam holderEntityId: UUID,
    ): List<AllowanceHoldingView> = holdingQuery.holdings(holderEntityId)

    @GetMapping("/holdings/{id}/movements")
    fun movements(
        @PathVariable id: UUID,
    ): List<AllowanceMovementView> {
        val holding = holdingQuery.holdingById(id) ?: throw NotFoundException("AllowanceHolding", id)
        actorProvider.current().requireEntity(holding.holderEntityId)
        return holdingQuery.movements(id)
    }

    @PostMapping("/holdings/movements")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
    fun recordMovement(
        @Valid @RequestBody body: RecordAllowanceMovement,
    ): AllowanceHoldingView {
        // Module flows (deliveries, surrenders) record movements as SYSTEM; people need a lot operator role.
        lotCommand.requireLotOperator(body.holderEntityId)
        return holdingCommand.record(body)
    }

    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun importStatement(
        @Valid @RequestBody body: ImportStatement,
    ): StatementView = reconciliationAccess.importer(body.registryAccountId).let { reconciliationCommand.importStatement(body) }

    @PostMapping("/statements/{id}/reconcile")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE)
    fun reconcile(
        @PathVariable id: UUID,
    ): StatementView {
        reconciliationAccess.importer(requireStatement(id).registryAccountId)
        return reconciliationCommand.reconcile(id)
    }

    @GetMapping("/statements")
    fun statements(
        @RequestParam registryAccountId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<StatementView> = reconciliationAccess.reader(registryAccountId).let { reconciliationQuery.statements(registryAccountId, limit) }

    @GetMapping("/statements/{id}")
    @AllowedRoles(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK, Role.AUDITOR)
    fun statement(
        @PathVariable id: UUID,
    ): StatementView = requireStatement(id).also { reconciliationAccess.reader(it.registryAccountId) }

    @GetMapping("/breaks")
    fun breaks(
        @RequestParam(required = false) registryAccountId: UUID?,
        @RequestParam(required = false) status: Set<BreakStatus>?,
        @RequestParam(required = false) type: BreakType?,
    ): List<ReconciliationBreakView> = reconciliationAccess.visible(
        reconciliationQuery.breaks(BreakFilter(registryAccountId, status ?: BreakStatus.entries.filter { it.open }.toSet(), type)),
    ) { it.registryAccountId }

    @GetMapping("/breaks/{id}")
    fun reconciliationBreak(
        @PathVariable id: UUID,
    ): ReconciliationBreakView = reconciliationQuery.requireBreak(id).also { reconciliationAccess.reader(it.registryAccountId) }

    @GetMapping("/breaks/ageing")
    fun ageing(
        @RequestParam(required = false) legalEntityId: UUID?,
    ): BreakAgeing = reconciliationQuery.ageing(reconciliationAccess.accountsInScope(legalEntityId))

    private fun requireStatement(id: UUID): StatementView = reconciliationQuery.statement(id) ?: throw NotFoundException("Statement", id)
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

data class LotCancelRequest(val quantity: Long? = null, val details: CancellationDetails)

data class ReasonRequest(val reason: String)
