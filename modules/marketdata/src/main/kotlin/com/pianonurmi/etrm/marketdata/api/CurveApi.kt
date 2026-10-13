package com.pianonurmi.etrm.marketdata.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class CurveKind {
    /** Allowance prices by delivery date, from futures settlements and spot. */
    EUA_FORWARD,

    /** GoO prices of one product bucket by production year. */
    GOO_VINTAGE,

    /** Zero rates (percent a year) of one currency by tenor, for discounting (ADR 0044). */
    DISCOUNT,

    /** Implied volatility (percent a year) of options on an allowance product's futures by expiry (ADR 0047). */
    EUA_VOLATILITY,
}

/** How a discount curve turns days into years: actual days over 360 (EUR money markets) or over 365. */
enum class DayCount(val basis: Int) {
    ACT_360(360),
    ACT_365F(365),
}

/**
 * How a forward curve is read off its pillars: linear in days between them either way (a constant carry rate under
 * simple compounding); beyond the last pillar flat ([LINEAR]) or growing at the last segment's carry rate ([CARRY]).
 */
enum class Interpolation { LINEAR, CARRY }

enum class SnapshotStatus { DRAFT, PENDING_SIGN_OFF, OFFICIAL, SUPERSEDED, REJECTED }

/** Where a stored point's price came from. */
enum class PointOrigin {
    /** A price of the snapshot date. */
    QUOTE,

    /** The latest price within the curve's staleness window, older than the snapshot date. */
    STALE,

    /** The proxy curve's price plus the spread. */
    PROXY,

    /** Set by hand in a new snapshot version. */
    OVERRIDE,
}

/** How a price read from a curve was obtained. */
enum class PriceOrigin { POINT, INTERPOLATED, EXTRAPOLATED, EARLIER_VINTAGE }

data class CurveView(
    val code: String,
    val kind: CurveKind,
    val description: String,
    val allowanceProductCode: String?,
    val bucketCode: String?,
    val currency: String,
    val quoteTypes: List<QuoteType>,
    val stalenessDays: Int,
    val proxyCurveCode: String?,
    val proxySpread: BigDecimal,
    val active: Boolean,
    /** Discount curves only. */
    val dayCount: DayCount? = null,
    val interpolation: Interpolation = Interpolation.LINEAR,
)

data class CreateCurve(
    val code: String,
    val kind: CurveKind,
    val description: String,
    val allowanceProductCode: String? = null,
    val bucketCode: String? = null,
    val currency: String = "EUR",
    val quoteTypes: List<QuoteType> = listOf(QuoteType.SETTLEMENT, QuoteType.CLOSE, QuoteType.MID),
    val stalenessDays: Int = 5,
    val proxyCurveCode: String? = null,
    val proxySpread: BigDecimal = BigDecimal.ZERO,
    /** Discount curves only; ACT_360 for EUR and ACT_365F for other currencies when left out. */
    val dayCount: DayCount? = null,
    /** Forward curves only. */
    val interpolation: Interpolation = Interpolation.LINEAR,
)

data class CurvePointView(
    val pillar: String,
    val pillarDate: LocalDate?,
    val productionYear: Int?,
    val price: BigDecimal,
    val origin: PointOrigin,
    val quoteIds: List<UUID>,
    val note: String?,
)

/** A price read from an official snapshot; [curveDate] is the snapshot's date, earlier than asked when that was the latest. */
data class CurvePrice(
    val value: BigDecimal,
    val currency: String,
    val origin: PriceOrigin,
    val curveCode: String,
    val snapshotId: UUID,
    val curveDate: LocalDate,
)

data class CurveSnapshotView(
    val id: UUID,
    val curveCode: String,
    val kind: CurveKind,
    val currency: String,
    val asOfDate: LocalDate,
    val version: Int,
    val status: SnapshotStatus,
    val inputsHash: String,
    val gaps: List<String>,
    val note: String?,
    val builtBy: String,
    val builtAt: Instant,
    val signedOffBy: String?,
    val signedOffAt: Instant?,
    val supersededAt: Instant?,
    val points: List<CurvePointView>,
    /** Discount curves only. */
    val dayCount: DayCount? = null,
    /** The method the snapshot was built with. */
    val interpolation: Interpolation = Interpolation.LINEAR,
)

interface CurveQuery {
    fun curves(activeOnly: Boolean = true): List<CurveView>

    fun curve(code: String): CurveView?

    fun snapshot(id: UUID): CurveSnapshotView?

    fun snapshots(curveCode: String, asOfDate: LocalDate): List<CurveSnapshotView>

    /** The official snapshot of [asOfDate]; with [knownAt], the one that was official at that instant. */
    fun official(curveCode: String, asOfDate: LocalDate, knownAt: Instant? = null): CurveSnapshotView?

    /** The official snapshot of the latest date on or before [onOrBefore]. */
    fun latestOfficial(curveCode: String, onOrBefore: LocalDate, knownAt: Instant? = null): CurveSnapshotView?

    /** Price of an allowance for delivery on [deliveryDate] from the latest official forward curve on or before [asOfDate]. */
    fun forwardPrice(allowanceProductCode: String, deliveryDate: LocalDate, asOfDate: LocalDate, knownAt: Instant? = null): CurvePrice?

    /** Price of a GoO bucket's vintage from the latest official vintage curve on or before [asOf]. */
    fun vintagePrice(bucketCode: String, productionYear: Int, asOf: LocalDate, knownAt: Instant? = null): CurvePrice?

    /**
     * Implied volatility in percent a year for an option on the allowance product expiring on [expiryDate], seen from
     * [asOfDate], from the latest official volatility curve on or before it; null without one.
     */
    fun volatility(allowanceProductCode: String, expiryDate: LocalDate, asOfDate: LocalDate, knownAt: Instant? = null): CurvePrice?
}

/**
 * A discount factor for a payment: [value] is what one unit paid on the payment date is worth on the date it was
 * seen from. [NONE] (exactly 1) when there is no official discount curve for the currency, or the payment is due.
 */
data class DiscountFactor(
    val value: BigDecimal,
    /** The interpolated zero rate in percent a year. */
    val rate: BigDecimal?,
    val days: Long,
    val origin: PriceOrigin?,
    val curveCode: String?,
    val snapshotId: UUID?,
    val curveDate: LocalDate?,
) {
    val discounted: Boolean get() = snapshotId != null

    companion object {
        val NONE = DiscountFactor(BigDecimal.ONE, null, 0, null, null, null, null)
    }
}

/** Discount factors from official discount curves (ADR 0044); nothing else stores interest rates. */
interface DiscountFactors {
    /** From the latest official discount curve of [currency] on or before [asOf], for a payment on [payDate] seen from [asOf]. */
    fun discountFactor(currency: String, payDate: LocalDate, asOf: LocalDate, knownAt: Instant? = null): DiscountFactor

    /** From one snapshot's rates, for a payment on [payDate] seen from [from] (the P&L explain rolls a curve forward this way). */
    fun discountFactor(snapshotId: UUID, from: LocalDate, payDate: LocalDate): DiscountFactor
}

/** Marks for allocation scoring: EUR/MWh of a bucket's vintage, or null when there is no official curve price. */
fun interface GooMarks {
    fun mark(bucketCode: String, productionYear: Int, asOf: LocalDate): BigDecimal?
}

interface CurveCommand {
    fun createCurve(command: CreateCurve): CurveView

    /** Builds a draft snapshot from the prices known now. */
    fun build(curveCode: String, asOfDate: LocalDate, note: String? = null): CurveSnapshotView

    /** A new draft version of [snapshotId] with one pillar set by hand. */
    fun override(snapshotId: UUID, pillar: String, price: BigDecimal, note: String): CurveSnapshotView

    /** How later snapshots of a forward curve are read beyond the last pillar; snapshots already built keep theirs. */
    fun setInterpolation(curveCode: String, interpolation: Interpolation): CurveView

    /** Sends a draft for sign-off by a second person (workflow task `CURVE_SIGN_OFF`). */
    fun submit(snapshotId: UUID): CurveSnapshotView
}

data class CurveSnapshotSubmitted(val snapshot: CurveSnapshotView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class CurveSnapshotSignedOff(val snapshot: CurveSnapshotView, val superseded: UUID?, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

data class CurveSnapshotRejected(val snapshot: CurveSnapshotView, val reason: String?, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
