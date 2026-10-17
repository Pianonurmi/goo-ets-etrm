package com.pianonurmi.etrm.registry.api

import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.ImportStatement
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Cost and feasibility of moving certificates between registries. */
data class TransferRoute(
    val fromRegistryCode: String,
    val toRegistryCode: String,
    val feasible: Boolean,
    val feePerMwh: BigDecimal,
    val expectedDays: Int,
    val restrictions: Map<String, Any?> = emptyMap(),
) {
    val sameRegistry: Boolean get() = fromRegistryCode == toRegistryCode
}

interface TransferRouteQuery {
    fun route(fromRegistryCode: String, toRegistryCode: String): TransferRoute

    /** Routes from one registry to every known registry; used by the allocation engine's scoring. */
    fun routesFrom(fromRegistryCode: String): Map<String, TransferRoute>
}

data class UpsertTransferRoute(
    val fromRegistryCode: String,
    val toRegistryCode: String,
    val feasible: Boolean,
    val feePerMwh: BigDecimal,
    val expectedDays: Int,
)

interface TransferRouteCommand {
    fun upsert(command: UpsertTransferRoute): TransferRoute
}

enum class InstructionType {
    /** Certificate lot transfer to another account. */
    TRANSFER,

    /** Certificate lot cancellation (disclosure, own consumption). */
    CANCELLATION,

    /** Quantity of an allowance product to another account. */
    ALLOWANCE_TRANSFER,

    /** Allowances surrendered for an installation's compliance year. */
    SURRENDER,
}

enum class AssetKind { GOO_LOT, ALLOWANCE }

enum class InstructionStatus {
    /** Waiting for a second person's approval (instructions that do not come from an approved allocation plan). */
    PENDING_APPROVAL,

    /** Approved and waiting to be dispatched to the registry. */
    PENDING,

    /** Claimed by a dispatcher; the registry call is in progress. */
    DISPATCHING,
    SENT,

    /** The registry call may or may not have taken effect (timeout, lost lease); resolved by status query or operator. */
    UNKNOWN,
    ACKNOWLEDGED,
    CONFIRMED,
    FAILED,
    CANCELLED,
    ;

    val terminal: Boolean get() = this == CONFIRMED || this == FAILED || this == CANCELLED

    /** With the registry: the outcome is awaited from the registry or an operator. */
    val awaitingRegistry: Boolean get() = this == SENT || this == ACKNOWLEDGED || this == UNKNOWN
}

data class RegistryInstructionView(
    val id: UUID,
    val instructionKey: String,
    val type: InstructionType,
    val registryCode: String,
    val holderEntityId: UUID,
    val fromAccountId: UUID,
    val toRegistryCode: String?,
    val toAccountRef: String?,
    /** Certificate lot (GOO_LOT instructions only). */
    val lotId: UUID?,
    val range: CertificateRange?,
    /** MWh for certificate lots, tCO2e for allowances. */
    val quantity: Long,
    val cancellation: CancellationDetails?,
    val planRef: UUID?,
    val lineRef: UUID?,
    val obligationRef: UUID?,
    val status: InstructionStatus,
    val externalRef: String?,
    val attempts: Int,
    val lastError: String?,
    val submittedBy: String,
    val confirmedBy: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
    val confirmedAt: Instant?,
    val version: Int,
    /** Gateway channel (integration mode) fixed at creation. */
    val channel: String = "MANUAL",
    val nextAttemptAt: Instant? = null,
    val approvedBy: String? = null,
    val approvedAt: Instant? = null,
    val assetKind: AssetKind = AssetKind.GOO_LOT,
    /** Allowance product and holding (ALLOWANCE instructions only). */
    val productCode: String? = null,
    val holdingRef: UUID? = null,
    /** What confirmation records on the holding, with the trade price for sales. */
    val movementType: AllowanceMovementType? = null,
    val unitPrice: java.math.BigDecimal? = null,
    val priceCurrency: String? = null,
    val complianceYear: Int? = null,
    /** When the registry is expected to execute; later than now for Union Registry transfers to untrusted accounts. */
    val expectedEffectiveAt: Instant? = null,
) {
    val unit: String get() = if (assetKind == AssetKind.ALLOWANCE) "TCO2E" else "MWH"

    /** The certificate lot of a GOO_LOT instruction. */
    fun requireLot(): UUID = lotId ?: error("Instruction $instructionKey is not a certificate lot instruction")

    fun requireHolding(): UUID = holdingRef ?: error("Instruction $instructionKey is not an allowance instruction")

    fun requireProduct(): String = productCode ?: error("Instruction $instructionKey names no allowance product")
}

/** Transfer of a lot (or part of it) to another account; a partial quantity splits the lot first. */
data class SubmitTransfer(
    val lotId: UUID,
    val quantityMwh: Long? = null,
    val toRegistryCode: String,
    val toAccountRef: String,
    val idempotencyKey: String,
    val planRef: UUID? = null,
    val lineRef: UUID? = null,
    val obligationRef: UUID? = null,
)

data class SubmitCancellation(
    val lotId: UUID,
    val quantityMwh: Long? = null,
    val details: CancellationDetails,
    val idempotencyKey: String,
    val planRef: UUID? = null,
    val lineRef: UUID? = null,
    val obligationRef: UUID? = null,
)

/** Transfer of allowances from one of our Union Registry accounts; needs a second person's approval. */
data class SubmitAllowanceTransfer(
    val fromAccountId: UUID,
    val productCode: String,
    val quantity: Long,
    /** The receiving account (a registered counterparty or own account). */
    val toAccountId: UUID,
    val idempotencyKey: String,
    /** SALE (with [unitPrice]) for a sale to a third party, TRANSFER_OUT otherwise. */
    val movementType: AllowanceMovementType = AllowanceMovementType.TRANSFER_OUT,
    val unitPrice: java.math.BigDecimal? = null,
    val priceCurrency: String? = null,
    val obligationRef: UUID? = null,
)

/** Surrender of allowances for a compliance year; needs a second person's approval. */
data class SubmitSurrender(
    val fromAccountId: UUID,
    val productCode: String,
    val quantity: Long,
    val complianceYear: Int,
    val idempotencyKey: String,
)

interface RegistryInstructionCommand {
    fun submitTransfer(command: SubmitTransfer): RegistryInstructionView

    fun submitCancellation(command: SubmitCancellation): RegistryInstructionView

    fun submitAllowanceTransfer(command: SubmitAllowanceTransfer): RegistryInstructionView

    fun submitSurrender(command: SubmitSurrender): RegistryInstructionView

    /** Operator or adapter confirmation of an instruction executed in the registry. */
    fun confirm(instructionId: UUID, externalRef: String?): RegistryInstructionView

    fun fail(instructionId: UUID, reason: String): RegistryInstructionView

    fun cancel(instructionId: UUID, reason: String): RegistryInstructionView

    /** Re-executes a FAILED or UNKNOWN instruction; needs a second person's approval like any manual instruction. */
    fun retry(instructionId: UUID): RegistryInstructionView
}

/** Instructions of any status, newest first, paged by an opaque cursor. */
data class InstructionSearch(
    val statuses: Set<InstructionStatus>? = null,
    val registryCode: String? = null,
    val holderEntityId: UUID? = null,
    val assetKind: AssetKind? = null,
    /** Created at or after this instant. */
    val from: Instant? = null,
    /** Created before this instant. */
    val to: Instant? = null,
    val limit: Int = 100,
    val cursor: String? = null,
    /** Restricts the search to these holders; set by the service from the user's scope. */
    val holderEntityIds: Set<UUID>? = null,
)

data class InstructionPage(val items: List<RegistryInstructionView>, val nextCursor: String?)

interface RegistryInstructionQuery {
    fun instruction(id: UUID): RegistryInstructionView?

    /** Instructions the current user may see (entity scope), filtered and paged. */
    fun search(search: InstructionSearch): InstructionPage

    fun requireInstruction(id: UUID): RegistryInstructionView

    fun byKey(idempotencyKey: String): RegistryInstructionView?

    fun open(registryCode: String? = null): List<RegistryInstructionView>

    fun forPlan(planRef: UUID): List<RegistryInstructionView>
}

// --------------------------------------------------------------------------------------------------------------
// Statement formats and imports
// --------------------------------------------------------------------------------------------------------------

enum class StatementKind { GOO_HOLDINGS, GOO_TRANSACTIONS, ALLOWANCE_HOLDINGS, ALLOWANCE_TRANSACTIONS }

/**
 * How one registry export is laid out: which header carries which field. Fields for GoO holdings: issuanceRef,
 * certStart, certEnd (or certRange with [constants] `rangeSeparator`), deviceGsrn, productionStart, productionEnd.
 * Transactions add externalRef, instructionKey, transactionType, status, transactionDate. [constants] may also map
 * registry status words to COMPLETED / FAILED (`status.<word>`) and type words to TRANSFER / CANCELLATION (`type.<word>`).
 */
data class StatementFormatView(
    val id: UUID,
    val registryCode: String,
    val profileCode: String,
    val kind: StatementKind,
    val delimiter: Char,
    val skipLines: Int,
    val dateFormat: String,
    val columnMapping: Map<String, String>,
    val constants: Map<String, String>,
    val active: Boolean,
    val version: Int,
    val updatedBy: String,
    val updatedAt: Instant,
)

data class UpsertStatementFormat(
    val registryCode: String,
    val profileCode: String,
    val kind: StatementKind,
    val delimiter: Char = ',',
    val skipLines: Int = 0,
    val dateFormat: String = "yyyy-MM-dd",
    val columnMapping: Map<String, String>,
    val constants: Map<String, String> = emptyMap(),
    val active: Boolean = true,
)

interface StatementFormatQuery {
    fun formats(registryCode: String? = null): List<StatementFormatView>

    /** The registry's own profile of that kind (or the named one), falling back to the generic `*` profile. */
    fun resolve(registryCode: String, kind: StatementKind, profileCode: String? = null): StatementFormatView
}

interface StatementFormatCommand {
    fun upsert(command: UpsertStatementFormat): StatementFormatView
}

/** One line of a registry transaction export. */
data class RegistryTransactionLine(
    val lineNo: Int,
    val externalRef: String?,
    val instructionKey: String?,
    val transactionType: InstructionType?,
    val issuanceRef: String?,
    val range: CertificateRange?,
    val status: TransactionLineStatus,
    val transactionDate: java.time.LocalDate?,
    val raw: Map<String, String>,
)

enum class TransactionLineStatus { COMPLETED, FAILED, OTHER }

data class TransactionImportReport(
    val registryCode: String,
    val lines: Int,
    val confirmed: List<UUID>,
    val failed: List<UUID>,
    val alreadyFinal: Int,
    val unmatched: List<RegistryTransactionLine>,
    val documentId: UUID?,
)

interface RegistryStatementCommand {
    /**
     * Parses a holdings export (certificates, or balances for an allowance registry) with the registry's format
     * profile, stores it in inventory and reconciles it.
     */
    fun importHoldings(
        registryAccountId: UUID,
        statementDate: java.time.LocalDate,
        source: String,
        content: String,
        profileCode: String? = null,
    ): com.pianonurmi.etrm.inventory.api.StatementView

    /** Confirms or fails open instructions from a registry transaction export (matched by reference or range). */
    fun importTransactions(registryCode: String, source: String, content: String, profileCode: String? = null): TransactionImportReport

    /** Parses without storing; for previews and tests. */
    fun parseHoldings(
        registryAccountId: UUID,
        statementDate: java.time.LocalDate,
        source: String,
        content: String,
        profileCode: String? = null,
    ): ImportStatement
}

// --------------------------------------------------------------------------------------------------------------
// Events
// --------------------------------------------------------------------------------------------------------------

sealed interface InstructionEvent : DomainEvent {
    val instruction: RegistryInstructionView
}

data class InstructionSubmitted(override val instruction: RegistryInstructionView, val metadata: EventMetadata = EventMetadata()) :
    InstructionEvent, DomainEvent by metadata

data class InstructionConfirmed(
    override val instruction: RegistryInstructionView,
    /** The lot after confirmation (TRANSFERRED_OUT or CANCELLED); null for allowance instructions. */
    val lot: LotView?,
    /** The allowance holding after confirmation; null for certificate instructions. */
    val holding: com.pianonurmi.etrm.inventory.api.AllowanceHoldingView? = null,
    val metadata: EventMetadata = EventMetadata(),
) : InstructionEvent, DomainEvent by metadata

data class InstructionCancelled(
    override val instruction: RegistryInstructionView,
    val reason: String,
    val metadata: EventMetadata = EventMetadata(),
) : InstructionEvent, DomainEvent by metadata

/** The registry call's outcome is not known (timeout, lost lease); resolved by status query or operator. */
data class InstructionOutcomeUnknown(
    override val instruction: RegistryInstructionView,
    val reason: String,
    val metadata: EventMetadata = EventMetadata(),
) : InstructionEvent, DomainEvent by metadata

/** No confirmation arrived within the channel's confirmation SLA. */
data class InstructionOverdue(override val instruction: RegistryInstructionView, val metadata: EventMetadata = EventMetadata()) :
    InstructionEvent, DomainEvent by metadata

data class InstructionFailed(
    override val instruction: RegistryInstructionView,
    val reason: String,
    val metadata: EventMetadata = EventMetadata(),
) : InstructionEvent, DomainEvent by metadata

/**
 * A registry's dispatch circuit (increment 16 M3): after `etrm.registry.circuit.failures` transport failures in a
 * row the registry is [paused] and its instructions wait; after the cool-down one instruction goes as a probe
 * ([probeAfter]), and the registry's answer, or an operator's resume, closes the circuit.
 */
data class RegistryCircuitView(
    val registryCode: String,
    val paused: Boolean,
    val consecutiveFailures: Int,
    val lastFailure: String?,
    val pausedSince: Instant?,
    val probeAfter: Instant?,
    val resumedBy: String?,
    val resumedAt: Instant?,
)

interface RegistryCircuitQuery {
    /** Every registry that has failed at least once since its last answer, paused or not. */
    fun circuits(): List<RegistryCircuitView>
}

interface RegistryCircuitCommand {
    /** Closes a paused registry's circuit, so its instructions are dispatched again at the next run. */
    fun resume(registryCode: String): RegistryCircuitView
}
