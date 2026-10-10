package com.pianonurmi.etrm.marketdata.domain

import com.pianonurmi.etrm.marketdata.api.CurveKind
import com.pianonurmi.etrm.marketdata.api.CurvePointView
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotView
import com.pianonurmi.etrm.marketdata.api.PointOrigin
import com.pianonurmi.etrm.marketdata.api.PriceOrigin
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Reading prices off a snapshot: linear in calendar days between dated pillars, flat beyond; vintages by year. */
object CurveMath {
    fun priceOn(snapshot: CurveSnapshotView, date: LocalDate): CurvePrice? {
        require(snapshot.kind == CurveKind.EUA_FORWARD) { "${snapshot.curveCode} is not a forward curve" }
        val points = snapshot.points.filter { it.pillarDate != null }.sortedBy { it.pillarDate }
        if (points.isEmpty()) return null

        fun price(value: BigDecimal, origin: PriceOrigin) = CurvePrice(value, snapshot.currency, origin, snapshot.curveCode, snapshot.id, snapshot.asOfDate)
        val exact = points.firstOrNull { it.pillarDate == date }
        val first = points.first()
        val last = points.last()
        return when {
            exact != null -> price(exact.price, PriceOrigin.POINT)
            date.isBefore(first.pillarDate) -> price(first.price, PriceOrigin.EXTRAPOLATED)
            date.isAfter(last.pillarDate) -> price(last.price, PriceOrigin.EXTRAPOLATED)
            else -> {
                val before = points.last { it.pillarDate!!.isBefore(date) }
                val after = points.first { it.pillarDate!!.isAfter(date) }
                val span = ChronoUnit.DAYS.between(before.pillarDate, after.pillarDate)
                val elapsed = ChronoUnit.DAYS.between(before.pillarDate, date)
                val value = before.price + (after.price - before.price) * BigDecimal(elapsed).divide(BigDecimal(span), SCALE, RoundingMode.HALF_EVEN)
                price(value.setScale(6, RoundingMode.HALF_EVEN), PriceOrigin.INTERPOLATED)
            }
        }
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

    private const val SCALE = 10
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
            if (price.signum() > 0) {
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
