package com.pianonurmi.etrm.referencedata.adapter.rest

import com.pianonurmi.etrm.referencedata.api.ExpectedProduction
import com.pianonurmi.etrm.referencedata.api.ForecastImportResult
import com.pianonurmi.etrm.referencedata.api.LoadFactorView
import com.pianonurmi.etrm.referencedata.api.ProductionForecastCommand
import com.pianonurmi.etrm.referencedata.api.ProductionForecastQuery
import com.pianonurmi.etrm.referencedata.api.ProductionForecastView
import com.pianonurmi.etrm.referencedata.api.RecordForecast
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.UpdateLoadFactor
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
import java.util.UUID

/** Expected production per device and month, and the load factors behind it. Writes need MIDDLE_OFFICE (checked in the service). */
@RestController
@RequestMapping("/api/v1/reference")
@Tag(name = "Production forecasts", description = "Expected production per device and month, entered, imported or from technology load factors")
class ProductionForecastController(
    private val query: ProductionForecastQuery,
    private val command: ProductionForecastCommand,
    private val referenceData: ReferenceDataQuery,
) {
    @GetMapping("/devices/{id}/forecasts")
    fun forecasts(
        @PathVariable id: UUID,
    ): List<ProductionForecastView> {
        referenceData.requireProductionDevice(id)
        return query.forecasts(id)
    }

    @PutMapping("/devices/{id}/forecasts/{month}")
    fun recordForecast(
        @PathVariable id: UUID,
        @PathVariable month: YearMonth,
        @Valid @RequestBody body: ForecastRequest,
    ): ProductionForecastView = command.record(RecordForecast(id, month, body.expectedMwh, body.p90Mwh))

    @GetMapping("/devices/{id}/expected")
    fun expected(
        @PathVariable id: UUID,
        @RequestParam from: YearMonth,
        @RequestParam to: YearMonth,
    ): List<ExpectedProduction> {
        referenceData.requireProductionDevice(id)
        return query.expected(id, from, to)
    }

    /** Forecast file: `gsrn,month,expected_mwh,p90_mwh` with a header line; a bad line is skipped and reported. */
    @PostMapping("/forecasts/import", consumes = ["text/csv", "text/plain"])
    fun importForecasts(
        @RequestBody content: String,
    ): ForecastImportResult = command.importCsv(content)

    @GetMapping("/load-factors")
    fun loadFactors(): List<LoadFactorView> = query.loadFactors()

    @PutMapping("/load-factors/{technologyCode}/{monthOfYear}")
    fun updateLoadFactor(
        @PathVariable technologyCode: String,
        @PathVariable monthOfYear: Int,
        @Valid @RequestBody body: UpdateLoadFactor,
    ): LoadFactorView = command.updateLoadFactor(technologyCode, monthOfYear, body)
}

data class ForecastRequest(val expectedMwh: Long, val p90Mwh: Long)
