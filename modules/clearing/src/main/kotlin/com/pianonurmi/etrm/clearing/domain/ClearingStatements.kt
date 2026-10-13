package com.pianonurmi.etrm.clearing.domain

import com.pianonurmi.etrm.clearing.api.ClearingBreakStatus
import com.pianonurmi.etrm.clearing.api.ClearingBreakView
import com.pianonurmi.etrm.clearing.api.ClearingStatementView
import com.pianonurmi.etrm.clearing.api.MarginDifference
import com.pianonurmi.etrm.clearing.api.MarginDifferenceKind
import com.pianonurmi.etrm.clearing.api.MarginRunView
import com.pianonurmi.etrm.clearing.api.StatementLineView
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.text.Csv
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

interface ClearingStatementRepository {
    fun insertStatement(statement: ClearingStatementView)

    /** The current statement of the day, locked for a new version when [lock]. */
    fun currentStatement(accountId: UUID, businessDate: LocalDate, lock: Boolean = false): ClearingStatementView?

    fun statements(accountId: UUID, from: LocalDate, to: LocalDate): List<ClearingStatementView>

    fun statement(id: UUID): ClearingStatementView?

    fun nextStatementVersion(accountId: UUID, businessDate: LocalDate): Int

    fun supersedeStatement(id: UUID, at: Instant)

    /** The latest business day with a current statement. */
    fun latestStatementDate(accountId: UUID): LocalDate?

    /** Locks the account row; statements of one account are imported one at a time. */
    fun lockAccount(accountId: UUID)

    fun setInitialMargin(accountId: UUID, initialMargin: BigDecimal)

    fun insertBreak(clearingBreak: ClearingBreakView)

    fun updateBreak(clearingBreak: ClearingBreakView)

    fun openBreak(accountId: UUID, businessDate: LocalDate): ClearingBreakView?

    fun breaks(legalEntityId: UUID?, status: ClearingBreakStatus?): List<ClearingBreakView>

    fun clearingBreak(id: UUID, lock: Boolean = false): ClearingBreakView?
}

/**
 * The clearing member's daily statement as CSV (ADR 0046), one or more business days per file:
 *
 * `Date,Account,Record,Contract,Position,SettlementPrice,Amount,Currency`
 *
 * - `VM`: a contract's variation margin of the day (Amount, + received), its position after the day in tonnes
 *   (+ long) and optionally the settlement price;
 * - `IM`: the initial margin required at the close (Amount, at most one per day; none means none required);
 * - `FEE`: fees charged (Amount, + charged), on a contract or on the account;
 * - `CASH`: the cash balance at the member after the day (Amount, at most one per day).
 */
object StatementFile {
    data class Day(
        val businessDate: LocalDate,
        val lines: List<StatementLineView>,
        val initialMargin: BigDecimal,
        val fees: BigDecimal,
        val cashBalance: BigDecimal?,
    ) {
        val variationMargin: BigDecimal get() = lines.fold(BigDecimal.ZERO.setScale(2)) { sum, l -> sum + l.variationMargin }
    }

    val HEADER = listOf("Date", "Account", "Record", "Contract", "Position", "SettlementPrice", "Amount", "Currency")

    private data class Row(
        val number: Int,
        val date: LocalDate,
        val record: String,
        val contract: String?,
        val position: BigDecimal?,
        val price: BigDecimal?,
        val amount: BigDecimal,
    )

    fun parse(csv: String, accountCode: String, currency: String): List<Day> {
        val records = Csv.parse(csv.removePrefix("﻿")).filter { r -> r.any { it.isNotBlank() } }
        if (records.isEmpty()) throw ValidationException("The statement file is empty")
        if (records.first().map { it.trim().lowercase() } != HEADER.map { it.lowercase() }) {
            throw ValidationException("The statement file starts with the header ${HEADER.joinToString(",")}")
        }
        val problems = mutableListOf<String>()
        val rows = records.drop(1).mapIndexedNotNull { index, fields ->
            val number = index + 2
            try {
                row(number, fields, accountCode, currency)
            } catch (e: ValidationException) {
                problems += "line $number: ${e.message}"
                null
            }
        }
        if (problems.isNotEmpty()) throw ValidationException("The statement file has errors: ${problems.take(MAX_PROBLEMS).joinToString("; ")}")
        if (rows.isEmpty()) throw ValidationException("The statement file has no records")
        return rows.groupBy { it.date }.toSortedMap().map { (date, day) -> day(date, day) }
    }

    private fun row(number: Int, fields: List<String>, accountCode: String, currency: String): Row {
        if (fields.size != HEADER.size) throw ValidationException("expected ${HEADER.size} fields, found ${fields.size}")
        val f = fields.map { it.trim() }
        val account = f[1]
        val record = f[2]
        val contract = f[3]
        val position = f[4]
        val price = f[5]
        val ccy = f[7]
        if (!account.equals(accountCode, ignoreCase = true)) throw ValidationException("account $account is not $accountCode")
        if (!ccy.equals(currency, ignoreCase = true)) throw ValidationException("currency $ccy is not the account's $currency")
        val type = record.uppercase()
        if (type !in RECORDS) throw ValidationException("record $record is not one of ${RECORDS.joinToString()}")
        if (type == "VM" && contract.isEmpty()) throw ValidationException("a VM record names its contract")
        if (type == "VM" && position.isEmpty()) throw ValidationException("a VM record gives the position")
        val parsed = Row(
            number,
            date(f[0]),
            type,
            contract.uppercase().ifEmpty { null },
            position.ifEmpty { null }?.let { decimal(it, "position") },
            price.ifEmpty { null }?.let { decimal(it, "settlement price") },
            money(f[6]),
        )
        if (parsed.price != null && parsed.price.signum() <= 0) throw ValidationException("the settlement price must be positive")
        if (type == "IM" && parsed.amount.signum() < 0) throw ValidationException("initial margin cannot be negative")
        return parsed
    }

    private fun day(date: LocalDate, rows: List<Row>): Day {
        val single = { type: String ->
            val of = rows.filter { it.record == type }
            if (of.size > 1) throw ValidationException("$date has ${of.size} $type records; one is expected")
            of.singleOrNull()?.amount
        }
        val vm = rows.filter { it.record == "VM" }
        vm.groupBy { it.contract }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw ValidationException("$date has more than one VM record for $it")
        }
        val fees = rows.filter { it.record == "FEE" }
        val contracts = (vm.map { it.contract!! } + fees.mapNotNull { it.contract }).toSortedSet()
        val lines = contracts.map { code ->
            val margin = vm.firstOrNull { it.contract == code }
            StatementLineView(
                contractCode = code,
                position = margin?.position ?: BigDecimal.ZERO,
                settlementPrice = margin?.price,
                variationMargin = margin?.amount ?: BigDecimal.ZERO.setScale(2),
                fees = fees.filter { it.contract == code }.fold(BigDecimal.ZERO.setScale(2)) { s, f -> s + f.amount },
            )
        }
        return Day(
            businessDate = date,
            lines = lines,
            initialMargin = single("IM") ?: BigDecimal.ZERO.setScale(2),
            fees = fees.fold(BigDecimal.ZERO.setScale(2)) { s, f -> s + f.amount },
            cashBalance = single("CASH"),
        )
    }

    private fun date(text: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        throw ValidationException("date $text is not YYYY-MM-DD")
    }

    private fun decimal(text: String, what: String): BigDecimal = try {
        BigDecimal(text)
    } catch (_: NumberFormatException) {
        throw ValidationException("$what $text is not a number")
    }

    private fun money(text: String): BigDecimal = try {
        decimal(text, "amount").setScale(2, RoundingMode.UNNECESSARY)
    } catch (_: ArithmeticException) {
        throw ValidationException("amount $text has more than two decimals")
    }

    private val RECORDS = setOf("VM", "IM", "FEE", "CASH")
    private const val MAX_PROBLEMS = 10
}

/**
 * Statement against computed margin, contract by contract: the position after the day, the variation margin and,
 * where the member gives it, the settlement price. A contract missing on one side counts as flat with no margin.
 */
object MarginReconciliation {
    fun differences(statement: ClearingStatementView, run: MarginRunView?): List<MarginDifference> {
        if (run == null) {
            val active = statement.lines.any { it.position.signum() != 0 || it.variationMargin.signum() != 0 }
            return if (active) listOf(MarginDifference(MarginDifferenceKind.NO_RUN, null, null, statement.variationMargin)) else emptyList()
        }
        val ours = run.lines.associateBy { it.contractCode }
        val theirs = statement.lines.associateBy { it.contractCode }
        return (ours.keys + theirs.keys).sorted().flatMap { code ->
            val o = ours[code]
            val t = theirs[code]
            buildList {
                val ourPosition = o?.positionAfter ?: BigDecimal.ZERO
                val theirPosition = t?.position ?: BigDecimal.ZERO
                if (ourPosition.compareTo(theirPosition) != 0) add(MarginDifference(MarginDifferenceKind.POSITION, code, ourPosition, theirPosition))
                val ourMargin = o?.variationMargin ?: BigDecimal.ZERO
                val theirMargin = t?.variationMargin ?: BigDecimal.ZERO
                if (ourMargin.compareTo(theirMargin) != 0) add(MarginDifference(MarginDifferenceKind.VARIATION_MARGIN, code, ourMargin, theirMargin))
                val theirPrice = t?.settlementPrice
                if (o != null && theirPrice != null && o.settlementPrice.compareTo(theirPrice) != 0) {
                    add(MarginDifference(MarginDifferenceKind.SETTLEMENT_PRICE, code, o.settlementPrice, theirPrice))
                }
            }
        }
    }

    /** Differences compare by value, so 70.4 and 70.40 are the same. */
    fun same(a: List<MarginDifference>, b: List<MarginDifference>): Boolean = a.size == b.size &&
        a.zip(b).all { (x, y) -> x.kind == y.kind && x.contractCode == y.contractCode && x.ours.eq(y.ours) && x.theirs.eq(y.theirs) }

    private fun BigDecimal?.eq(other: BigDecimal?) = if (this == null || other == null) this == other else compareTo(other) == 0
}
