package com.pianonurmi.etrm.clearing

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.MarginRunView
import com.pianonurmi.etrm.clearing.application.ClearingService
import com.pianonurmi.etrm.clearing.application.MarginJob
import com.pianonurmi.etrm.clearing.domain.ClearingRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class MarginJobTest {
    @Test
    fun `the job dates its work in Berlin, where the cron runs, not in the clock's zone`() {
        val accountId = UUID.randomUUID()
        val account = mockk<ClearingAccountView> {
            every { id } returns accountId
            every { code } returns "ECC-JOB"
            every { active } returns true
        }
        val repository = mockk<ClearingRepository> { every { accounts(null) } returns listOf(account) }
        val clearing = mockk<ClearingService> { every { computeMargin(any(), any()) } returns mockk<MarginRunView>() }
        // 22:30 UTC on Monday 5 October is 00:30 on Tuesday 6 October in Berlin (UTC+2).
        val clock = Clock.fixed(Instant.parse("2026-10-05T22:30:00Z"), ZoneOffset.UTC)

        MarginJob(clearing, repository, clock).run()

        verify(exactly = 1) { clearing.computeMargin(accountId, LocalDate.of(2026, 10, 6)) }
    }
}
