package com.pianonurmi.etrm.marketdata.application

import com.pianonurmi.etrm.marketdata.api.CreateInstrument
import com.pianonurmi.etrm.marketdata.api.CreatePriceIndex
import com.pianonurmi.etrm.marketdata.api.FixingRule
import com.pianonurmi.etrm.marketdata.api.FixingView
import com.pianonurmi.etrm.marketdata.api.FxRateView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.FxRatesPublished
import com.pianonurmi.etrm.marketdata.api.ImportError
import com.pianonurmi.etrm.marketdata.api.ImportFormatView
import com.pianonurmi.etrm.marketdata.api.ImportResult
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.InstrumentView
import com.pianonurmi.etrm.marketdata.api.MarketDataCommand
import com.pianonurmi.etrm.marketdata.api.MarketDataQuery
import com.pianonurmi.etrm.marketdata.api.PriceIndexView
import com.pianonurmi.etrm.marketdata.api.QuoteFlagged
import com.pianonurmi.etrm.marketdata.api.QuoteQuality
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import com.pianonurmi.etrm.marketdata.api.QuotesImported
import com.pianonurmi.etrm.marketdata.api.RecordFxRate
import com.pianonurmi.etrm.marketdata.api.RecordQuote
import com.pianonurmi.etrm.marketdata.api.UpsertImportFormat
import com.pianonurmi.etrm.marketdata.domain.CurveMath
import com.pianonurmi.etrm.marketdata.domain.FxRateRepository
import com.pianonurmi.etrm.marketdata.domain.FxResolver
import com.pianonurmi.etrm.marketdata.domain.ImportParsers
import com.pianonurmi.etrm.marketdata.domain.ImportRecord
import com.pianonurmi.etrm.marketdata.domain.ImportRepository
import com.pianonurmi.etrm.marketdata.domain.InstrumentRepository
import com.pianonurmi.etrm.marketdata.domain.ParseIssue
import com.pianonurmi.etrm.marketdata.domain.ParsedRow
import com.pianonurmi.etrm.marketdata.domain.Plausibility
import com.pianonurmi.etrm.marketdata.domain.PriceIndexRepository
import com.pianonurmi.etrm.marketdata.domain.QuoteRepository
import com.pianonurmi.etrm.marketdata.domain.StoredFxRate
import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.money.Money
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.FuturesContractQuery
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

@Service
@Transactional
@EnableConfigurationProperties(MarketDataSettings::class)
class MarketDataService(
    private val instruments: InstrumentRepository,
    private val quotes: QuoteRepository,
    private val fxRates: FxRateRepository,
    private val imports: ImportRepository,
    private val indices: PriceIndexRepository,
    private val catalogue: ProductCatalogueQuery,
    private val futures: FuturesContractQuery,
    private val buckets: ProductBucketQuery,
    private val settings: MarketDataSettings,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : MarketDataQuery, MarketDataCommand, FxRates, IndexFixings {
    private val resolver = FxResolver({ date, from, to -> fxRates.latest(date, from, to) }, settings.fxMaxStalenessDays)

    // ----------------------------------------------------------------------------------------------------------
    // FX
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun rate(date: LocalDate, from: String, to: String): FxRateView? {
        val f = from.uppercase()
        val t = to.uppercase()
        return resolver.resolve(date, f, t)?.let { FxRateView(date, f, t, it.rate, it.path, it.oldestDate) }
    }

    @Transactional(readOnly = true)
    override fun require(date: LocalDate, from: String, to: String): FxRateView =
        rate(date, from, to) ?: throw ValidationException("No FX rate $from/$to on or within ${settings.fxMaxStalenessDays} days before $date")

    @Transactional(readOnly = true)
    override fun convert(amount: Money, to: String, date: LocalDate): Money {
        val target = Currency.getInstance(to.uppercase())
        if (amount.currency == target) return amount
        val rate = require(date, amount.currency.currencyCode, target.currencyCode).rate
        return Money((amount.amount * rate).setScale(target.defaultFractionDigits, RoundingMode.HALF_EVEN), target)
    }

    override fun recordFxRate(command: RecordFxRate) {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK, Role.ACCOUNTANT, Role.SYSTEM)
        storeFx(normalise(command), null)
        events.publishEvent(FxRatesPublished(command.source, setOf(command.date), 1))
    }

    private fun normalise(command: RecordFxRate): RecordFxRate {
        if (command.rate.signum() <= 0) throw ValidationException("FX rate must be positive")
        val base = Currency.getInstance(command.base.uppercase()).currencyCode
        val quote = Currency.getInstance(command.quote.uppercase()).currencyCode
        if (base == quote) throw ValidationException("An FX rate needs two different currencies")
        return command.copy(base = base, quote = quote, source = command.source.uppercase())
    }

    /** Stores a rate; the same rate again changes nothing, a different one supersedes the current row. */
    private fun storeFx(rate: RecordFxRate, importId: UUID?): Boolean {
        val current = fxRates.current(rate.date, rate.base, rate.quote, rate.source)
        if (current != null && current.rate.rate.compareTo(rate.rate) == 0) return false
        current?.let { fxRates.supersede(it.id, clock.instant()) }
        fxRates.insert(StoredFxRate(Ids.newId(), rate, importId, actorProvider.current().userId))
        return true
    }

    // ----------------------------------------------------------------------------------------------------------
    // Instruments and quotes
    // ----------------------------------------------------------------------------------------------------------

    override fun createInstrument(command: CreateInstrument): InstrumentView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        val code = command.code.trim().uppercase()
        if (!code.matches(Regex("[A-Z0-9_]+"))) throw ValidationException("Instrument codes use letters, digits and underscores: $code")
        if (instruments.byCode(code) != null) throw ConflictException("Instrument $code already exists")
        val product = command.allowanceProductCode?.let { catalogue.allowanceProduct(it) ?: throw NotFoundException("AllowanceProduct", it) }
        val contract = command.futuresContractCode?.let { futures.futuresContract(it) ?: throw NotFoundException("FuturesContract", it) }
        val bucket = command.bucketCode?.let { buckets.requireBucket(it) }
        val unit = when (command.kind) {
            InstrumentKind.GOO_VINTAGE -> "MWH"
            InstrumentKind.EUA_SPOT, InstrumentKind.EUA_FUTURE, InstrumentKind.EUA_FORWARD -> "TCO2E"
            InstrumentKind.INDEX, InstrumentKind.RATE -> command.unit ?: throw ValidationException("Index and rate instruments need a unit")
        }
        val instrument = InstrumentView(
            code = code,
            kind = command.kind,
            description = command.description,
            allowanceProductCode = contract?.allowanceProductCode ?: product?.code,
            futuresContractCode = contract?.code,
            bucketCode = bucket?.code,
            productionYear = command.productionYear,
            currency = contract?.currency ?: Currency.getInstance(command.currency.uppercase()).currencyCode,
            unit = unit,
            active = true,
            tenor = command.tenor?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
        )
        instrument.tenor?.let { tenor ->
            if (command.kind != InstrumentKind.RATE) throw ValidationException("Only rate instruments have a tenor")
            if (tenor != "ON" && !CurveMath.TENOR.matches(tenor)) throw ValidationException("A tenor is ON or a number of D, W, M or Y, e.g. 3M: $tenor")
        }
        when (command.kind) {
            InstrumentKind.EUA_FUTURE -> if (contract == null) throw ValidationException("A futures instrument needs futuresContractCode")
            InstrumentKind.EUA_SPOT, InstrumentKind.EUA_FORWARD -> if (product == null) {
                throw ValidationException(
                    "An allowance instrument needs allowanceProductCode",
                )
            }
            InstrumentKind.GOO_VINTAGE -> if (bucket == null || command.productionYear == null) {
                throw ValidationException("A GoO instrument needs bucketCode and productionYear")
            }
            else -> Unit
        }
        instruments.insert(instrument)
        return instrument
    }

    override fun addAlias(source: String, externalCode: String, instrumentCode: String) {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        val instrument = instruments.byCode(instrumentCode.uppercase()) ?: throw NotFoundException("Instrument", instrumentCode)
        instruments.addAlias(source.uppercase(), externalCode.trim(), instrument.code)
    }

    override fun recordQuote(command: RecordQuote): QuoteView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        val instrument = instruments.byCode(command.instrumentCode.uppercase()) ?: throw NotFoundException("Instrument", command.instrumentCode)
        return storeQuote(instrument, command.quoteDate, command.quoteType, command.value, command.currency, command.source.uppercase(), null)
            ?: quotes.current(instrument.code, command.quoteDate, command.quoteType, command.source.uppercase())!!
    }

    /** Stores a price unless the same one is already current; returns the new row, or null when nothing changed. */
    private fun storeQuote(
        instrument: InstrumentView,
        date: LocalDate,
        type: QuoteType,
        value: BigDecimal,
        currency: String?,
        source: String,
        importId: UUID?,
    ): QuoteView? {
        val ccy = currency?.uppercase() ?: instrument.currency
        if (ccy != instrument.currency) throw ValidationException("${instrument.code} is quoted in ${instrument.currency}, not $ccy")
        if (type == QuoteType.VOLATILITY && instrument.kind != InstrumentKind.EUA_FUTURE) {
            throw ValidationException("Volatility is quoted on futures instruments, not on ${instrument.code}")
        }
        // Interest rates may be zero or negative; prices may not.
        if (instrument.kind != InstrumentKind.RATE && value.signum() <= 0) throw ValidationException("A price must be positive")
        val current = quotes.current(instrument.code, date, type, source)
        if (current != null && current.value.compareTo(value) == 0) return null
        val previous = quotes.previous(instrument.code, type, date)
        val suspect = if (instrument.kind == InstrumentKind.RATE) {
            Plausibility.suspectRate(previous?.value, value, settings.maxRateMoveBp)
        } else {
            Plausibility.suspect(previous?.value, value, settings.maxMovePct)
        }
        val now = clock.instant()
        current?.let { quotes.supersede(it.id, now) }
        val quote = QuoteView(
            id = Ids.newId(),
            instrumentCode = instrument.code,
            quoteDate = date,
            quoteType = type,
            value = value.setScale(6, RoundingMode.HALF_EVEN),
            currency = ccy,
            source = source,
            importId = importId,
            quality = if (suspect) QuoteQuality.SUSPECT else QuoteQuality.OK,
            qualityNote = if (suspect) suspectNote(instrument, previous!!) else null,
            recordedBy = actorProvider.current().userId,
            recordedAt = now,
            supersededAt = null,
            acknowledgedBy = null,
        )
        quotes.insert(quote)
        if (suspect) events.publishEvent(QuoteFlagged(quote, previous!!.value))
        return quote
    }

    private fun suspectNote(instrument: InstrumentView, previous: QuoteView): String {
        val limit = if (instrument.kind == InstrumentKind.RATE) "${settings.maxRateMoveBp} bp" else "${settings.maxMovePct}%"
        return "Moved more than $limit from ${previous.value.toPlainString()} on ${previous.quoteDate}"
    }

    override fun acknowledgeQuote(quoteId: UUID, note: String): QuoteView {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        if (note.isBlank()) throw ValidationException("Say why the price is right")
        val quote = quotes.find(quoteId) ?: throw NotFoundException("Quote", quoteId)
        if (quote.quality != QuoteQuality.SUSPECT) throw ValidationException("Quote ${quote.id} is ${quote.quality}")
        if (quote.recordedBy == actor.userId) throw SegregationOfDutiesException("A suspect price is acknowledged by someone other than who recorded it")
        quotes.acknowledge(quoteId, actor.userId, note)
        return quotes.find(quoteId)!!
    }

    // ----------------------------------------------------------------------------------------------------------
    // Imports
    // ----------------------------------------------------------------------------------------------------------

    override fun upsertImportFormat(command: UpsertImportFormat): ImportFormatView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        val code = command.code.trim().uppercase()
        val current = imports.format(code)
        val view = ImportFormatView(
            code,
            command.kind,
            command.description,
            command.source.uppercase(),
            command.delimiter,
            command.skipLines,
            command.dateFormat,
            command.decimalSeparator,
            command.columnMapping,
            command.constants,
            command.active,
            (current?.version ?: 0) + 1,
        )
        runCatching {
            java.time.format.DateTimeFormatter.ofPattern(view.dateFormat)
        }.onFailure { throw ValidationException("Bad date format ${view.dateFormat}") }
        imports.upsertFormat(view)
        return view
    }

    /**
     * Imports a file once (by content hash). FX rates are stored by currency pair; prices are resolved to an
     * instrument by code or by the format source's alias. Lines that cannot be read or resolved are reported.
     */
    override fun importFile(formatCode: String, fileName: String, content: ByteArray): ImportResult {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK, Role.ACCOUNTANT, Role.SYSTEM)
        val format = imports.format(formatCode.uppercase())?.takeIf { it.active } ?: throw NotFoundException("ImportFormat", formatCode)
        val hash = HashChain.next(null, content.decodeToString())
        imports.findByHash(format.code, hash)?.let { previous ->
            val errors = imports.errors(previous.id).map { ImportError(it.lineNo, it.message) }
            return ImportResult(
                previous.id,
                format.code,
                previous.fileName,
                previous.rowsRead,
                previous.accepted,
                previous.rejected,
                0,
                errors,
                duplicate = true,
            )
        }
        val parsed = runCatching { ImportParsers.parse(format, content) }
            .getOrElse { throw ValidationException("The file cannot be read as ${format.code}: ${it.message}") }
        val record = ImportRecord(Ids.newId(), format.code, fileName, hash, parsed.linesRead, 0, 0, actorProvider.current().userId)
        // The import row is written first so that the prices and rates can refer to it.
        imports.insert(record, emptyList())
        val issues = parsed.issues.toMutableList()
        var accepted = 0
        var suspect = 0
        val fxDates = mutableSetOf<LocalDate>()
        val quoteDates = mutableSetOf<LocalDate>()
        parsed.rows.forEach { row ->
            when (row) {
                is ParsedRow.Fx -> runCatching { storeFx(normalise(RecordFxRate(row.date, row.base, row.quote, row.rate, format.source)), record.id) }
                    .onSuccess {
                        accepted++
                        fxDates += row.date
                    }
                    .onFailure { issues += ParseIssue(row.lineNo, it.message ?: "Invalid rate") }
                is ParsedRow.Price -> {
                    val instrument = resolveInstrument(format.source, row.instrumentRef)
                    if (instrument == null) {
                        issues += ParseIssue(row.lineNo, "Unknown instrument '${row.instrumentRef}' (no alias for source ${format.source})")
                    } else {
                        runCatching { storeQuote(instrument, row.date, row.type, row.value, row.currency, format.source, record.id) }
                            .onSuccess { stored ->
                                accepted++
                                quoteDates += row.date
                                if (stored?.quality == QuoteQuality.SUSPECT) suspect++
                            }
                            .onFailure { issues += ParseIssue(row.lineNo, it.message ?: "Invalid price") }
                    }
                }
            }
        }
        imports.complete(record.id, accepted, issues)
        return finish(record, format, accepted, issues, suspect, fxDates, quoteDates)
    }

    private fun finish(
        record: ImportRecord,
        format: ImportFormatView,
        accepted: Int,
        issues: List<ParseIssue>,
        suspect: Int,
        fxDates: Set<LocalDate>,
        quoteDates: Set<LocalDate>,
    ): ImportResult {
        if (fxDates.isNotEmpty()) events.publishEvent(FxRatesPublished(format.source, fxDates, accepted))
        if (quoteDates.isNotEmpty()) events.publishEvent(QuotesImported(record.id, format.code, accepted, quoteDates))
        return ImportResult(
            record.id,
            format.code,
            record.fileName,
            record.rowsRead,
            accepted,
            issues.size,
            suspect,
            issues.map {
                ImportError(it.lineNo, it.message)
            },
            false,
        )
    }

    private fun resolveInstrument(source: String, ref: String): InstrumentView? =
        instruments.alias(source, ref.trim())?.let(instruments::byCode) ?: instruments.byCode(ref.trim().uppercase())

    // ----------------------------------------------------------------------------------------------------------
    // Indices
    // ----------------------------------------------------------------------------------------------------------

    override fun createIndex(command: CreatePriceIndex): PriceIndexView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE, Role.RISK)
        val code = command.code.trim().uppercase()
        if (indices.byCode(code) != null) throw ConflictException("Price index $code already exists")
        val instrument = instruments.byCode(command.instrumentCode.uppercase()) ?: throw NotFoundException("Instrument", command.instrumentCode)
        if (command.quoteType == QuoteType.VOLATILITY) throw ValidationException("A price index fixes on prices, not on volatility")
        val index = PriceIndexView(code, command.description, instrument.code, command.quoteType, command.fixingRule, true)
        indices.insert(index)
        return index
    }

    @Transactional(readOnly = true)
    override fun fixing(indexCode: String, fixingDate: LocalDate): FixingView? {
        val index = indices.byCode(indexCode.uppercase()) ?: return null
        val currency = instruments.byCode(index.instrumentCode)?.currency ?: return null
        return when (index.fixingRule) {
            FixingRule.ON_DATE -> quotes.latest(index.instrumentCode, index.quoteType, fixingDate, null)
                ?.takeIf { it.quoteDate == fixingDate }
                ?.let { FixingView(index.code, fixingDate, it.value, currency, listOf(it.id)) }
            FixingRule.PRIOR_DAY -> quotes.latest(index.instrumentCode, index.quoteType, fixingDate.minusDays(1), null)
                ?.let { FixingView(index.code, fixingDate, it.value, currency, listOf(it.id)) }
            FixingRule.MONTH_AVERAGE -> {
                val month = java.time.YearMonth.from(fixingDate)
                val inMonth = quotes.quotes(index.instrumentCode, month.atDay(1), month.atEndOfMonth(), null)
                    .filter { it.quoteType == index.quoteType && it.quality.usable }
                    .groupBy { it.quoteDate }
                    .map { (_, sameDay) -> sameDay.maxBy { it.recordedAt } }
                if (inMonth.isEmpty()) {
                    null
                } else {
                    val average = inMonth.sumOf { it.value }.divide(BigDecimal(inMonth.size), MathContext.DECIMAL64).setScale(6, RoundingMode.HALF_EVEN)
                    FixingView(index.code, fixingDate, average, currency, inMonth.map { it.id })
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun instruments(kind: InstrumentKind?, activeOnly: Boolean): List<InstrumentView> = instruments.all(kind, activeOnly)

    @Transactional(readOnly = true)
    override fun instrument(code: String): InstrumentView? = instruments.byCode(code.uppercase())

    @Transactional(readOnly = true)
    override fun quotes(instrumentCode: String, from: LocalDate, toInclusive: LocalDate, knownAt: Instant?): List<QuoteView> =
        quotes.quotes(instrumentCode.uppercase(), from, toInclusive, knownAt)

    @Transactional(readOnly = true)
    override fun latestQuote(instrumentCode: String, type: QuoteType, date: LocalDate, knownAt: Instant?): QuoteView? =
        quotes.latest(instrumentCode.uppercase(), type, date, knownAt)

    @Transactional(readOnly = true)
    override fun suspectQuotes(): List<QuoteView> = quotes.withQuality(QuoteQuality.SUSPECT)

    @Transactional(readOnly = true)
    override fun fxRates(date: LocalDate, base: String?): List<RecordFxRate> = fxRates.on(date, base?.uppercase())

    @Transactional(readOnly = true)
    override fun importFormats(): List<ImportFormatView> = imports.formats()

    @Transactional(readOnly = true)
    override fun indices(): List<PriceIndexView> = indices.all()

    @Transactional(readOnly = true)
    override fun index(code: String): PriceIndexView? = indices.byCode(code.uppercase())

    /** Age in days of the newest FX rate, for monitoring. */
    @Transactional(readOnly = true)
    fun fxAgeDays(): Long? = fxRates.latestDate()?.let { java.time.temporal.ChronoUnit.DAYS.between(it, LocalDate.now(clock)) }
}
