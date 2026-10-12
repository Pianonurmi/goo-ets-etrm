package com.pianonurmi.etrm.referencedata.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/** Where an expected production figure comes from (ADR 0038). */
enum class ForecastSource {
    /** Entered for the device and month by a person. */
    ENTERED,

    /** Loaded from a forecast file. */
    IMPORTED,

    /** No forecast: the device's capacity × the technology's load factor for the month × the hours in it. */
    LOAD_FACTOR,
}

/**
 * A device's expected production for one month, in MWh: [expectedMwh] is the central (P50) figure, [p90Mwh] the
 * figure it should exceed nine years in ten. Forecasts are versioned; every change is kept in the history.
 */
data class ProductionForecastView(
    val deviceId: UUID,
    val month: YearMonth,
    val expectedMwh: Long,
    val p90Mwh: Long,
    val source: ForecastSource,
    val version: Int,
    val recordedBy: String,
    val recordedAt: Instant,
)

/** What a device is expected to produce in a month: its forecast where there is one, else from the load factor. */
data class ExpectedProduction(
    val deviceId: UUID,
    val gsrn: String,
    val month: YearMonth,
    val expectedMwh: Long,
    val p90Mwh: Long,
    val source: ForecastSource,
)

/** A technology's typical capacity factor per calendar month (0..1): central and P90. Illustrative until replaced. */
data class LoadFactorView(val technologyCode: String, val monthOfYear: Int, val factor: BigDecimal, val p90Factor: BigDecimal)

data class RecordForecast(val deviceId: UUID, val month: YearMonth, val expectedMwh: Long, val p90Mwh: Long)

data class UpdateLoadFactor(val factor: BigDecimal, val p90Factor: BigDecimal)

/** One CSV line per device and month: `gsrn,month,expected_mwh,p90_mwh` (month as `2026-11`). */
data class ForecastImportResult(val imported: Int, val rejected: Int, val errors: List<String>)

interface ProductionForecastQuery {
    fun forecasts(deviceId: UUID): List<ProductionForecastView>

    /**
     * One figure per month from [from] to [to] inclusive while the device operates (after commissioning, before
     * decommissioning): its forecast where entered or imported, otherwise capacity × load factor × hours of the month.
     * Empty for an unknown device.
     */
    fun expected(deviceId: UUID, from: YearMonth, to: YearMonth): List<ExpectedProduction>

    fun loadFactors(): List<LoadFactorView>
}

interface ProductionForecastCommand {
    /** Creates or replaces the device's forecast for the month (MIDDLE_OFFICE); the previous figure goes to history. */
    fun record(command: RecordForecast): ProductionForecastView

    /** Loads a forecast file (MIDDLE_OFFICE); a bad line is reported and skipped, the rest is loaded. */
    fun importCsv(csv: String): ForecastImportResult

    fun updateLoadFactor(technologyCode: String, monthOfYear: Int, command: UpdateLoadFactor): LoadFactorView
}

/** A device's expected production changed for these months: plans counting on it may need to be made again. */
data class ProductionForecastChanged(val deviceId: UUID, val months: List<YearMonth>, val metadata: EventMetadata = EventMetadata()) :
    DomainEvent by metadata
