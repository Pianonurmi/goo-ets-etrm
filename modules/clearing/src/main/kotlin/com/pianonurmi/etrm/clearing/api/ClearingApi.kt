package com.pianonurmi.etrm.clearing.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Where an entity's futures are cleared: an account at a clearing house, held through a clearing member. */
data class ClearingAccountView(
    val id: UUID,
    val code: String,
    val legalEntityId: UUID,
    val clearingHouseId: UUID,
    val clearingMember: String,
    val currency: String,
    val active: Boolean,
    val createdBy: String,
    val createdAt: Instant,
)

data class CreateClearingAccount(
    val code: String,
    val legalEntityId: UUID,
    val clearingHouseId: UUID,
    val clearingMember: String,
    val currency: String = "EUR",
)

/**
 * One contract's variation margin on a business day: yesterday's position at the move of the settlement price, plus
 * today's trades against their trade price. Quantities in tonnes, signed (+ long, − short); money in [currency].
 */
data class MarginLineView(
    val contractCode: String,
    val positionBefore: BigDecimal,
    val tradedQuantity: BigDecimal,
    val positionAfter: BigDecimal,
    val settlementPrice: BigDecimal,
    val previousSettlementPrice: BigDecimal?,
    val variationMargin: BigDecimal,
)

/**
 * An account's variation margin of one business day (ADR 0046). Runs are immutable; a later version for the same day
 * (trades amended or prices corrected) supersedes the earlier one, and the ledger posts the difference.
 */
data class MarginRunView(
    val id: UUID,
    val accountId: UUID,
    val legalEntityId: UUID,
    val businessDate: LocalDate,
    val version: Int,
    val currency: String,
    val variationMargin: BigDecimal,
    val lines: List<MarginLineView>,
    val inputsHash: String,
    val computedBy: String,
    val computedAt: Instant,
    val supersededAt: Instant?,
)

interface ClearingQuery {
    fun accounts(legalEntityId: UUID? = null): List<ClearingAccountView>

    fun account(id: UUID): ClearingAccountView?

    /** The current run of each business day in [from]..[to], newest first. */
    fun runs(accountId: UUID, from: LocalDate, to: LocalDate): List<MarginRunView>

    fun run(id: UUID): MarginRunView?

    /** Futures trades of the entity cleared through one of its accounts: their value is settled daily, not carried as MtM. */
    fun marginedTradeIds(legalEntityId: UUID): Set<UUID>
}

interface ClearingCommand {
    fun createAccount(command: CreateClearingAccount): ClearingAccountView

    /**
     * Computes the variation margin of [businessDate]; returns the current run when nothing changed, or a new version
     * when trades or prices did. Refused without a settlement price for a contract with a position or a trade.
     */
    fun computeMargin(accountId: UUID, businessDate: LocalDate): MarginRunView
}

/** A run became current; [previous] is the variation margin of the version it supersedes (zero for the first). */
data class VariationMarginComputed(val run: MarginRunView, val previous: BigDecimal, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata
