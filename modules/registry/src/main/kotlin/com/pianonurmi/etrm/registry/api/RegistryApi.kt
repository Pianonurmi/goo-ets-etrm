package com.pianonurmi.etrm.registry.api

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

enum class InstructionType { TRANSFER, CANCELLATION }

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
    val lotId: UUID,
    val range: CertificateRange,
    val quantityMwh: Long,
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
)

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

interface RegistryInstructionCommand {
    fun submitTransfer(command: SubmitTransfer): RegistryInstructionView

    fun submitCancellation(command: SubmitCancellation): RegistryInstructionView

    /** Operator or adapter confirmation of an instruction executed in the registry. */
    fun confirm(instructionId: UUID, externalRef: String?): RegistryInstructionView

    fun fail(instructionId: UUID, reason: String): RegistryInstructionView

    fun cancel(instructionId: UUID, reason: String): RegistryInstructionView

    /** Re-executes a FAILED or UNKNOWN instruction; needs a second person's approval like any manual instruction. */
    fun retry(instructionId: UUID): RegistryInstructionView
}

interface RegistryInstructionQuery {
    fun instruction(id: UUID): RegistryInstructionView?

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
    /** Parses a holdings export with the account's format profile, stores it in inventory and reconciles it. */
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
    /** The lot after confirmation (TRANSFERRED_OUT or CANCELLED). */
    val lot: LotView,
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
