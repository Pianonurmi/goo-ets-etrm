package com.pianonurmi.etrm.marketdata

import com.pianonurmi.etrm.marketdata.api.CurveKind
import com.pianonurmi.etrm.marketdata.api.CurvePointView
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotView
import com.pianonurmi.etrm.marketdata.api.DayCount
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.Interpolation
import com.pianonurmi.etrm.marketdata.api.PointOrigin
import com.pianonurmi.etrm.marketdata.api.PriceOrigin
import com.pianonurmi.etrm.marketdata.api.QuoteQuality
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import com.pianonurmi.etrm.marketdata.api.SnapshotStatus
import com.pianonurmi.etrm.marketdata.domain.CurveBuilder
import com.pianonurmi.etrm.marketdata.domain.CurveMath
import com.pianonurmi.etrm.marketdata.domain.PillarInput
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CurveMathTest {
    private val asOf = LocalDate.of(2026, 10, 9)

    private fun snapshot(kind: CurveKind, vararg points: CurvePointView, interpolation: Interpolation = Interpolation.LINEAR) = CurveSnapshotView(
        UUID.randomUUID(),
        "C",
        kind,
        "EUR",
        asOf,
        1,
        SnapshotStatus.OFFICIAL,
        "h",
        emptyList(),
        null,
        "a",
        Instant.now(),
        "b",
        Instant.now(),
        null,
        points.toList(),
        if (kind == CurveKind.DISCOUNT) DayCount.ACT_360 else null,
        interpolation,
    )

    private fun dated(date: String, price: String) = CurvePointView(date, LocalDate.parse(date), null, BigDecimal(price), PointOrigin.QUOTE, emptyList(), null)

    private fun year(y: Int, price: String) = CurvePointView(y.toString(), null, y, BigDecimal(price), PointOrigin.QUOTE, emptyList(), null)

    @Test
    fun `forward prices interpolate linearly in days and stay flat beyond the pillars`() {
        val curve = snapshot(CurveKind.EUA_FORWARD, dated("2026-10-13", "70.00"), dated("2026-12-21", "72.00"), dated("2027-12-20", "76.00"))
        CurveMath.priceOn(curve, LocalDate.of(2026, 12, 21))!!.let {
            it.value shouldBe BigDecimal("72.00")
            it.origin shouldBe PriceOrigin.POINT
        }
        // 2026-11-16 is 34 of 69 days from spot to December: 70 + 2 x 34/69.
        CurveMath.priceOn(curve, LocalDate.of(2026, 11, 16))!!.let {
            it.value shouldBe BigDecimal("70.985507")
            it.origin shouldBe PriceOrigin.INTERPOLATED
        }
        CurveMath.priceOn(curve, asOf)!!.let {
            it.value shouldBe BigDecimal("70.00")
            it.origin shouldBe PriceOrigin.EXTRAPOLATED
        }
        CurveMath.priceOn(curve, LocalDate.of(2028, 6, 1))!!.origin shouldBe PriceOrigin.EXTRAPOLATED
        CurveMath.priceOn(snapshot(CurveKind.EUA_FORWARD), asOf) shouldBe null
    }

    @Test
    fun `a carry curve continues the last segment's carry beyond the last future and is linear before it`() {
        val curve = snapshot(
            CurveKind.EUA_FORWARD,
            dated("2026-10-13", "70.00"),
            dated("2026-12-21", "72.00"),
            dated("2027-12-20", "76.00"),
            interpolation = Interpolation.CARRY,
        )
        // 164 days past Dec-27 at the Dec-26 to Dec-27 carry of 4/72 over 364 days: 76 x (1 + 4/72 x 164/364).
        CurveMath.priceOn(curve, LocalDate.of(2028, 6, 1))!!.let {
            it.value shouldBe BigDecimal("77.902320")
            it.origin shouldBe PriceOrigin.EXTRAPOLATED
        }
        CurveMath.priceOn(curve, LocalDate.of(2026, 11, 16))!!.value shouldBe BigDecimal("70.985507")
        CurveMath.priceOn(curve, LocalDate.of(2027, 12, 20))!!.value shouldBe BigDecimal("76.00")
    }

    @Test
    fun `vintages use their own point or the nearest earlier one`() {
        val curve = snapshot(CurveKind.GOO_VINTAGE, year(2025, "0.40"), year(2026, "0.85"))
        CurveMath.priceFor(curve, 2026)!!.value shouldBe BigDecimal("0.85")
        CurveMath.priceFor(curve, 2027)!!.let {
            it.value shouldBe BigDecimal("0.85")
            it.origin shouldBe PriceOrigin.EARLIER_VINTAGE
        }
        CurveMath.priceFor(curve, 2024) shouldBe null
    }

    private fun quote(instrument: String, date: LocalDate, type: QuoteType, price: String) = QuoteView(
        UUID.randomUUID(),
        instrument,
        date,
        type,
        BigDecimal(price),
        "EUR",
        "X",
        null,
        QuoteQuality.OK,
        null,
        "u",
        Instant.now(),
        null,
        null,
    )

    @Test
    fun `builder prefers a quote of the day, then the latest within the staleness window, else a gap`() {
        val quotes = mapOf(
            ("DEC26" to QuoteType.SETTLEMENT) to quote("DEC26", asOf.minusDays(1), QuoteType.SETTLEMENT, "71.00"),
            ("DEC26" to QuoteType.CLOSE) to quote("DEC26", asOf, QuoteType.CLOSE, "71.50"),
            ("DEC27" to QuoteType.SETTLEMENT) to quote("DEC27", asOf.minusDays(3), QuoteType.SETTLEMENT, "75.00"),
            ("DEC28" to QuoteType.SETTLEMENT) to quote("DEC28", asOf.minusDays(10), QuoteType.SETTLEMENT, "80.00"),
        )
        val result = CurveBuilder.build(
            asOf,
            listOf(QuoteType.SETTLEMENT, QuoteType.CLOSE),
            5,
            listOf(
                PillarInput("DEC26", LocalDate.of(2026, 12, 21), null, "DEC26"),
                PillarInput("DEC27", LocalDate.of(2027, 12, 20), null, "DEC27"),
                PillarInput("DEC28", LocalDate.of(2028, 12, 18), null, "DEC28"),
            ),
            { i, t -> quotes[i to t] },
        )
        result.points.map { it.pillar to it.origin } shouldBe listOf("DEC26" to PointOrigin.QUOTE, "DEC27" to PointOrigin.STALE)
        result.points.first().price shouldBe BigDecimal("71.50")
        result.gaps shouldBe listOf("DEC28")
    }

    @Test
    fun `a proxy curve fills missing pillars with its prices plus the spread`() {
        val result = CurveBuilder.build(
            asOf,
            listOf(QuoteType.MID),
            5,
            listOf(PillarInput("2026", null, 2026, "FI_HYDRO_2026")),
            { _, _ -> null },
            proxy = listOf(year(2025, "0.40"), year(2026, "0.85")),
            proxySpread = BigDecimal("0.05"),
        )
        result.points.map { it.productionYear to it.price } shouldBe listOf(2025 to BigDecimal("0.45"), 2026 to BigDecimal("0.90"))
        result.points.all { it.origin == PointOrigin.PROXY } shouldBe true
        result.gaps shouldBe emptyList()
    }

    @Test
    fun `discount factors compound simply within a year and annually beyond, in decimals`() {
        CurveMath.factor(BigDecimal("3"), 90, DayCount.ACT_360) shouldBe BigDecimal("0.9925558313")
        // 500 days: one whole year at 3%, then a simple stub of 140/360.
        CurveMath.factor(BigDecimal("3"), 500, DayCount.ACT_360) shouldBe BigDecimal("0.9596775483")
        // A negative rate makes money paid later worth more.
        CurveMath.factor(BigDecimal("-0.5"), 180, DayCount.ACT_360) shouldBe BigDecimal("1.0025062657")
        CurveMath.factor(BigDecimal("3"), 0, DayCount.ACT_360) shouldBe BigDecimal.ONE
    }

    @Test
    fun `a discount curve interpolates its rates by tenor date and does not discount what is due`() {
        val curve = snapshot(
            CurveKind.DISCOUNT,
            dated(CurveMath.tenorDate(asOf, "3M").toString(), "2.00"),
            dated(CurveMath.tenorDate(asOf, "1Y").toString(), "2.50"),
        )
        // 2027-04-09 is 90 of the 273 days from the 3M to the 1Y date: 2 + 0.5 x 90/273; 182 days from the curve date.
        CurveMath.discountFactor(curve, asOf, LocalDate.of(2027, 4, 9)).let {
            it.rate shouldBe BigDecimal("2.164835")
            it.days shouldBe 182
            it.value shouldBe BigDecimal("0.9891740405")
            it.origin shouldBe PriceOrigin.INTERPOLATED
            it.snapshotId shouldBe curve.id
        }
        CurveMath.discountFactor(curve, asOf, LocalDate.of(2030, 1, 1)).origin shouldBe PriceOrigin.EXTRAPOLATED
        CurveMath.discountFactor(curve, asOf, asOf) shouldBe DiscountFactor.NONE
        CurveMath.discountFactor(curve, asOf, asOf.minusDays(3)) shouldBe DiscountFactor.NONE
        CurveMath.tenorDate(asOf, "ON") shouldBe asOf.plusDays(1)
        CurveMath.tenorDate(asOf, "2W") shouldBe asOf.plusDays(14)
    }
}
