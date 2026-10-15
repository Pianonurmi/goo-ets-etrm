package com.pianonurmi.etrm.clearing.domain

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.MarginLineView
import com.pianonurmi.etrm.clearing.api.MarginRunView
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ClearingRepository {
    fun insertAccount(account: ClearingAccountView)

    fun accounts(legalEntityId: UUID?): List<ClearingAccountView>

    fun account(id: UUID): ClearingAccountView?

    fun insertRun(run: MarginRunView)

    /** The current (not superseded) run of the day, locked for a new version when [lock]. */
    fun currentRun(accountId: UUID, businessDate: LocalDate, lock: Boolean = false): MarginRunView?

    fun runs(accountId: UUID, from: LocalDate, to: LocalDate): List<MarginRunView>

    /** The business dates after [date] that have a current run, earliest first. */
    fun laterRunDates(accountId: UUID, date: LocalDate): List<LocalDate>

    fun run(id: UUID): MarginRunView?

    fun nextVersion(accountId: UUID, businessDate: LocalDate): Int

    fun supersede(runId: UUID, at: Instant)

    /** Marks the run stale with [reason], or clears the mark with null. */
    fun markStale(runId: UUID, reason: String?)
}

/**
 * Variation margin of one contract on one business day (ADR 0046): the position held overnight earns the move of the
 * settlement price, and each trade of the day earns the difference between the settlement price and its trade price.
 * Quantities are signed tonnes (+ bought, − sold); the result is rounded to cents once per contract.
 */
object VariationMargin {
    data class Fill(val quantity: BigDecimal, val price: BigDecimal)

    fun line(
        contractCode: String,
        positionBefore: BigDecimal,
        todays: List<Fill>,
        settlementPrice: BigDecimal,
        previousSettlementPrice: BigDecimal?,
    ): MarginLineView {
        require(positionBefore.signum() == 0 || previousSettlementPrice != null) { "$contractCode has a position but no previous settlement price" }
        val carried = if (positionBefore.signum() == 0) BigDecimal.ZERO else positionBefore * (settlementPrice - previousSettlementPrice!!)
        val traded = todays.fold(BigDecimal.ZERO) { sum, f -> sum + f.quantity * (settlementPrice - f.price) }
        val quantity = todays.fold(BigDecimal.ZERO) { sum, f -> sum + f.quantity }
        return MarginLineView(
            contractCode = contractCode,
            positionBefore = positionBefore,
            tradedQuantity = quantity,
            positionAfter = positionBefore + quantity,
            settlementPrice = settlementPrice,
            previousSettlementPrice = previousSettlementPrice,
            variationMargin = (carried + traded).setScale(2, RoundingMode.HALF_EVEN),
        )
    }
}
