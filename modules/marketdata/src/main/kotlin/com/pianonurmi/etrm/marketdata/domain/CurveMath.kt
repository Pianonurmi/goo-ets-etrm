package com.pianonurmi.etrm.marketdata.domain

import com.pianonurmi.etrm.marketdata.api.CurveKind
import com.pianonurmi.etrm.marketdata.api.CurvePointView
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotView
import com.pianonurmi.etrm.marketdata.api.DayCount
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.Interpolation
import com.pianonurmi.etrm.marketdata.api.PointOrigin
import com.pianonurmi.etrm.marketdata.api.PriceOrigin
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Reading prices off a snapshot: linear in calendar days between dated pillars; beyond the last, flat or (CARRY) at
 * the last segment's carry rate; before the first, flat. Vintages by year.
 */
object CurveMath {
    fun priceOn(snapshot: CurveSnapshotView, date: LocalDate): CurvePrice? {
        require(snapshot.kind == CurveKind.EUA_FORWARD) { "${snapshot.curveCode} is not a forward curve" }
        val points = snapshot.points.filter { it.pillarDate != null }.sortedBy { it.pillarDate }
        if (points.isEmpty()) return null
        val (value, origin) = if (snapshot.interpolation == Interpolation.CARRY && points.size > 1 && date.isAfter(points.last().pillarDate)) {
            carried(points[points.size - 2], points.last(), date) to PriceOrigin.EXTRAPOLATED
        } else {
            interpolate(points, date)
        }
        return CurvePrice(value, snapshot.currency, origin, snapshot.curveCode, snapshot.id, snapshot.asOfDate)
    }

    /** `F_n (1 + (F_n / F_n-1 − 1) × (T − T_n) / (T_n − T_n-1))`: the last segment's simple carry continued. */
    private fun carried(before: CurvePointView, last: CurvePointView, date: LocalDate): BigDecimal {
        val segment = ChronoUnit.DAYS.between(before.pillarDate, last.pillarDate)
        val beyond = ChronoUnit.DAYS.between(last.pillarDate, date)
        val growth = last.price.divide(before.price, MC) - BigDecimal.ONE
        val factor = BigDecimal.ONE + growth.multiply(BigDecimal(beyond), MC).divide(BigDecimal(segment), MC)
        return last.price.multiply(factor, MC).setScale(6, RoundingMode.HALF_EVEN)
    }

    /** The vintage's own point, else the nearest earlier vintage (GoO of an older year are no dearer, a cautious mark). */
    fun priceFor(snapshot: CurveSnapshotView, productionYear: Int): CurvePrice? {
        require(snapshot.kind == CurveKind.GOO_VINTAGE) { "${snapshot.curveCode} is not a vintage curve" }
        val points = snapshot.points.filter { it.productionYear != null }
        points.firstOrNull { it.productionYear == productionYear }
            ?.let { return CurvePrice(it.price, snapshot.currency, PriceOrigin.POINT, snapshot.curveCode, snapshot.id, snapshot.asOfDate) }
        return points.filter { it.productionYear!! < productionYear }.maxByOrNull { it.productionYear!! }
            ?.let { CurvePrice(it.price, snapshot.currency, PriceOrigin.EARLIER_VINTAGE, snapshot.curveCode, snapshot.id, snapshot.asOfDate) }
    }

    /**
     * The zero rate for [date] off a discount snapshot, linear in calendar days between tenor dates and flat beyond,
     * then the factor for a payment on [payDate] seen from [from] (ADR 0044): simple compounding up to a year,
     * `1 / (1 + r t)`; beyond, annual compounding for the whole years with a simple stub, `1 / ((1 + r)^n (1 + r f))`.
     * No floating point: everything in decimal arithmetic, the factor rounded to ten places. A payment on or before
     * [from] is not discounted.
     */
    fun discountFactor(snapshot: CurveSnapshotView, from: LocalDate, payDate: LocalDate): DiscountFactor {
        require(snapshot.kind == CurveKind.DISCOUNT) { "${snapshot.curveCode} is not a discount curve" }
        val dayCount = requireNotNull(snapshot.dayCount) { "${snapshot.curveCode} has no day count" }
        val days = ChronoUnit.DAYS.between(from, payDate)
        if (days <= 0) return DiscountFactor.NONE
        val points = snapshot.points.filter { it.pillarDate != null }.sortedBy { it.pillarDate }
        if (points.isEmpty()) return DiscountFactor.NONE
        val (rate, origin) = interpolate(points, payDate)
        return DiscountFactor(factor(rate, days, dayCount), rate, days, origin, snapshot.curveCode, snapshot.id, snapshot.asOfDate)
    }

    /** `1 / (1 + r t)` within a year, `1 / ((1 + r)^n (1 + r f))` beyond, with [ratePct] in percent a year. */
    fun factor(ratePct: BigDecimal, days: Long, dayCount: DayCount): BigDecimal {
        if (days <= 0) return BigDecimal.ONE
        val r = ratePct.divide(HUNDRED, MC)
        val basis = dayCount.basis.toLong()
        val years = days / basis
        val stub = BigDecimal(days - years * basis).divide(BigDecimal(basis), MC)
        val growth = if (years == 0L) {
            BigDecimal.ONE + r.multiply(BigDecimal(days).divide(BigDecimal(basis), MC), MC)
        } else {
            (BigDecimal.ONE + r).pow(years.toInt(), MC).multiply(BigDecimal.ONE + r.multiply(stub, MC), MC)
        }
        require(growth.signum() > 0) { "A rate of $ratePct% cannot discount over $days days" }
        return BigDecimal.ONE.divide(growth, MC).setScale(FACTOR_SCALE, RoundingMode.HALF_EVEN)
    }

    private fun interpolate(points: List<CurvePointView>, date: LocalDate): Pair<BigDecimal, PriceOrigin> {
        val exact = points.firstOrNull { it.pillarDate == date }
        val first = points.first()
        val last = points.last()
        return when {
            exact != null -> exact.price to PriceOrigin.POINT
            date.isBefore(first.pillarDate) -> first.price to PriceOrigin.EXTRAPOLATED
            date.isAfter(last.pillarDate) -> last.price to PriceOrigin.EXTRAPOLATED
            else -> {
                val before = points.last { it.pillarDate!!.isBefore(date) }
                val after = points.first { it.pillarDate!!.isAfter(date) }
                val span = ChronoUnit.DAYS.between(before.pillarDate, after.pillarDate)
                val elapsed = ChronoUnit.DAYS.between(before.pillarDate, date)
                val value = before.price + (after.price - before.price) * BigDecimal(elapsed).divide(BigDecimal(span), SCALE, RoundingMode.HALF_EVEN)
                value.setScale(6, RoundingMode.HALF_EVEN) to PriceOrigin.INTERPOLATED
            }
        }
    }

    /** The date a tenor (`ON`, `7D`, `2W`, `3M`, `10Y`) ends, counted from [from]. */
    fun tenorDate(from: LocalDate, tenor: String): LocalDate {
        if (tenor == "ON") return from.plusDays(1)
        val match = requireNotNull(TENOR.matchEntire(tenor)) { "Unreadable tenor $tenor" }
        val n = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "D" -> from.plusDays(n)
            "W" -> from.plusWeeks(n)
            "M" -> from.plusMonths(n)
            else -> from.plusYears(n)
        }
    }

    val TENOR = Regex("^([1-9][0-9]*)([DWMY])$")
    private const val SCALE = 10
    private const val FACTOR_SCALE = 10
    private val HUNDRED = BigDecimal(100)
    private val MC = MathContext.DECIMAL128
}

/** A pillar to price: the instrument quoted for it and where it sits on the curve. */
data class PillarInput(val pillar: String, val pillarDate: LocalDate?, val productionYear: Int?, val instrumentCode: String)

/**
 * Picks each pillar's price: a quote of the snapshot date in the curve's preferred order of quote types, else the
 * most recent within the staleness window (marked STALE). Pillars without either are gaps. A proxy curve's points
 * fill pillars the curve itself does not have, shifted by the spread.
 */
object CurveBuilder {
    data class Result(val points: List<CurvePointView>, val gaps: List<String>)

    fun build(
        asOf: LocalDate,
        quoteTypes: List<QuoteType>,
        stalenessDays: Int,
        pillars: List<PillarInput>,
        latest: (instrumentCode: String, type: QuoteType) -> QuoteView?,
        proxy: List<CurvePointView> = emptyList(),
        proxySpread: BigDecimal = BigDecimal.ZERO,
        // Rates may be zero or negative; prices may not.
        positiveOnly: Boolean = true,
    ): Result {
        val points = mutableListOf<CurvePointView>()
        val gaps = mutableListOf<String>()
        pillars.forEach { p ->
            val candidates = quoteTypes.mapNotNull { latest(p.instrumentCode, it) }
                .filter { !it.quoteDate.isBefore(asOf.minusDays(stalenessDays.toLong())) }
            val chosen = candidates.firstOrNull { it.quoteDate == asOf } ?: candidates.maxByOrNull { it.quoteDate }
            if (chosen == null) {
                gaps += p.pillar
            } else {
                points += CurvePointView(
                    p.pillar,
                    p.pillarDate,
                    p.productionYear,
                    chosen.value,
                    if (chosen.quoteDate == asOf) PointOrigin.QUOTE else PointOrigin.STALE,
                    listOf(chosen.id),
                    if (chosen.quoteDate == asOf) null else "${chosen.quoteType} of ${chosen.quoteDate}",
                )
            }
        }
        val covered = points.map { it.pillarDate to it.productionYear }.toSet()
        proxy.filter { (it.pillarDate to it.productionYear) !in covered }.forEach { pp ->
            val price = pp.price + proxySpread
            if (!positiveOnly || price.signum() > 0) {
                points += CurvePointView(
                    "proxy:${pp.pillar}",
                    pp.pillarDate,
                    pp.productionYear,
                    price,
                    PointOrigin.PROXY,
                    pp.quoteIds,
                    "proxy ${pp.pillar} + $proxySpread",
                )
                gaps.remove(pp.pillar)
            }
        }
        return Result(points.sortedWith(compareBy({ it.pillarDate }, { it.productionYear })), gaps)
    }
}
