package com.pianonurmi.etrm.etscompliance

import com.pianonurmi.etrm.etscompliance.api.DeadlineKind
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionRecordView
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.EmissionsBasis
import com.pianonurmi.etrm.etscompliance.api.FreeAllocationStatus
import com.pianonurmi.etrm.etscompliance.api.FreeAllocationView
import com.pianonurmi.etrm.etscompliance.domain.DeadlineRule
import com.pianonurmi.etrm.etscompliance.domain.Deadlines
import com.pianonurmi.etrm.etscompliance.domain.EmissionsBasisRule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EtsComplianceRulesTest {
    private val deadlines = Deadlines(
        listOf(
            DeadlineRule(DeadlineKind.SURRENDER, 2005, 1, 4, 30),
            DeadlineRule(DeadlineKind.SURRENDER, 2024, 1, 9, 30),
            DeadlineRule(DeadlineKind.VERIFIED_EMISSIONS, 2005, 1, 3, 31),
            DeadlineRule(DeadlineKind.FREE_ALLOCATION, 2013, 0, 6, 30),
        ),
    )

    private fun record(kind: EmissionKind, quantity: Long, status: EmissionStatus = EmissionStatus.APPROVED, month: Int? = null) = EmissionRecordView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        2026,
        kind,
        month,
        quantity,
        status,
        null,
        null,
        null,
        null,
        "u",
        Instant.EPOCH,
        null,
        null,
    )

    @Test
    fun `the surrender deadline moved to 30 September from the 2024 compliance year`() {
        deadlines.dueOn(DeadlineKind.SURRENDER, 2023) shouldBe LocalDate.of(2024, 4, 30)
        deadlines.dueOn(DeadlineKind.SURRENDER, 2024) shouldBe LocalDate.of(2025, 9, 30)
        deadlines.dueOn(DeadlineKind.VERIFIED_EMISSIONS, 2026) shouldBe LocalDate.of(2027, 3, 31)
        deadlines.dueOn(DeadlineKind.FREE_ALLOCATION, 2026) shouldBe LocalDate.of(2026, 6, 30)
    }

    @Test
    fun `approved verified emissions win over monthly estimates, which win over the forecast`() {
        val forecast = record(EmissionKind.FORECAST, 12_000)
        val months = listOf(record(EmissionKind.MONTHLY_ESTIMATE, 900, month = 1), record(EmissionKind.MONTHLY_ESTIMATE, 1_100, month = 2))
        val pending = record(EmissionKind.VERIFIED, 11_500, EmissionStatus.PENDING_APPROVAL)
        EmissionsBasisRule.of(emptyList()) shouldBe (0L to EmissionsBasis.NONE)
        EmissionsBasisRule.of(listOf(forecast)) shouldBe (12_000L to EmissionsBasis.FORECAST)
        EmissionsBasisRule.of(listOf(forecast) + months + pending) shouldBe (2_000L to EmissionsBasis.MONTHLY_ESTIMATES)
        EmissionsBasisRule.of(listOf(forecast, pending.copy(status = EmissionStatus.APPROVED))) shouldBe (11_500L to EmissionsBasis.VERIFIED)
    }

    @Test
    fun `free allocation outstanding is the entitlement less what was issued, nothing when withheld`() {
        val allocation = FreeAllocationView(UUID.randomUUID(), 2026, 10_000, 9_000, FreeAllocationStatus.EXPECTED, 4_000, null)
        allocation.outstanding shouldBe 5_000
        allocation.copy(received = 9_500).outstanding shouldBe 0
        allocation.copy(status = FreeAllocationStatus.WITHHELD).outstanding shouldBe 0
    }
}
