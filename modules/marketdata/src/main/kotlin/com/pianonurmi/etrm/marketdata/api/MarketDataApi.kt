package com.pianonurmi.etrm.marketdata.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.platform.money.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class InstrumentKind { EUA_SPOT, EUA_FUTURE, EUA_FORWARD, GOO_VINTAGE, INDEX, RATE }

enum class QuoteType { SETTLEMENT, MID, BID, ASK, CLOSE, FIXING }

enum class QuoteQuality {
    OK,

    /** Moved more than the plausibility limit from the previous value; usable once someone else acknowledges it. */
    SUSPECT,
    ACKNOWLEDGED,
    ;

    val usable: Boolean get() = this != SUSPECT
}

data class InstrumentView(
    val code: String,
    val kind: InstrumentKind,
    val description: String,
    val allowanceProductCode: String?,
    val futuresContractCode: String?,
    val bucketCode: String?,
    val productionYear: Int?,
    val currency: String,
    val unit: String,
    val active: Boolean,
)

data class CreateInstrument(
    val code: String,
    val kind: InstrumentKind,
    val description: String,
    val allowanceProductCode: String? = null,
    val futuresContractCode: String? = null,
    val bucketCode: String? = null,
    val productionYear: Int? = null,
    val currency: String = "EUR",
    val unit: String? = null,
)

data class QuoteView(
    val id: UUID,
    val instrumentCode: String,
    val quoteDate: LocalDate,
    val quoteType: QuoteType,
    val value: BigDecimal,
    val currency: String,
    val source: String,
    val importId: UUID?,
    val quality: QuoteQuality,
    val qualityNote: String?,
    val recordedBy: String,
    val recordedAt: Instant,
    val supersededAt: Instant?,
    val acknowledgedBy: String?,
)

data class RecordQuote(
    val instrumentCode: String,
    val quoteDate: LocalDate,
    val quoteType: QuoteType,
    val value: BigDecimal,
    val currency: String? = null,
    val source: String = "MANUAL",
)

/** An exchange rate as used: 1 [from] = [rate] [to], and how it was found (direct, inverse or crossed through EUR). */
data class FxRateView(val date: LocalDate, val from: String, val to: String, val rate: BigDecimal, val path: String, val rateDate: LocalDate)

data class RecordFxRate(val date: LocalDate, val base: String, val quote: String, val rate: BigDecimal, val source: String = "MANUAL")

enum class FixingRule {
    /** The quote on the fixing date. */
    ON_DATE,

    /** The latest quote before the fixing date. */
    PRIOR_DAY,

    /** The average of the quotes in the fixing date's calendar month. */
    MONTH_AVERAGE,
}

data class PriceIndexView(
    val code: String,
    val description: String,
    val instrumentCode: String,
    val quoteType: QuoteType,
    val fixingRule: FixingRule,
    val active: Boolean,
)

data class CreatePriceIndex(val code: String, val description: String, val instrumentCode: String, val quoteType: QuoteType, val fixingRule: FixingRule)

data class FixingView(val indexCode: String, val fixingDate: LocalDate, val value: BigDecimal, val currency: String, val quoteIds: List<UUID>)

enum class ImportFormatKind { FX_WIDE_CSV, FX_ECB_XML, QUOTES_LONG_CSV }

data class ImportFormatView(
    val code: String,
    val kind: ImportFormatKind,
    val description: String,
    val source: String,
    val delimiter: Char,
    val skipLines: Int,
    val dateFormat: String,
    val decimalSeparator: Char,
    /** Logical field (`date`, `instrument`, `value`, `currency`, `quoteType`) to the column header holding it. */
    val columnMapping: Map<String, String>,
    /** Values for fields the file does not carry (e.g. `quoteType`, `currency`, `base`). */
    val constants: Map<String, String>,
    val active: Boolean,
    val version: Int,
)

data class UpsertImportFormat(
    val code: String,
    val kind: ImportFormatKind,
    val description: String,
    val source: String,
    val delimiter: Char = ',',
    val skipLines: Int = 0,
    val dateFormat: String = "yyyy-MM-dd",
    val decimalSeparator: Char = '.',
    val columnMapping: Map<String, String> = emptyMap(),
    val constants: Map<String, String> = emptyMap(),
    val active: Boolean = true,
)

data class ImportResult(
    val id: UUID,
    val formatCode: String,
    val fileName: String,
    val rowsRead: Int,
    val accepted: Int,
    val rejected: Int,
    val suspect: Int,
    val errors: List<ImportError>,
    /** True when the same file was imported before; nothing was changed. */
    val duplicate: Boolean,
)

data class ImportError(val lineNo: Int, val message: String)

/** FX conversion for every module: rates come from the published (ECB, imported or manual) rate table. */
interface FxRates {
    /** 1 [from] in [to] on [date], or null when no rate is fresh enough. */
    fun rate(date: LocalDate, from: String, to: String): FxRateView?

    fun require(date: LocalDate, from: String, to: String): FxRateView

    fun convert(amount: Money, to: String, date: LocalDate): Money
}

interface MarketDataQuery {
    fun instruments(kind: InstrumentKind? = null, activeOnly: Boolean = true): List<InstrumentView>

    fun instrument(code: String): InstrumentView?

    /** Current (not superseded) quotes; [knownAt] gives the quotes as they were known at that instant. */
    fun quotes(instrumentCode: String, from: LocalDate, toInclusive: LocalDate, knownAt: Instant? = null): List<QuoteView>

    /** Latest usable quote of [type] on or before [date] (any source), as known at [knownAt]. */
    fun latestQuote(instrumentCode: String, type: QuoteType, date: LocalDate, knownAt: Instant? = null): QuoteView?

    fun suspectQuotes(): List<QuoteView>

    fun fxRates(date: LocalDate, base: String? = null): List<RecordFxRate>

    fun importFormats(): List<ImportFormatView>

    fun indices(): List<PriceIndexView>

    fun index(code: String): PriceIndexView?
}

interface IndexFixings {
    /** The index fixing for [fixingDate] under the index's fixing rule, or null when the quotes are not there yet. */
    fun fixing(indexCode: String, fixingDate: LocalDate): FixingView?
}

interface MarketDataCommand {
    fun createInstrument(command: CreateInstrument): InstrumentView

    fun addAlias(source: String, externalCode: String, instrumentCode: String)

    fun recordQuote(command: RecordQuote): QuoteView

    /** A second person accepts a suspect quote as correct. */
    fun acknowledgeQuote(quoteId: UUID, note: String): QuoteView

    fun recordFxRate(command: RecordFxRate)

    fun upsertImportFormat(command: UpsertImportFormat): ImportFormatView

    fun importFile(formatCode: String, fileName: String, content: ByteArray): ImportResult

    fun createIndex(command: CreatePriceIndex): PriceIndexView
}

data class QuotesImported(
    val importId: UUID,
    val formatCode: String,
    val accepted: Int,
    val dates: Set<LocalDate>,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata

data class FxRatesPublished(val source: String, val dates: Set<LocalDate>, val count: Int, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata

data class QuoteFlagged(val quote: QuoteView, val previous: BigDecimal, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
