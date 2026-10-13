package com.pianonurmi.etrm.clearing

import com.pianonurmi.etrm.clearing.api.ClearingStatementView
import com.pianonurmi.etrm.clearing.api.MarginDifferenceKind
import com.pianonurmi.etrm.clearing.api.MarginLineView
import com.pianonurmi.etrm.clearing.api.MarginRunView
import com.pianonurmi.etrm.clearing.domain.MarginReconciliation
import com.pianonurmi.etrm.clearing.domain.StatementFile
import com.pianonurmi.etrm.platform.errors.ValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class StatementFileTest {
    private val header = "Date,Account,Record,Contract,Position,SettlementPrice,Amount,Currency\n"

    @Test
    fun `a statement file gives one day per date, with fees by contract and the account's totals`() {
        val days = StatementFile.parse(
            header +
                "2026-10-06,ECC-1,VM,EUAZ26,3000,71.00,3400.00,EUR\n" +
                "2026-10-06,ECC-1,FEE,EUAZ26,,,4.00,EUR\n" +
                "2026-10-06,ECC-1,FEE,,,,25.00,EUR\n" +
                "2026-10-06,ECC-1,IM,,,,18000.00,EUR\n" +
                "2026-10-05,ecc-1,VM,euaz26,5000,,2000,EUR\n" +
                "2026-10-05,ECC-1,CASH,,,,100000.00,EUR\n",
            "ECC-1",
            "EUR",
        )
        days.map { it.businessDate } shouldBe listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6))
        days[0].variationMargin shouldBe BigDecimal("2000.00")
        days[0].cashBalance shouldBe BigDecimal("100000.00")
        days[0].initialMargin shouldBe BigDecimal("0.00")
        days[1].fees shouldBe BigDecimal("29.00")
        days[1].lines.single().fees shouldBe BigDecimal("4.00")
        days[1].initialMargin shouldBe BigDecimal("18000.00")
    }

    @Test
    fun `errors name their line, and the header, account and currency must be the account's`() {
        shouldThrow<ValidationException> { StatementFile.parse("Date,Amount\n", "ECC-1", "EUR") }.message!! shouldContain "header"
        shouldThrow<ValidationException> {
            StatementFile.parse(
                header + "2026-10-06,ECC-2,VM,EUAZ26,1,,1.00,EUR\n2026-10-06,ECC-1,VM,EUAZ26,1,,1.001,EUR\n2026-10-06,ECC-1,IM,,,,1.00,USD\n",
                "ECC-1",
                "EUR",
            )
        }.message!!.let {
            it shouldContain "line 2: account ECC-2"
            it shouldContain "line 3: amount 1.001"
            it shouldContain "line 4: currency USD"
        }
        shouldThrow<ValidationException> {
            StatementFile.parse(header + "2026-10-06,ECC-1,IM,,,,1.00,EUR\n2026-10-06,ECC-1,IM,,,,2.00,EUR\n", "ECC-1", "EUR")
        }.message!! shouldContain "2 IM records"
    }

    @Test
    fun `reconciliation compares position, margin and settlement price per contract`() {
        val run = MarginRunView(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 10, 6),
            1,
            "EUR",
            BigDecimal("3400.00"),
            listOf(
                MarginLineView(
                    "EUAZ26",
                    BigDecimal(5000),
                    BigDecimal(-2000),
                    BigDecimal(3000),
                    BigDecimal("71.00"),
                    BigDecimal("70.40"),
                    BigDecimal("3400.00"),
                ),
            ),
            "h",
            "x",
            Instant.EPOCH,
            null,
        )

        fun statement(vararg lines: com.pianonurmi.etrm.clearing.api.StatementLineView) = ClearingStatementView(
            UUID.randomUUID(),
            run.accountId,
            run.legalEntityId,
            run.businessDate,
            1,
            "EUR",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            lines.toList(),
            "h",
            null,
            "x",
            Instant.EPOCH,
            null,
        )
        val line = {
            position: Int,
            price: String?,
            vm: String,
            ->
            com.pianonurmi.etrm.clearing.api.StatementLineView("EUAZ26", BigDecimal(position), price?.let(::BigDecimal), BigDecimal(vm), BigDecimal.ZERO)
        }
        MarginReconciliation.differences(statement(line(3000, "71.0", "3400.00")), run).shouldBeEmpty()
        MarginReconciliation.differences(statement(line(2000, "71.05", "3500.00")), run).map { it.kind } shouldBe
            listOf(MarginDifferenceKind.POSITION, MarginDifferenceKind.VARIATION_MARGIN, MarginDifferenceKind.SETTLEMENT_PRICE)
        MarginReconciliation.differences(statement(), run).map { it.kind } shouldBe listOf(MarginDifferenceKind.POSITION, MarginDifferenceKind.VARIATION_MARGIN)
        MarginReconciliation.differences(statement(line(3000, null, "3400.00")), null).single().kind shouldBe MarginDifferenceKind.NO_RUN
        MarginReconciliation.differences(statement(line(0, null, "0.00")), null).shouldBeEmpty()
    }
}
