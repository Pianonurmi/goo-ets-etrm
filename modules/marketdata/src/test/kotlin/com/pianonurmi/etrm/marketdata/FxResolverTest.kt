package com.pianonurmi.etrm.marketdata

import com.pianonurmi.etrm.marketdata.domain.DatedRate
import com.pianonurmi.etrm.marketdata.domain.FxResolver
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FxResolverTest {
    private val day = LocalDate.of(2026, 10, 9)
    private val published = mapOf(
        Triple("EUR", "SEK", day) to BigDecimal("11.00"),
        Triple("EUR", "USD", day.minusDays(3)) to BigDecimal("1.10"),
        Triple("EUR", "NOK", day.minusDays(30)) to BigDecimal("11.50"),
    )
    private val resolver = FxResolver(
        {
            date,
            from,
            to,
            ->
            published.filterKeys {
                it.first == from && it.second == to && !it.third.isAfter(date)
            }.maxByOrNull { it.key.third }?.let { DatedRate(it.key.third, it.value) }
        },
        maxStalenessDays = 5,
    )

    @Test
    fun `direct, inverse and cross rates`() {
        resolver.resolve(day, "EUR", "SEK")!!.rate shouldBe BigDecimal("11.00")
        resolver.resolve(day, "SEK", "EUR")!!.rate shouldBe BigDecimal("0.0909090909")
        resolver.resolve(day, "SEK", "USD")!!.let {
            it.rate shouldBe BigDecimal("0.1000000000")
            it.oldestDate shouldBe day.minusDays(3)
        }
        resolver.resolve(day, "EUR", "EUR")!!.rate shouldBe BigDecimal.ONE
    }

    @Test
    fun `stale or missing rates are not used`() {
        resolver.resolve(day, "EUR", "NOK") shouldBe null
        resolver.resolve(day, "SEK", "NOK") shouldBe null
        resolver.resolve(day, "EUR", "GBP") shouldBe null
        resolver.resolve(day.minusDays(1), "EUR", "SEK") shouldBe null
    }
}
