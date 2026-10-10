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
    PENDING,
    SENT,
    ACKNOWLEDGED,
    CONFIRMED,
    FAILED,
    CANCELLED,
    ;

    val terminal: Boolean get() = this == CONFIRMED || this == FAILED || this == CANCELLED
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

    /** Re-executes a PENDING or FAILED instruction through the gateway. */
    fun retry(instructionId: UUID): RegistryInstructionView
}

interface RegistryInstructionQuery {
    fun instruction(id: UUID): RegistryInstructionView?

    fun requireInstruction(id: UUID): RegistryInstructionView

    fun byKey(idempotencyKey: String): RegistryInstructionView?

    fun open(registryCode: String? = null): List<RegistryInstructionView>

    fun forPlan(planRef: UUID): List<RegistryInstructionView>
}

/** Parses a registry's account statement export into the inventory module's structured statement. */
interface StatementParser {
    val registryCode: String

    fun parse(registryAccountId: UUID, statementDate: java.time.LocalDate, source: String, content: String): ImportStatement
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

data class InstructionFailed(
    override val instruction: RegistryInstructionView,
    val reason: String,
    val metadata: EventMetadata = EventMetadata(),
) : InstructionEvent, DomainEvent by metadata
