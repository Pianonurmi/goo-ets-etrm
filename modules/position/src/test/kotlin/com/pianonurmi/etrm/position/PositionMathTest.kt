package com.pianonurmi.etrm.position

import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.position.api.BucketPosition
import com.pianonurmi.etrm.position.application.PositionLot
import com.pianonurmi.etrm.position.application.PositionMath
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class PositionMathTest {
    private val today = LocalDate.of(2026, 10, 16)

    private fun lot(bucket: String?, year: Int, status: LotStatus, mwh: Long, expiresInDays: Long = 365) =
        PositionLot(bucket, year, status, mwh, today.plusDays(expiresInDays))

    @Test
    fun `nets live lots by bucket and vintage, sorted, with lots outside a bucket grouped apart`() {
        val positions = PositionMath.inventory(
            listOf(
                lot("NORDIC_HYDRO", 2026, LotStatus.HELD, 100),
                lot("NORDIC_HYDRO", 2026, LotStatus.RESERVED, 40),
                lot("NORDIC_HYDRO", 2026, LotStatus.IN_TRANSFER, 10),
                lot("NORDIC_HYDRO", 2025, LotStatus.HELD, 7),
                lot("EU_WIND", 2026, LotStatus.HELD, 5),
                lot(null, 2026, LotStatus.HELD, 3),
            ),
            today,
        )
        positions shouldContainExactly listOf(
            BucketPosition("EU_WIND", 2026, 5, 0, 0, 0, 1),
            BucketPosition("NORDIC_HYDRO", 2025, 7, 0, 0, 0, 1),
            BucketPosition("NORDIC_HYDRO", 2026, 100, 40, 10, 0, 3),
            BucketPosition(PositionMath.UNBUCKETED, 2026, 3, 0, 0, 0, 1),
        )
        positions.single { it.bucketCode == "NORDIC_HYDRO" && it.productionYear == 2026 }.liveMwh shouldBe 150
    }

    @Test
    fun `counts what expires within 90 days, today and the 90th day included`() {
        val positions = PositionMath.inventory(
            listOf(
                lot("B", 2026, LotStatus.HELD, 1, expiresInDays = 0),
                lot("B", 2026, LotStatus.RESERVED, 10, expiresInDays = 90),
                lot("B", 2026, LotStatus.HELD, 100, expiresInDays = 91),
                lot("B", 2026, LotStatus.HELD, 1000, expiresInDays = -1),
            ),
            today,
        )
        positions.single().expiringWithin90DaysMwh shouldBe 11
        positions.single().lotCount shouldBe 4
    }

    @Test
    fun `an empty inventory has no positions`() {
        PositionMath.inventory(emptyList(), today).shouldBeEmpty()
    }
}
