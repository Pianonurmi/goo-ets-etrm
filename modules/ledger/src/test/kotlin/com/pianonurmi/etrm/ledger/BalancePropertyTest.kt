package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.api.JournalLineView
import com.pianonurmi.etrm.ledger.domain.EntryBuilder
import io.kotest.assertions.withClue
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A balanced entry in a transaction currency stays balanced in base currency after per-line rounding (review findings
 * D7 and C8): whatever the amounts, the number of lines and the rate, `balanceBase` absorbs the rounding cents, no base
 * amount turns negative, no line moves further from its exact value than the cents it absorbs, and `requireBalanced`
 * accepts the result. The seed is fixed for the build and set to a random value by the nightly property run
 * (`-Detrm.proptest.seed`), which prints it with any failure.
 */
@OptIn(ExperimentalKotest::class)
class BalancePropertyTest {
    private val seed = System.getProperty("etrm.proptest.seed")?.toLongOrNull() ?: 14L

    @Test
    fun `base amounts balance after rounding whatever the lines and the rate`() {
        runBlocking {
            checkAll(
                PropTestConfig(seed = seed, iterations = 500),
                Arb.list(Arb.long(1L..10_000_000_00L), 1..6),
                Arb.int(1..6),
                Arb.long(1L..20_000_000L),
            ) { debitCents, creditParts, rateMicros ->
                val total = debitCents.sum()
                val credits = split(total, creditParts.coerceAtMost(total.toInt().coerceAtLeast(1)))
                val rate = BigDecimal(rateMicros).movePointLeft(6)
                val lines = debitCents.map { line(it, debit = true, rate) } + credits.map { line(it, debit = false, rate) }
                val balanced = EntryBuilder.balanceBase(lines)
                withClue("seed $seed, rate $rate, debits $debitCents, credits $credits") {
                    EntryBuilder.requireBalanced(balanced)
                    balanced.all { it.baseDebit.signum() >= 0 && it.baseCredit.signum() >= 0 } shouldBe true
                    val tolerance = BigDecimal("0.01") * BigDecimal(lines.size)
                    balanced.all { (exact(it) - (it.baseDebit + it.baseCredit)).abs() <= tolerance } shouldBe true
                }
            }
        }
    }

    /** [total] cents in [parts] positive amounts, deterministic for a given total and count. */
    private fun split(total: Long, parts: Int): List<Long> {
        val share = total / parts
        return List(parts) { i -> if (i == parts - 1) total - share * (parts - 1) else share }.filter { it > 0 }
    }

    private fun line(cents: Long, debit: Boolean, rate: BigDecimal): JournalLineView {
        // As LedgerService.line: the amount at the column's scale, the base amount rounded per line.
        val amount = BigDecimal(cents).movePointLeft(2)
        val base = (amount * rate).setScale(2, RoundingMode.HALF_EVEN)
        return JournalLineView(
            lineNo = 0,
            accountCode = if (debit) "1400" else "2200",
            accountName = "",
            debit = if (debit) amount else BigDecimal.ZERO.setScale(2),
            credit = if (debit) BigDecimal.ZERO.setScale(2) else amount,
            currency = "SEK",
            fxRate = rate,
            baseDebit = if (debit) base else BigDecimal.ZERO.setScale(2),
            baseCredit = if (debit) BigDecimal.ZERO.setScale(2) else base,
            dimensions = emptyMap(),
            memo = null,
        )
    }

    private fun exact(line: JournalLineView): BigDecimal = (line.debit + line.credit) * line.fxRate
}
