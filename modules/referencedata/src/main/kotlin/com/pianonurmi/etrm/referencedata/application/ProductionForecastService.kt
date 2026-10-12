package com.pianonurmi.etrm.referencedata.application

import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.text.Csv
import com.pianonurmi.etrm.referencedata.api.ExpectedProduction
import com.pianonurmi.etrm.referencedata.api.ForecastImportResult
import com.pianonurmi.etrm.referencedata.api.ForecastSource
import com.pianonurmi.etrm.referencedata.api.LoadFactorView
import com.pianonurmi.etrm.referencedata.api.ProductionDeviceView
import com.pianonurmi.etrm.referencedata.api.ProductionForecastChanged
import com.pianonurmi.etrm.referencedata.api.ProductionForecastCommand
import com.pianonurmi.etrm.referencedata.api.ProductionForecastQuery
import com.pianonurmi.etrm.referencedata.api.ProductionForecastView
import com.pianonurmi.etrm.referencedata.api.RecordForecast
import com.pianonurmi.etrm.referencedata.api.UpdateLoadFactor
import com.pianonurmi.etrm.referencedata.domain.ProductionForecastRepository
import com.pianonurmi.etrm.referencedata.domain.ReferenceDataRepository
import com.pianonurmi.etrm.taxonomy.api.TaxonomyQuery
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Expected production per device and month (ADR 0038): entered or imported forecasts, and for months without one the
 * device's capacity × the technology's load factor × the hours in the month.
 */
@Service
@Transactional(readOnly = true)
class ProductionForecastService(
    private val repository: ProductionForecastRepository,
    private val devices: ReferenceDataRepository,
    private val taxonomy: TaxonomyQuery,
    private val events: ApplicationEventPublisher,
    private val actorProvider: ActorProvider,
) : ProductionForecastQuery, ProductionForecastCommand {
    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    override fun forecasts(deviceId: UUID): List<ProductionForecastView> = repository.forecasts(deviceId)

    override fun expected(deviceId: UUID, from: YearMonth, to: YearMonth): List<ExpectedProduction> {
        if (from.isAfter(to)) throw ValidationException("from must not be after to")
        if (ChronoUnit.MONTHS.between(from, to) >= MAX_RANGE_MONTHS) {
            throw ValidationException("The range is limited to $MAX_RANGE_MONTHS months")
        }
        val device = devices.findProductionDevice(deviceId) ?: return emptyList()
        val months = generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.filter { operates(device, it) }.toList()
        if (months.isEmpty()) return emptyList()
        val entered = repository.forecasts(deviceId, months.first(), months.last()).associateBy { it.month }
        val factors = repository.loadFactorsOf(device.technologyCode)
        return months.map { month ->
            val forecast = entered[month]
            if (forecast != null) {
                ExpectedProduction(deviceId, device.gsrn, month, forecast.expectedMwh, forecast.p90Mwh, forecast.source)
            } else {
                val factor = factors[month.monthValue]
                ExpectedProduction(
                    deviceId,
                    device.gsrn,
                    month,
                    factor?.let { mwh(device, month, it.factor) } ?: 0,
                    factor?.let { mwh(device, month, it.p90Factor) } ?: 0,
                    ForecastSource.LOAD_FACTOR,
                )
            }
        }
    }

    override fun loadFactors(): List<LoadFactorView> = repository.loadFactors()

    /** Whether the device is in operation during any part of the month (commissioned before it ends, not decommissioned before it starts). */
    private fun operates(device: ProductionDeviceView, month: YearMonth): Boolean =
        device.commissioningDate.isBefore(month.plusMonths(1).atDay(1)) &&
            (device.decommissioningDate == null || device.decommissioningDate.isAfter(month.atDay(1)))

    /** Capacity (kW) / 1000 × hours in the month (days × 24, ignoring DST) × [factor], rounded half-up to whole MWh. */
    private fun mwh(device: ProductionDeviceView, month: YearMonth, factor: BigDecimal): Long = device.capacityKw
        .multiply(BigDecimal(month.lengthOfMonth() * HOURS_PER_DAY))
        .multiply(factor)
        .divide(KW_PER_MW, 0, RoundingMode.HALF_UP)
        .longValueExact()

    // ----------------------------------------------------------------------------------------------------------
    // Commands
    // ----------------------------------------------------------------------------------------------------------

    @Transactional
    override fun record(command: RecordForecast): ProductionForecastView {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE)
        val device = devices.findProductionDevice(command.deviceId) ?: throw NotFoundException("ProductionDevice", command.deviceId)
        validate(command.expectedMwh, command.p90Mwh)
        val view = repository.upsert(device.id, command.month, command.expectedMwh, command.p90Mwh, ForecastSource.ENTERED, actor.userId)
        events.publishEvent(ProductionForecastChanged(device.id, listOf(command.month)))
        return view
    }

    @Transactional
    override fun importCsv(csv: String): ForecastImportResult {
        val actor = actorProvider.current()
        actor.requireRole(Role.MIDDLE_OFFICE)
        val records = Csv.parse(csv)
        val errors = mutableListOf<String>()
        val changed = linkedMapOf<UUID, MutableSet<YearMonth>>()
        var imported = 0
        records.forEachIndexed { index, fields ->
            val line = index + 1
            if (index == 0 && fields.firstOrNull()?.trim().equals(HEADER_FIRST_FIELD, ignoreCase = true)) return@forEachIndexed
            try {
                val row = parseRow(fields)
                val device = devices.findProductionDeviceByGsrn(row.gsrn) ?: throw ValidationException("unknown device ${row.gsrn}")
                validate(row.expectedMwh, row.p90Mwh)
                repository.upsert(device.id, row.month, row.expectedMwh, row.p90Mwh, ForecastSource.IMPORTED, actor.userId)
                changed.getOrPut(device.id) { linkedSetOf() }.add(row.month)
                imported++
            } catch (e: ValidationException) {
                errors += "line $line: ${e.message}"
            }
        }
        changed.forEach { (deviceId, months) -> events.publishEvent(ProductionForecastChanged(deviceId, months.sorted())) }
        return ForecastImportResult(imported, errors.size, errors)
    }

    @Transactional
    override fun updateLoadFactor(technologyCode: String, monthOfYear: Int, command: UpdateLoadFactor): LoadFactorView {
        actorProvider.current().requireRole(Role.MIDDLE_OFFICE)
        if (monthOfYear !in 1..MONTHS_PER_YEAR) throw ValidationException("monthOfYear must be between 1 and $MONTHS_PER_YEAR")
        val code = technologyCode.trim().uppercase()
        taxonomy.require(TaxonomyScheme.TECHNOLOGY, code)
        if (command.factor < BigDecimal.ZERO || command.factor > BigDecimal.ONE) throw ValidationException("factor must be between 0 and 1")
        if (command.p90Factor < BigDecimal.ZERO || command.p90Factor > command.factor) {
            throw ValidationException("p90Factor must be between 0 and the factor")
        }
        return repository.upsertLoadFactor(code, monthOfYear, command.factor, command.p90Factor)
    }

    private fun validate(expectedMwh: Long, p90Mwh: Long) {
        if (expectedMwh < 0 || p90Mwh < 0) throw ValidationException("Production must not be negative")
        if (p90Mwh > expectedMwh) throw ValidationException("The P90 figure ($p90Mwh MWh) must not exceed the expected figure ($expectedMwh MWh)")
    }

    private class Row(val gsrn: String, val month: YearMonth, val expectedMwh: Long, val p90Mwh: Long)

    private fun parseRow(fields: List<String>): Row {
        if (fields.size != CSV_COLUMNS) throw ValidationException("expected $CSV_COLUMNS fields (gsrn,month,expected_mwh,p90_mwh) but found ${fields.size}")
        val gsrn = fields[0].trim()
        if (gsrn.isEmpty()) throw ValidationException("gsrn is missing")
        val month = try {
            YearMonth.parse(fields[1].trim())
        } catch (_: DateTimeParseException) {
            throw ValidationException("month '${fields[1].trim()}' is not a month like 2026-11")
        }
        val expected = fields[2].trim().toLongOrNull() ?: throw ValidationException("expected_mwh '${fields[2].trim()}' is not a whole number")
        val p90 = fields[3].trim().toLongOrNull() ?: throw ValidationException("p90_mwh '${fields[3].trim()}' is not a whole number")
        return Row(gsrn, month, expected, p90)
    }

    companion object {
        private const val HOURS_PER_DAY = 24
        private const val MONTHS_PER_YEAR = 12
        private const val MAX_RANGE_MONTHS = 240L
        private const val CSV_COLUMNS = 4
        private const val HEADER_FIRST_FIELD = "gsrn"
        private val KW_PER_MW = BigDecimal(1000)
    }
}
