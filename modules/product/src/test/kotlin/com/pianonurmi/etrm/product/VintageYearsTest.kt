package com.pianonurmi.etrm.product

import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.VintageYears
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class VintageYearsTest {
    @Test
    fun `the vintage year is the year the criterion's window overlaps most`() {
        fun window(from: String, to: String) = BoundCriterion.ProductionPeriodWithin(LocalDateRange(LocalDate.parse(from), LocalDate.parse(to)))
        VintageYears.of(window("2026-01-01", "2027-01-01"), LocalDate.of(2027, 3, 1)) shouldBe 2026
        VintageYears.of(window("2025-10-01", "2026-04-01"), LocalDate.of(2026, 5, 1)) shouldBe 2025
        VintageYears.of(window("2025-07-01", "2026-07-01"), LocalDate.of(2026, 9, 1)) shouldBe 2025 // 184 days in 2025, 181 in 2026
        VintageYears.of(window("2025-07-05", "2026-07-05"), LocalDate.of(2026, 9, 1)) shouldBe 2026 // 180 days in 2025, 185 in 2026
        VintageYears.of(BoundCriterion.All(listOf(BoundCriterion.MatchAll, window("2024-01-01", "2025-01-01"))), LocalDate.of(2026, 9, 1)) shouldBe 2024
        VintageYears.of(BoundCriterion.MatchAll, LocalDate.of(2026, 9, 1)) shouldBe 2026
    }
}
