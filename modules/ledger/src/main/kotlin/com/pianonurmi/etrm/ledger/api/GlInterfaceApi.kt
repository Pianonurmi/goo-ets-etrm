package com.pianonurmi.etrm.ledger.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

data class GlExportFormatView(val code: String, val name: String, val verified: Boolean, val active: Boolean)

/** A closing balance of one GL account as the general ledger reports it, in base currency (debit positive). */
data class GlBalanceLine(val glAccount: String, val balance: BigDecimal, val currency: String)

/**
 * Closing balances per GL account at the end of a period (debit positive): in the sub-ledger, in what was exported
 * (and not rejected), and in the general ledger as imported.
 */
data class ReconciliationRow(val glAccount: String, val subLedger: BigDecimal, val exported: BigDecimal, val generalLedger: BigDecimal?) {
    /** Posted but not yet exported. */
    val notExported: BigDecimal get() = subLedger - exported

    /** Exported but not (or differently) in the general ledger; null when no GL balance was imported. */
    val glDifference: BigDecimal? get() = generalLedger?.let { exported - it }

    val reconciled: Boolean get() = notExported.signum() == 0 && glDifference?.signum() == 0
}

enum class RuleChangeStatus { PENDING, APPLIED, REJECTED }

data class PostingRuleChangeView(
    val id: UUID,
    val legalEntityId: UUID?,
    val ruleCode: String,
    val change: UpsertPostingRule,
    val status: RuleChangeStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
)

data class PostingRuleVersionView(
    val ruleCode: String,
    val legalEntityId: UUID?,
    val version: Int,
    val rule: UpsertPostingRule,
    val changeId: UUID?,
    val recordedAt: Instant,
)

/** Conditions a period must meet before it is hard-closed; which apply is configured (`etrm.ledger.hard-close-gates`). */
enum class HardCloseGate {
    /** Every journal entry of the period is in an export that was not rejected. */
    ALL_EXPORTED,

    /** The period's latest export was acknowledged by the general ledger. */
    LAST_EXPORT_ACKNOWLEDGED,

    /** The period end has been run for the period. */
    PERIOD_END_RUN,
}

interface GlInterfaceQuery {
    fun exports(legalEntityId: UUID, period: YearMonth?): List<GlExportView>

    fun formats(): List<GlExportFormatView>

    fun reconciliation(legalEntityId: UUID, period: YearMonth): List<ReconciliationRow>

    fun ruleChanges(status: RuleChangeStatus?): List<PostingRuleChangeView>

    fun ruleHistory(ruleCode: String): List<PostingRuleVersionView>

    /** The gates a hard close of the period would fail now. */
    fun hardCloseBlockers(legalEntityId: UUID, period: YearMonth): List<String>
}

interface GlInterfaceCommand {
    fun markDelivered(exportId: UUID): GlExportView

    fun acknowledge(exportId: UUID, reference: String): GlExportView

    /** The general ledger refused the file: its entries go into the next export. */
    fun reject(exportId: UUID, reason: String): GlExportView

    fun importBalances(legalEntityId: UUID, period: YearMonth, balances: List<GlBalanceLine>): List<ReconciliationRow>

    /** Proposes a new or changed posting rule; it applies when another accountant approves it (`POSTING_RULE_CHANGE`). */
    fun requestRuleChange(command: UpsertPostingRule): PostingRuleChangeView
}

data class GlExportStatusChanged(val export: GlExportView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class PostingRuleChangeRequested(val change: PostingRuleChangeView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
