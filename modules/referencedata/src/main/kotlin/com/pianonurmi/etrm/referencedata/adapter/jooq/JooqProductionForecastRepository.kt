package com.pianonurmi.etrm.referencedata.adapter.jooq

import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.LoadFactor.LOAD_FACTOR
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.ProductionForecast.PRODUCTION_FORECAST
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.LoadFactorRecord
import com.pianonurmi.etrm.referencedata.adapter.jooq.generated.tables.records.ProductionForecastRecord
import com.pianonurmi.etrm.referencedata.api.ForecastSource
import com.pianonurmi.etrm.referencedata.api.LoadFactorView
import com.pianonurmi.etrm.referencedata.api.ProductionForecastView
import com.pianonurmi.etrm.referencedata.domain.ProductionForecastRepository
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

@Repository
class JooqProductionForecastRepository(private val dsl: DSLContext) : ProductionForecastRepository {
    override fun forecasts(deviceId: UUID): List<ProductionForecastView> = dsl.selectFrom(PRODUCTION_FORECAST)
        .where(PRODUCTION_FORECAST.DEVICE_ID.eq(deviceId))
        .orderBy(PRODUCTION_FORECAST.MONTH)
        .fetch(::toForecast)

    override fun forecasts(deviceId: UUID, from: YearMonth, to: YearMonth): List<ProductionForecastView> = dsl.selectFrom(PRODUCTION_FORECAST)
        .where(
            PRODUCTION_FORECAST.DEVICE_ID.eq(deviceId),
            PRODUCTION_FORECAST.MONTH.ge(from.atDay(1)),
            PRODUCTION_FORECAST.MONTH.le(to.atDay(1)),
        )
        .orderBy(PRODUCTION_FORECAST.MONTH)
        .fetch(::toForecast)

    // One statement, so concurrent first writes cannot collide; the table's trigger copies the replaced row to the history.
    override fun upsert(
        deviceId: UUID,
        month: YearMonth,
        expectedMwh: Long,
        p90Mwh: Long,
        source: ForecastSource,
        recordedBy: String,
        recordedAt: Instant,
    ): ProductionForecastView = dsl.insertInto(PRODUCTION_FORECAST)
        .set(PRODUCTION_FORECAST.DEVICE_ID, deviceId)
        .set(PRODUCTION_FORECAST.MONTH, month.atDay(1))
        .set(PRODUCTION_FORECAST.EXPECTED_MWH, expectedMwh)
        .set(PRODUCTION_FORECAST.P90_MWH, p90Mwh)
        .set(PRODUCTION_FORECAST.SOURCE, source.name)
        .set(PRODUCTION_FORECAST.VERSION, 1)
        .set(PRODUCTION_FORECAST.RECORDED_BY, recordedBy)
        .set(PRODUCTION_FORECAST.RECORDED_AT, recordedAt)
        .onConflict(PRODUCTION_FORECAST.DEVICE_ID, PRODUCTION_FORECAST.MONTH)
        .doUpdate()
        .set(PRODUCTION_FORECAST.EXPECTED_MWH, expectedMwh)
        .set(PRODUCTION_FORECAST.P90_MWH, p90Mwh)
        .set(PRODUCTION_FORECAST.SOURCE, source.name)
        .set(PRODUCTION_FORECAST.VERSION, PRODUCTION_FORECAST.VERSION.plus(1))
        .set(PRODUCTION_FORECAST.RECORDED_BY, recordedBy)
        .set(PRODUCTION_FORECAST.RECORDED_AT, recordedAt)
        .returning()
        .fetchSingle()
        .let(::toForecast)

    override fun loadFactors(): List<LoadFactorView> = dsl.selectFrom(LOAD_FACTOR)
        .orderBy(LOAD_FACTOR.TECHNOLOGY_CODE, LOAD_FACTOR.MONTH_OF_YEAR)
        .fetch(::toLoadFactor)

    override fun loadFactorsOf(technologyCode: String): Map<Int, LoadFactorView> = dsl.selectFrom(LOAD_FACTOR)
        .where(LOAD_FACTOR.TECHNOLOGY_CODE.eq(technologyCode))
        .fetch(::toLoadFactor)
        .associateBy { it.monthOfYear }

    override fun upsertLoadFactor(technologyCode: String, monthOfYear: Int, factor: BigDecimal, p90Factor: BigDecimal): LoadFactorView =
        dsl.insertInto(LOAD_FACTOR)
            .set(LOAD_FACTOR.TECHNOLOGY_CODE, technologyCode)
            .set(LOAD_FACTOR.MONTH_OF_YEAR, monthOfYear)
            .set(LOAD_FACTOR.FACTOR, factor)
            .set(LOAD_FACTOR.P90_FACTOR, p90Factor)
            .onConflict(LOAD_FACTOR.TECHNOLOGY_CODE, LOAD_FACTOR.MONTH_OF_YEAR)
            .doUpdate()
            .set(LOAD_FACTOR.FACTOR, factor)
            .set(LOAD_FACTOR.P90_FACTOR, p90Factor)
            .returning()
            .fetchSingle()
            .let(::toLoadFactor)

    private fun toForecast(r: ProductionForecastRecord) = ProductionForecastView(
        deviceId = r.deviceId,
        month = YearMonth.from(r.month),
        expectedMwh = r.expectedMwh,
        p90Mwh = r.p90Mwh,
        source = ForecastSource.valueOf(r.source),
        version = r.version,
        recordedBy = r.recordedBy,
        recordedAt = r.recordedAt,
    )

    private fun toLoadFactor(r: LoadFactorRecord) = LoadFactorView(r.technologyCode, r.monthOfYear, r.factor, r.p90Factor)
}
