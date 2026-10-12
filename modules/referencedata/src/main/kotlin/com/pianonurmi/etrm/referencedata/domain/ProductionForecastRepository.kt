package com.pianonurmi.etrm.referencedata.domain

import com.pianonurmi.etrm.referencedata.api.ForecastSource
import com.pianonurmi.etrm.referencedata.api.LoadFactorView
import com.pianonurmi.etrm.referencedata.api.ProductionForecastView
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID

/** Persistence port for production forecasts and load factors. */
interface ProductionForecastRepository {
    fun forecasts(deviceId: UUID): List<ProductionForecastView>

    fun forecasts(deviceId: UUID, from: YearMonth, to: YearMonth): List<ProductionForecastView>

    /** Creates the forecast or replaces the current one (version + 1); the replaced row goes to the history. */
    fun upsert(
        deviceId: UUID,
        month: YearMonth,
        expectedMwh: Long,
        p90Mwh: Long,
        source: ForecastSource,
        recordedBy: String,
    ): ProductionForecastView

    fun loadFactors(): List<LoadFactorView>

    /** The technology's factors by calendar month (1..12). */
    fun loadFactorsOf(technologyCode: String): Map<Int, LoadFactorView>

    fun upsertLoadFactor(technologyCode: String, monthOfYear: Int, factor: BigDecimal, p90Factor: BigDecimal): LoadFactorView
}
