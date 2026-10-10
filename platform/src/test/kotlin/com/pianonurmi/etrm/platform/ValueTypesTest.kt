package com.pianonurmi.etrm.platform

import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.money.Money
import com.pianonurmi.etrm.platform.money.Quantity
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.time.LocalDateRange
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class ValueTypesTest {
    @Test
    fun `money arithmetic requires matching currency`() {
        (Money.eur("10.50") + Money.eur("0.50")) shouldBe Money.eur("11.00")
        shouldThrow<IllegalArgumentException> { Money.eur("1") + Money("1", "SEK") }
    }

    @Test
    fun `money rounds half even to currency scale`() {
        Money.eur("2.345").rounded() shouldBe Money.eur("2.34")
        Money.eur("2.355").rounded() shouldBe Money.eur("2.36")
    }

    @Test
    fun `whole-only units reject fractions`() {
        shouldThrow<IllegalArgumentException> { Quantity(BigDecimal("1.5"), QuantityUnit.CERTIFICATE) }
        Quantity(BigDecimal("1.50"), QuantityUnit.MWH).amount shouldBe BigDecimal("1.50")
    }

    @Test
    fun `date ranges are closed-open`() {
        val jan = LocalDateRange.ofMonth(YearMonth.of(2026, 1))
        val feb = LocalDateRange.ofMonth(YearMonth.of(2026, 2))
        (LocalDate.of(2026, 1, 31) in jan) shouldBe true
        (LocalDate.of(2026, 2, 1) in jan) shouldBe false
        jan.overlaps(feb) shouldBe false
        jan.isAdjacentTo(feb) shouldBe true
        LocalDateRange.ofYear(2026).containsRange(jan) shouldBe true
        jan.intersect(LocalDateRange.inclusive(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15))) shouldBe
            LocalDateRange(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 1))
    }

    @Test
    fun `uuid v7 identifiers are time ordered`() {
        val first = Ids.newId()
        val second = Ids.newId()
        first.version() shouldBe 7
        (first.toString() < second.toString()) shouldBe true
    }

    @Test
    fun `hash chain is deterministic and tamper evident`() {
        val h1 = HashChain.next(null, "a")
        val h2 = HashChain.next(h1, "b")
        HashChain.next(null, "a") shouldBe h1
        HashChain.next(h1, "c") shouldNotBe h2
    }
}
