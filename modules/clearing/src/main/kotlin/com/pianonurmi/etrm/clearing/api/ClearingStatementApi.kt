package com.pianonurmi.etrm.clearing.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One contract on the clearing member's statement: the position after the day, its margin and the fees charged on it. */
data class StatementLineView(
    val contractCode: String,
    val position: BigDecimal,
    val settlementPrice: BigDecimal?,
    val variationMargin: BigDecimal,
    val fees: BigDecimal,
)

/**
 * The clearing member's statement of one business day (ADR 0046): variation margin per contract, the initial margin
 * required at the close, fees charged and the cash balance. A re-import with other figures supersedes it.
 */
data class ClearingStatementView(
    val id: UUID,
    val accountId: UUID,
    val legalEntityId: UUID,
    val businessDate: LocalDate,
    val version: Int,
    val currency: String,
    val variationMargin: BigDecimal,
    val initialMargin: BigDecimal,
    val fees: BigDecimal,
    val cashBalance: BigDecimal?,
    val lines: List<StatementLineView>,
    val contentHash: String,
    val fileName: String?,
    val importedBy: String,
    val importedAt: Instant,
    val supersededAt: Instant?,
)

enum class ClearingBreakStatus {
    OPEN,

    /** The statement and the computed margin agree again (a trade or price was corrected, or the statement re-sent). */
    MATCHED,

    /** A second person accepted the member's figures with a reason. */
    ACCEPTED,
}

enum class MarginDifferenceKind {
    /** The statement shows margin or positions, but no margin has been computed for the day. */
    NO_RUN,
    POSITION,
    VARIATION_MARGIN,
    SETTLEMENT_PRICE,
}

/** What we computed ([ours]) against what the member reported ([theirs]); per contract, or for the day. */
data class MarginDifference(val kind: MarginDifferenceKind, val contractCode: String?, val ours: BigDecimal?, val theirs: BigDecimal?)

/** The differences of one account and day between the current statement and the current margin run. */
data class ClearingBreakView(
    val id: UUID,
    val accountId: UUID,
    val legalEntityId: UUID,
    val businessDate: LocalDate,
    val statementId: UUID,
    val runId: UUID?,
    val status: ClearingBreakStatus,
    val differences: List<MarginDifference>,
    val openedBy: String,
    val openedAt: Instant,
    val closedBy: String?,
    val closedAt: Instant?,
    val resolution: String?,
)

/** The result of a statement file: the statements stored (new or new versions), those unchanged, and the open breaks. */
data class StatementImportView(val imported: List<ClearingStatementView>, val unchanged: Int, val breaks: List<ClearingBreakView>)

interface ClearingStatementQuery {
    /** The current statement of each business day in [from]..[to], newest first. */
    fun statements(accountId: UUID, from: LocalDate, to: LocalDate): List<ClearingStatementView>

    fun statement(id: UUID): ClearingStatementView?

    fun breaks(legalEntityId: UUID? = null, status: ClearingBreakStatus? = null): List<ClearingBreakView>

    fun clearingBreak(id: UUID): ClearingBreakView?
}

interface ClearingStatementCommand {
    /** Imports the member's statement file (CSV, one or more days) and reconciles each day against the margin. */
    fun importStatements(accountId: UUID, fileName: String?, csv: String): StatementImportView
}

/**
 * A statement became current. [initialMarginChange] moves cash at the member into initial margin (only a statement of
 * the account's latest day sets the level); [feesChange] is the fees of this day less those of the version it replaces.
 */
data class ClearingStatementImported(
    val statement: ClearingStatementView,
    val initialMarginChange: BigDecimal,
    val feesChange: BigDecimal,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata

data class ClearingBreakOpened(val clearingBreak: ClearingBreakView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/** The break closed: matched by a correction, or accepted in the inbox. */
data class ClearingBreakClosed(val clearingBreak: ClearingBreakView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
