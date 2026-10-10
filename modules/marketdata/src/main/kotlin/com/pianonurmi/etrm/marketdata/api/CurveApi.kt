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
}

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

data class CurvePrice(val value: BigDecimal, val currency: String, val origin: PriceOrigin, val curveCode: String, val snapshotId: UUID)

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

    /** Price of an allowance for delivery on [deliveryDate] from the official forward curve of [asOfDate]. */
    fun forwardPrice(allowanceProductCode: String, deliveryDate: LocalDate, asOfDate: LocalDate, knownAt: Instant? = null): CurvePrice?

    /** Price of a GoO bucket's vintage from the latest official vintage curve on or before [asOf]. */
    fun vintagePrice(bucketCode: String, productionYear: Int, asOf: LocalDate, knownAt: Instant? = null): CurvePrice?
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

    /** Sends a draft for sign-off by a second person (workflow task `CURVE_SIGN_OFF`). */
    fun submit(snapshotId: UUID): CurveSnapshotView
}

data class CurveSnapshotSubmitted(val snapshot: CurveSnapshotView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class CurveSnapshotSignedOff(val snapshot: CurveSnapshotView, val superseded: UUID?, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

data class CurveSnapshotRejected(val snapshot: CurveSnapshotView, val reason: String?, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
