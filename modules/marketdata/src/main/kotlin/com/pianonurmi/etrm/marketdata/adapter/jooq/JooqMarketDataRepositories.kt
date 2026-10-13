package com.pianonurmi.etrm.marketdata.adapter.jooq

import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.FxRate.FX_RATE
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.ImportFormat.IMPORT_FORMAT
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.Instrument.INSTRUMENT
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.InstrumentAlias.INSTRUMENT_ALIAS
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.MarketDataImport.MARKET_DATA_IMPORT
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.MarketDataImportError.MARKET_DATA_IMPORT_ERROR
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.PriceIndex.PRICE_INDEX
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.Quote.QUOTE
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.records.ImportFormatRecord
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.records.InstrumentRecord
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.records.PriceIndexRecord
import com.pianonurmi.etrm.marketdata.adapter.jooq.generated.tables.records.QuoteRecord
import com.pianonurmi.etrm.marketdata.api.FixingRule
import com.pianonurmi.etrm.marketdata.api.ImportFormatKind
import com.pianonurmi.etrm.marketdata.api.ImportFormatView
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.InstrumentView
import com.pianonurmi.etrm.marketdata.api.PriceIndexView
import com.pianonurmi.etrm.marketdata.api.QuoteQuality
import com.pianonurmi.etrm.marketdata.api.QuoteType
import com.pianonurmi.etrm.marketdata.api.QuoteView
import com.pianonurmi.etrm.marketdata.api.RecordFxRate
import com.pianonurmi.etrm.marketdata.domain.DatedRate
import com.pianonurmi.etrm.marketdata.domain.FxRateRepository
import com.pianonurmi.etrm.marketdata.domain.ImportRecord
import com.pianonurmi.etrm.marketdata.domain.ImportRepository
import com.pianonurmi.etrm.marketdata.domain.InstrumentRepository
import com.pianonurmi.etrm.marketdata.domain.ParseIssue
import com.pianonurmi.etrm.marketdata.domain.PriceIndexRepository
import com.pianonurmi.etrm.marketdata.domain.QuoteRepository
import com.pianonurmi.etrm.marketdata.domain.StoredFxRate
import com.pianonurmi.etrm.platform.json.Json
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class JooqInstrumentRepository(private val dsl: DSLContext) : InstrumentRepository {
    override fun all(kind: InstrumentKind?, activeOnly: Boolean): List<InstrumentView> = dsl.selectFrom(INSTRUMENT)
        .where(kind?.let { INSTRUMENT.KIND.eq(it.name) } ?: DSL.noCondition())
        .and(if (activeOnly) INSTRUMENT.ACTIVE.isTrue else DSL.noCondition())
        .orderBy(INSTRUMENT.CODE)
        .fetch(::toView)

    override fun byCode(code: String): InstrumentView? = dsl.selectFrom(INSTRUMENT).where(INSTRUMENT.CODE.eq(code)).fetchOne()?.let(::toView)

    override fun insert(instrument: InstrumentView) {
        dsl.insertInto(INSTRUMENT)
            .set(INSTRUMENT.CODE, instrument.code)
            .set(INSTRUMENT.KIND, instrument.kind.name)
            .set(INSTRUMENT.DESCRIPTION, instrument.description)
            .set(INSTRUMENT.ALLOWANCE_PRODUCT_CODE, instrument.allowanceProductCode)
            .set(INSTRUMENT.FUTURES_CONTRACT_CODE, instrument.futuresContractCode)
            .set(INSTRUMENT.BUCKET_CODE, instrument.bucketCode)
            .set(INSTRUMENT.PRODUCTION_YEAR, instrument.productionYear)
            .set(INSTRUMENT.CURRENCY, instrument.currency)
            .set(INSTRUMENT.UNIT, instrument.unit)
            .set(INSTRUMENT.ACTIVE, instrument.active)
            .set(INSTRUMENT.TENOR, instrument.tenor)
            .execute()
    }

    override fun alias(source: String, externalCode: String): String? = dsl.select(INSTRUMENT_ALIAS.INSTRUMENT_CODE)
        .from(INSTRUMENT_ALIAS)
        .where(INSTRUMENT_ALIAS.SOURCE.eq(source), INSTRUMENT_ALIAS.EXTERNAL_CODE.eq(externalCode))
        .fetchOne(INSTRUMENT_ALIAS.INSTRUMENT_CODE)

    override fun addAlias(source: String, externalCode: String, instrumentCode: String) {
        dsl.insertInto(INSTRUMENT_ALIAS)
            .set(INSTRUMENT_ALIAS.SOURCE, source)
            .set(INSTRUMENT_ALIAS.EXTERNAL_CODE, externalCode)
            .set(INSTRUMENT_ALIAS.INSTRUMENT_CODE, instrumentCode)
            .onConflict(INSTRUMENT_ALIAS.SOURCE, INSTRUMENT_ALIAS.EXTERNAL_CODE)
            .doUpdate()
            .set(INSTRUMENT_ALIAS.INSTRUMENT_CODE, instrumentCode)
            .execute()
    }

    private fun toView(r: InstrumentRecord) = InstrumentView(
        r.code,
        InstrumentKind.valueOf(r.kind),
        r.description,
        r.allowanceProductCode,
        r.futuresContractCode,
        r.bucketCode,
        r.productionYear,
        r.currency,
        r.unit,
        r.active,
        r.tenor,
    )
}

@Repository
class JooqQuoteRepository(private val dsl: DSLContext) : QuoteRepository {
    override fun current(instrumentCode: String, date: LocalDate, type: QuoteType, source: String): QuoteView? = dsl.selectFrom(QUOTE)
        .where(QUOTE.INSTRUMENT_CODE.eq(instrumentCode), QUOTE.QUOTE_DATE.eq(date), QUOTE.QUOTE_TYPE.eq(type.name), QUOTE.SOURCE.eq(source))
        .and(QUOTE.SUPERSEDED_AT.isNull)
        .forUpdate()
        .fetchOne()?.let(::toView)

    override fun previous(instrumentCode: String, type: QuoteType, date: LocalDate): QuoteView? = dsl.selectFrom(QUOTE)
        .where(QUOTE.INSTRUMENT_CODE.eq(instrumentCode), QUOTE.QUOTE_TYPE.eq(type.name), QUOTE.QUOTE_DATE.lt(date), QUOTE.SUPERSEDED_AT.isNull)
        .orderBy(QUOTE.QUOTE_DATE.desc(), QUOTE.RECORDED_AT.desc())
        .limit(1)
        .fetchOne()?.let(::toView)

    override fun insert(quote: QuoteView) {
        dsl.insertInto(QUOTE)
            .set(QUOTE.ID, quote.id)
            .set(QUOTE.INSTRUMENT_CODE, quote.instrumentCode)
            .set(QUOTE.QUOTE_DATE, quote.quoteDate)
            .set(QUOTE.QUOTE_TYPE, quote.quoteType.name)
            .set(QUOTE.PRICE, quote.value)
            .set(QUOTE.CURRENCY, quote.currency)
            .set(QUOTE.SOURCE, quote.source)
            .set(QUOTE.IMPORT_ID, quote.importId)
            .set(QUOTE.QUALITY, quote.quality.name)
            .set(QUOTE.QUALITY_NOTE, quote.qualityNote)
            .set(QUOTE.RECORDED_BY, quote.recordedBy)
            .set(QUOTE.RECORDED_AT, quote.recordedAt)
            .execute()
    }

    override fun supersede(id: UUID, at: Instant) {
        dsl.update(QUOTE).set(QUOTE.SUPERSEDED_AT, at).where(QUOTE.ID.eq(id), QUOTE.SUPERSEDED_AT.isNull).execute()
    }

    override fun find(id: UUID): QuoteView? = dsl.selectFrom(QUOTE).where(QUOTE.ID.eq(id)).fetchOne()?.let(::toView)

    override fun quotes(instrumentCode: String, from: LocalDate, toInclusive: LocalDate, knownAt: Instant?): List<QuoteView> = dsl.selectFrom(QUOTE)
        .where(QUOTE.INSTRUMENT_CODE.eq(instrumentCode), QUOTE.QUOTE_DATE.between(from, toInclusive), known(knownAt))
        .orderBy(QUOTE.QUOTE_DATE, QUOTE.QUOTE_TYPE, QUOTE.SOURCE)
        .fetch(::toView)

    override fun latest(instrumentCode: String, type: QuoteType, date: LocalDate, knownAt: Instant?): QuoteView? = dsl.selectFrom(QUOTE)
        .where(QUOTE.INSTRUMENT_CODE.eq(instrumentCode), QUOTE.QUOTE_TYPE.eq(type.name), QUOTE.QUOTE_DATE.le(date), known(knownAt))
        .and(QUOTE.QUALITY.ne(QuoteQuality.SUSPECT.name))
        .orderBy(QUOTE.QUOTE_DATE.desc(), QUOTE.RECORDED_AT.desc())
        .limit(1)
        .fetchOne()?.let(::toView)

    override fun withQuality(quality: QuoteQuality): List<QuoteView> = dsl.selectFrom(QUOTE)
        .where(QUOTE.QUALITY.eq(quality.name), QUOTE.SUPERSEDED_AT.isNull)
        .orderBy(QUOTE.QUOTE_DATE, QUOTE.INSTRUMENT_CODE)
        .fetch(::toView)

    override fun acknowledge(id: UUID, by: String, note: String) {
        dsl.update(QUOTE)
            .set(QUOTE.QUALITY, QuoteQuality.ACKNOWLEDGED.name)
            .set(QUOTE.ACKNOWLEDGED_BY, by)
            .set(QUOTE.QUALITY_NOTE, note)
            .where(QUOTE.ID.eq(id))
            .execute()
    }

    /** Rows current at [knownAt] (or now): recorded by then and not superseded by then. */
    private fun known(knownAt: Instant?): Condition = if (knownAt == null) {
        QUOTE.SUPERSEDED_AT.isNull
    } else {
        QUOTE.RECORDED_AT.le(knownAt).and(QUOTE.SUPERSEDED_AT.isNull.or(QUOTE.SUPERSEDED_AT.gt(knownAt)))
    }

    private fun toView(r: QuoteRecord) = QuoteView(
        r.id,
        r.instrumentCode,
        r.quoteDate,
        QuoteType.valueOf(r.quoteType),
        r.price,
        r.currency,
        r.source,
        r.importId,
        QuoteQuality.valueOf(r.quality),
        r.qualityNote,
        r.recordedBy,
        r.recordedAt,
        r.supersededAt,
        r.acknowledgedBy,
    )
}

@Repository
class JooqFxRateRepository(private val dsl: DSLContext) : FxRateRepository {
    override fun latest(date: LocalDate, base: String, quote: String): DatedRate? = dsl.select(FX_RATE.RATE_DATE, FX_RATE.RATE)
        .from(FX_RATE)
        .where(FX_RATE.BASE_CURRENCY.eq(base), FX_RATE.QUOTE_CURRENCY.eq(quote), FX_RATE.RATE_DATE.le(date), FX_RATE.SUPERSEDED_AT.isNull)
        .orderBy(FX_RATE.RATE_DATE.desc(), FX_RATE.RECORDED_AT.desc())
        .limit(1)
        .fetchOne { DatedRate(it.value1(), it.value2()) }

    override fun current(date: LocalDate, base: String, quote: String, source: String): StoredFxRate? = dsl.selectFrom(FX_RATE)
        .where(FX_RATE.RATE_DATE.eq(date), FX_RATE.BASE_CURRENCY.eq(base), FX_RATE.QUOTE_CURRENCY.eq(quote), FX_RATE.SOURCE.eq(source))
        .and(FX_RATE.SUPERSEDED_AT.isNull)
        .forUpdate()
        .fetchOne { StoredFxRate(it.id, RecordFxRate(it.rateDate, it.baseCurrency, it.quoteCurrency, it.rate, it.source), it.importId, it.recordedBy) }

    override fun insert(rate: StoredFxRate) {
        dsl.insertInto(FX_RATE)
            .set(FX_RATE.ID, rate.id)
            .set(FX_RATE.RATE_DATE, rate.rate.date)
            .set(FX_RATE.BASE_CURRENCY, rate.rate.base)
            .set(FX_RATE.QUOTE_CURRENCY, rate.rate.quote)
            .set(FX_RATE.RATE, rate.rate.rate)
            .set(FX_RATE.SOURCE, rate.rate.source)
            .set(FX_RATE.IMPORT_ID, rate.importId)
            .set(FX_RATE.RECORDED_BY, rate.recordedBy)
            .execute()
    }

    override fun supersede(id: UUID, at: Instant) {
        dsl.update(FX_RATE).set(FX_RATE.SUPERSEDED_AT, at).where(FX_RATE.ID.eq(id), FX_RATE.SUPERSEDED_AT.isNull).execute()
    }

    override fun on(date: LocalDate, base: String?): List<RecordFxRate> = dsl.selectFrom(FX_RATE)
        .where(FX_RATE.RATE_DATE.eq(date), FX_RATE.SUPERSEDED_AT.isNull)
        .and(base?.let { FX_RATE.BASE_CURRENCY.eq(it) } ?: DSL.noCondition())
        .orderBy(FX_RATE.BASE_CURRENCY, FX_RATE.QUOTE_CURRENCY, FX_RATE.SOURCE)
        .fetch { RecordFxRate(it.rateDate, it.baseCurrency, it.quoteCurrency, it.rate, it.source) }

    override fun latestDate(): LocalDate? = dsl.select(
        DSL.max(FX_RATE.RATE_DATE),
    ).from(FX_RATE).where(FX_RATE.SUPERSEDED_AT.isNull).fetchOne(0, LocalDate::class.java)
}

@Repository
class JooqImportRepository(private val dsl: DSLContext) : ImportRepository {
    private val mapType = object : TypeReference<Map<String, String>>() {}

    override fun formats(): List<ImportFormatView> = dsl.selectFrom(IMPORT_FORMAT).orderBy(IMPORT_FORMAT.CODE).fetch(::toView)

    override fun format(code: String): ImportFormatView? = dsl.selectFrom(IMPORT_FORMAT).where(IMPORT_FORMAT.CODE.eq(code)).fetchOne()?.let(::toView)

    override fun upsertFormat(format: ImportFormatView) {
        dsl.insertInto(IMPORT_FORMAT)
            .set(IMPORT_FORMAT.CODE, format.code)
            .set(IMPORT_FORMAT.KIND, format.kind.name)
            .set(IMPORT_FORMAT.DESCRIPTION, format.description)
            .set(IMPORT_FORMAT.SOURCE, format.source)
            .set(IMPORT_FORMAT.DELIMITER, format.delimiter.toString())
            .set(IMPORT_FORMAT.SKIP_LINES, format.skipLines)
            .set(IMPORT_FORMAT.DATE_FORMAT, format.dateFormat)
            .set(IMPORT_FORMAT.DECIMAL_SEPARATOR, format.decimalSeparator.toString())
            .set(IMPORT_FORMAT.COLUMN_MAPPING, JSONB.jsonb(Json.mapper.writeValueAsString(format.columnMapping)))
            .set(IMPORT_FORMAT.CONSTANTS, JSONB.jsonb(Json.mapper.writeValueAsString(format.constants)))
            .set(IMPORT_FORMAT.ACTIVE, format.active)
            .set(IMPORT_FORMAT.VERSION, format.version)
            .onConflict(IMPORT_FORMAT.CODE)
            .doUpdate()
            .set(IMPORT_FORMAT.KIND, format.kind.name)
            .set(IMPORT_FORMAT.DESCRIPTION, format.description)
            .set(IMPORT_FORMAT.SOURCE, format.source)
            .set(IMPORT_FORMAT.DELIMITER, format.delimiter.toString())
            .set(IMPORT_FORMAT.SKIP_LINES, format.skipLines)
            .set(IMPORT_FORMAT.DATE_FORMAT, format.dateFormat)
            .set(IMPORT_FORMAT.DECIMAL_SEPARATOR, format.decimalSeparator.toString())
            .set(IMPORT_FORMAT.COLUMN_MAPPING, JSONB.jsonb(Json.mapper.writeValueAsString(format.columnMapping)))
            .set(IMPORT_FORMAT.CONSTANTS, JSONB.jsonb(Json.mapper.writeValueAsString(format.constants)))
            .set(IMPORT_FORMAT.ACTIVE, format.active)
            .set(IMPORT_FORMAT.VERSION, format.version)
            .set(IMPORT_FORMAT.UPDATED_AT, Instant.now())
            .execute()
    }

    override fun findByHash(formatCode: String, contentHash: String): ImportRecord? = dsl.selectFrom(MARKET_DATA_IMPORT)
        .where(MARKET_DATA_IMPORT.FORMAT_CODE.eq(formatCode), MARKET_DATA_IMPORT.CONTENT_HASH.eq(contentHash))
        .fetchOne { ImportRecord(it.id, it.formatCode, it.fileName, it.contentHash, it.rowsRead, it.accepted, it.rejected, it.importedBy) }

    override fun insert(record: ImportRecord, errors: List<ParseIssue>) {
        dsl.insertInto(MARKET_DATA_IMPORT)
            .set(MARKET_DATA_IMPORT.ID, record.id)
            .set(MARKET_DATA_IMPORT.FORMAT_CODE, record.formatCode)
            .set(MARKET_DATA_IMPORT.FILE_NAME, record.fileName)
            .set(MARKET_DATA_IMPORT.CONTENT_HASH, record.contentHash)
            .set(MARKET_DATA_IMPORT.ROWS_READ, record.rowsRead)
            .set(MARKET_DATA_IMPORT.ACCEPTED, record.accepted)
            .set(MARKET_DATA_IMPORT.REJECTED, record.rejected)
            .set(MARKET_DATA_IMPORT.IMPORTED_BY, record.importedBy)
            .execute()
        insertErrors(record.id, errors)
    }

    override fun complete(importId: UUID, accepted: Int, errors: List<ParseIssue>) {
        dsl.update(MARKET_DATA_IMPORT)
            .set(MARKET_DATA_IMPORT.ACCEPTED, accepted)
            .set(MARKET_DATA_IMPORT.REJECTED, errors.size)
            .where(MARKET_DATA_IMPORT.ID.eq(importId))
            .execute()
        insertErrors(importId, errors)
    }

    private fun insertErrors(importId: UUID, errors: List<ParseIssue>) {
        errors.distinctBy { it.lineNo }.forEach { e ->
            dsl.insertInto(MARKET_DATA_IMPORT_ERROR)
                .set(MARKET_DATA_IMPORT_ERROR.IMPORT_ID, importId)
                .set(MARKET_DATA_IMPORT_ERROR.LINE_NO, e.lineNo)
                .set(MARKET_DATA_IMPORT_ERROR.MESSAGE, e.message)
                .execute()
        }
    }

    override fun errors(importId: UUID): List<ParseIssue> = dsl.selectFrom(MARKET_DATA_IMPORT_ERROR)
        .where(MARKET_DATA_IMPORT_ERROR.IMPORT_ID.eq(importId))
        .orderBy(MARKET_DATA_IMPORT_ERROR.LINE_NO)
        .fetch { ParseIssue(it.lineNo, it.message) }

    private fun toView(r: ImportFormatRecord) = ImportFormatView(
        r.code,
        ImportFormatKind.valueOf(r.kind),
        r.description,
        r.source,
        r.delimiter.first(),
        r.skipLines,
        r.dateFormat,
        r.decimalSeparator.first(),
        Json.mapper.readValue(r.columnMapping.data(), mapType),
        Json.mapper.readValue(r.constants.data(), mapType),
        r.active,
        r.version,
    )
}

@Repository
class JooqPriceIndexRepository(private val dsl: DSLContext) : PriceIndexRepository {
    override fun all(): List<PriceIndexView> = dsl.selectFrom(PRICE_INDEX).orderBy(PRICE_INDEX.CODE).fetch(::toView)

    override fun byCode(code: String): PriceIndexView? = dsl.selectFrom(PRICE_INDEX).where(PRICE_INDEX.CODE.eq(code)).fetchOne()?.let(::toView)

    override fun insert(index: PriceIndexView) {
        dsl.insertInto(PRICE_INDEX)
            .set(PRICE_INDEX.CODE, index.code)
            .set(PRICE_INDEX.DESCRIPTION, index.description)
            .set(PRICE_INDEX.INSTRUMENT_CODE, index.instrumentCode)
            .set(PRICE_INDEX.QUOTE_TYPE, index.quoteType.name)
            .set(PRICE_INDEX.FIXING_RULE, index.fixingRule.name)
            .set(PRICE_INDEX.ACTIVE, index.active)
            .execute()
    }

    private fun toView(r: PriceIndexRecord) =
        PriceIndexView(r.code, r.description, r.instrumentCode, QuoteType.valueOf(r.quoteType), FixingRule.valueOf(r.fixingRule), r.active)
}
