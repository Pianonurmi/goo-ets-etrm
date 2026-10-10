package com.pianonurmi.etrm.inventory.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Device attributes supplied explicitly when the production device is not (yet) in reference data. */
data class DeviceAttributes(
    val name: String?,
    val country: String,
    val region: String? = null,
    val commissioningDate: LocalDate?,
    val capacityKw: BigDecimal?,
    val energySourceCode: String,
    val technologyCode: String?,
)

data class ReceiveLots(
    val holderEntityId: UUID,
    val registryAccountId: UUID,
    val issuanceRef: String,
    val range: CertificateRange,
    val deviceGsrn: String,
    val productionPeriod: LocalDateRange,
    val issueDate: LocalDate,
    /** Defaults to production period end + 12 months (RED II art. 19(3)). */
    val expiryDate: LocalDate? = null,
    val energyCarrier: EnergyCarrier = EnergyCarrier.ELECTRICITY,
    val certificateScheme: String = "EECS_GO",
    val issuingBody: String,
    val issuingCountry: String,
    val supportProduction: Boolean? = null,
    val supportInvestment: Boolean? = null,
    val supportSchemeCode: String? = null,
    val labels: Set<String> = emptySet(),
    val acquisitionSource: AcquisitionSource,
    val acquisitionRef: String? = null,
    val acquisitionDate: LocalDate = issueDate,
    val costBasisPerMwh: BigDecimal? = null,
    val costCurrency: String? = null,
    val deviceAttributes: DeviceAttributes? = null,
    val attributes: Map<String, Any?> = emptyMap(),
    val effectiveAt: Instant? = null,
)

data class SplitResult(val retained: LotView, val carved: LotView)

data class LotCorrection(
    val labels: Set<String>? = null,
    val supportProduction: Boolean? = null,
    val supportInvestment: Boolean? = null,
    val supportSchemeCode: String? = null,
    val expiryDate: LocalDate? = null,
    val costBasisPerMwh: BigDecimal? = null,
    val costCurrency: String? = null,
    val reason: String,
    val effectiveAt: Instant? = null,
)

interface LotCommand {
    fun receive(command: ReceiveLots): LotView

    /** Carves [quantity] certificates off the head of the lot into a new lot; the parent becomes SPLIT. */
    fun split(lotId: UUID, quantity: Long): SplitResult

    /** Hard reservation. A partial quantity splits the lot first and reserves the carved part. */
    fun reserve(lotId: UUID, quantity: Long?, reservationRef: UUID): LotView

    fun release(lotId: UUID, reservationRef: UUID): LotView

    fun startTransfer(lotId: UUID, instructionRef: UUID): LotView

    fun confirmTransferOut(lotId: UUID, instructionRef: UUID, externalRef: String?): LotView

    fun failTransfer(lotId: UUID, instructionRef: UUID, reason: String): LotView

    /** Cancels (retires) the lot or [quantity] of it for the given purpose. */
    fun cancel(lotId: UUID, quantity: Long?, details: CancellationDetails): LotView

    fun withdraw(lotId: UUID, reason: String): LotView

    fun correct(lotId: UUID, correction: LotCorrection): LotView

    /** Marks HELD lots whose expiry date has passed as EXPIRED. Returns the number of lots expired. */
    fun expireDueLots(asOf: LocalDate): Int
}

interface LotQuery {
    fun lot(id: UUID): LotView?

    fun requireLot(id: UUID): LotView

    fun lots(filter: LotFilter): List<LotView>

    /** SQL search of live lots by bound criteria (uses the criteria compiler). */
    fun search(holderEntityId: UUID, bound: BoundCriterion, statuses: Set<LotStatus> = setOf(LotStatus.HELD), limit: Int = 1000): List<LotView>

    fun history(lotId: UUID): List<LotEventView>

    /** Live inventory of one entity plus the journal position it reflects, for allocation runs. */
    fun snapshot(holderEntityId: UUID): LotSnapshot

    fun journalSeq(): Long

    /** Verifies the per-lot hash chains; returns the lot ids whose chain is broken. */
    fun verifyJournal(): List<UUID>

    /** The lot as the system knew it at [knownAt], effective on [effectiveAt]. */
    fun asOf(lotId: UUID, effectiveAt: Instant, knownAt: Instant): Map<String, Any?>?

    /** Lots expiring within the window grouped by month, for the expiry ladder. */
    fun expiryLadder(holderEntityId: UUID, from: LocalDate, months: Int): Map<LocalDate, Long>
}

// --------------------------------------------------------------------------------------------------------------
// Emission allowance holdings
// --------------------------------------------------------------------------------------------------------------

enum class AllowanceMovementType {
    FREE_ALLOCATION,
    PURCHASE,
    SALE,
    TRANSFER_IN,
    TRANSFER_OUT,
    SURRENDER,
    DELETION,
    OPENING_BALANCE,
    CORRECTION,
    ;

    val sign: Int get() = when (this) {
        FREE_ALLOCATION, PURCHASE, TRANSFER_IN, OPENING_BALANCE -> 1
        SALE, TRANSFER_OUT, SURRENDER, DELETION -> -1
        CORRECTION -> 0
    }
}

data class AllowanceHoldingView(
    val id: UUID,
    val holderEntityId: UUID,
    val registryAccountId: UUID,
    val productCode: String,
    val quantity: Long,
    val reservedQuantity: Long,
    val version: Int,
)

data class AllowanceMovementView(
    val seq: Long,
    val holdingId: UUID,
    val type: AllowanceMovementType,
    val quantity: Long,
    val effectiveDate: LocalDate,
    val reference: String?,
    val costPerUnit: BigDecimal?,
    val costCurrency: String?,
    val complianceYear: Int?,
    val actor: String,
    val recordedAt: Instant,
)

data class RecordAllowanceMovement(
    val holderEntityId: UUID,
    val registryAccountId: UUID,
    val productCode: String,
    val type: AllowanceMovementType,
    /** Absolute quantity; sign is derived from [type] except CORRECTION where the sign is given. */
    val quantity: Long,
    val effectiveDate: LocalDate,
    val reference: String? = null,
    val costPerUnit: BigDecimal? = null,
    val costCurrency: String? = null,
    val complianceYear: Int? = null,
)

interface AllowanceHoldingCommand {
    fun record(command: RecordAllowanceMovement): AllowanceHoldingView

    fun reserve(holdingId: UUID, quantity: Long): AllowanceHoldingView

    fun release(holdingId: UUID, quantity: Long): AllowanceHoldingView
}

interface AllowanceHoldingQuery {
    fun holdings(holderEntityId: UUID): List<AllowanceHoldingView>

    fun holding(registryAccountId: UUID, productCode: String): AllowanceHoldingView?

    fun movements(holdingId: UUID): List<AllowanceMovementView>
}

data class HoldingChanged(
    val holding: AllowanceHoldingView,
    val movement: AllowanceMovementView,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata

// --------------------------------------------------------------------------------------------------------------
// Registry statements and reconciliation
// --------------------------------------------------------------------------------------------------------------

data class StatementLine(
    val lineNo: Int,
    val issuanceRef: String,
    val range: CertificateRange,
    val deviceGsrn: String?,
    val productionPeriod: LocalDateRange?,
    val raw: Map<String, Any?> = emptyMap(),
)

data class ImportStatement(
    val registryCode: String,
    val registryAccountId: UUID,
    val statementDate: LocalDate,
    val source: String,
    val lines: List<StatementLine>,
)

enum class BreakType { MISSING_INTERNALLY, MISSING_IN_REGISTRY, ATTRIBUTE_MISMATCH }

enum class BreakStatus { OPEN, RESOLVED, ACCEPTED }

data class ReconciliationBreakView(
    val id: UUID,
    val statementId: UUID,
    val type: BreakType,
    val issuanceRef: String,
    val range: CertificateRange,
    val lotId: UUID?,
    val details: Map<String, Any?>,
    val status: BreakStatus,
    val resolution: String?,
)

enum class StatementStatus { IMPORTED, RECONCILED, BREAKS }

data class StatementView(
    val id: UUID,
    val registryCode: String,
    val registryAccountId: UUID,
    val statementDate: LocalDate,
    val source: String,
    val lineCount: Int,
    val status: StatementStatus,
    val breaks: List<ReconciliationBreakView>,
)

interface ReconciliationCommand {
    fun importStatement(command: ImportStatement): StatementView

    /** Compares statement lines with live lots of the account and records breaks. */
    fun reconcile(statementId: UUID): StatementView

    fun resolveBreak(breakId: UUID, status: BreakStatus, resolution: String): ReconciliationBreakView
}

interface ReconciliationQuery {
    fun statement(id: UUID): StatementView?

    fun openBreaks(registryAccountId: UUID? = null): List<ReconciliationBreakView>
}

data class ReconciliationBreakDetected(val breakView: ReconciliationBreakView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/**
 * A lot that was IN_TRANSFER no longer appears in its account's registry statement: the registry executed the
 * instruction. The registry module confirms the instruction from this.
 */
data class InTransferLotLeftAccount(
    val lotId: UUID,
    val instructionRef: UUID,
    val statementId: UUID,
    val statementDate: java.time.LocalDate,
    val registryCode: String,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata

data class ReconciliationBreakResolved(
    val breakView: ReconciliationBreakView,
    val resolvedBy: String,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata
