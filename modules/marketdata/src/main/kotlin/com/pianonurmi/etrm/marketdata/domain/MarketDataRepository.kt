package com.pianonurmi.etrm.marketdata.domain

import com.pianonurmi.etrm.marketdata.api.ImportFormatView
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.InstrumentView
import com.pianonurmi.etrm.marketdata.api.PriceIndexView
import com.pianonurmi.etrm.marketdata.api.QuoteQuality
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import com.pianonurmi.etrm.marketdata.api.RecordFxRate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class StoredFxRate(val id: UUID, val rate: RecordFxRate, val importId: UUID?, val recordedBy: String)

data class ImportRecord(
    val id: UUID,
    val formatCode: String,
    val fileName: String,
    val contentHash: String,
    val rowsRead: Int,
    val accepted: Int,
    val rejected: Int,
    val importedBy: String,
)

interface InstrumentRepository {
    fun all(kind: InstrumentKind?, activeOnly: Boolean): List<InstrumentView>

    fun byCode(code: String): InstrumentView?

    fun insert(instrument: InstrumentView)

    fun alias(source: String, externalCode: String): String?

    fun addAlias(source: String, externalCode: String, instrumentCode: String)
}

interface QuoteRepository {
    /** The current quote for exactly this key, locked for a correction. */
    fun current(instrumentCode: String, date: LocalDate, type: QuoteType, source: String): QuoteView?

    /** The latest current quote of [type] strictly before [date], any source. */
    fun previous(instrumentCode: String, type: QuoteType, date: LocalDate): QuoteView?

    fun insert(quote: QuoteView)

    fun supersede(id: UUID, at: Instant)

    fun find(id: UUID): QuoteView?

    fun quotes(instrumentCode: String, from: LocalDate, toInclusive: LocalDate, knownAt: Instant?): List<QuoteView>

    fun latest(instrumentCode: String, type: QuoteType, date: LocalDate, knownAt: Instant?): QuoteView?

    fun withQuality(quality: QuoteQuality): List<QuoteView>

    fun acknowledge(id: UUID, by: String, note: String)
}

interface FxRateRepository {
    /** Latest current rate on or before [date] for exactly this pair, from any source (the most recently recorded wins a tie). */
    fun latest(date: LocalDate, base: String, quote: String): DatedRate?

    fun current(date: LocalDate, base: String, quote: String, source: String): StoredFxRate?

    fun insert(rate: StoredFxRate)

    fun supersede(id: UUID, at: Instant)

    fun on(date: LocalDate, base: String?): List<RecordFxRate>

    fun latestDate(): LocalDate?
}

interface ImportRepository {
    fun formats(): List<ImportFormatView>

    fun format(code: String): ImportFormatView?

    fun upsertFormat(format: ImportFormatView)

    fun findByHash(formatCode: String, contentHash: String): ImportRecord?

    fun insert(record: ImportRecord, errors: List<ParseIssue>)

    /** Records the outcome of an import started with [insert]. */
    fun complete(importId: UUID, accepted: Int, errors: List<ParseIssue>)

    fun errors(importId: UUID): List<ParseIssue>
}

interface PriceIndexRepository {
    fun all(): List<PriceIndexView>

    fun byCode(code: String): PriceIndexView?

    fun insert(index: PriceIndexView)
}

/** Relative move beyond which a new price is held as suspect until a second person acknowledges it. */
object Plausibility {
    fun suspect(previous: BigDecimal?, next: BigDecimal, maxMovePct: BigDecimal): Boolean {
        if (previous == null || previous.signum() <= 0) return false
        val move = (next - previous).abs().multiply(BigDecimal(100)).divide(previous, 6, java.math.RoundingMode.HALF_UP)
        return move > maxMovePct
    }

    /** Rates (percent) move in absolute terms: near zero a relative move means nothing. */
    fun suspectRate(previous: BigDecimal?, next: BigDecimal, maxMoveBp: BigDecimal): Boolean =
        previous != null && (next - previous).abs().multiply(BigDecimal(100)) > maxMoveBp
}

interface CurveRepository {
    fun curves(activeOnly: Boolean): List<com.pianonurmi.etrm.marketdata.api.CurveView>

    fun curve(code: String): com.pianonurmi.etrm.marketdata.api.CurveView?

    fun insertCurve(curve: com.pianonurmi.etrm.marketdata.api.CurveView)

    fun nextVersion(curveCode: String, asOfDate: LocalDate): Int

    fun insertSnapshot(snapshot: com.pianonurmi.etrm.marketdata.api.CurveSnapshotView)

    fun snapshot(id: UUID, lock: Boolean = false): com.pianonurmi.etrm.marketdata.api.CurveSnapshotView?

    fun snapshots(curveCode: String, asOfDate: LocalDate): List<com.pianonurmi.etrm.marketdata.api.CurveSnapshotView>

    fun setStatus(id: UUID, status: com.pianonurmi.etrm.marketdata.api.SnapshotStatus, signedOffBy: String? = null, at: Instant? = null)

    fun supersede(id: UUID, at: Instant)

    /** Official at [knownAt] (or now): signed off by then and not superseded by then. */
    fun official(curveCode: String, asOfDate: LocalDate?, onOrBefore: LocalDate?, knownAt: Instant?): com.pianonurmi.etrm.marketdata.api.CurveSnapshotView?
}
