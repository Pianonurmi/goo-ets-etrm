package com.pianonurmi.etrm.marketdata.adapter.rest

import com.pianonurmi.etrm.marketdata.api.CreateCurve
import com.pianonurmi.etrm.marketdata.api.CurveCommand
import com.pianonurmi.etrm.marketdata.api.CurvePrice
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.CurveSnapshotView
import com.pianonurmi.etrm.marketdata.api.CurveView
import com.pianonurmi.etrm.marketdata.api.DiscountFactor
import com.pianonurmi.etrm.marketdata.api.DiscountFactors
import com.pianonurmi.etrm.marketdata.api.Interpolation
import com.pianonurmi.etrm.platform.errors.NotFoundException
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/market-data/curves")
@Tag(name = "Curves", description = "Forward, vintage and discount curves: build, override, sign-off (four eyes), official prices and discount factors")
class CurveController(private val query: CurveQuery, private val command: CurveCommand, private val discounting: DiscountFactors) {
    @GetMapping
    fun curves(
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<CurveView> = query.curves(activeOnly)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody body: CreateCurve,
    ): CurveView = command.createCurve(body)

    /** How later snapshots of a forward curve are read beyond the last future (MIDDLE_OFFICE or RISK). */
    @PostMapping("/{code}/interpolation")
    fun setInterpolation(
        @PathVariable code: String,
        @RequestBody body: InterpolationRequest,
    ): CurveView = command.setInterpolation(code, body.interpolation)

    @PostMapping("/{code}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    fun build(
        @PathVariable code: String,
        @RequestBody body: BuildRequest,
    ): CurveSnapshotView = command.build(code, body.asOf, body.note)

    @GetMapping("/{code}/snapshots")
    fun snapshots(
        @PathVariable code: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): List<CurveSnapshotView> = query.snapshots(code, asOf)

    @GetMapping("/{code}/official")
    fun official(
        @PathVariable code: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
        @RequestParam(required = false) knownAt: Instant?,
    ): CurveSnapshotView = query.official(code, asOf, knownAt) ?: throw NotFoundException("Official curve", "$code on $asOf")

    @GetMapping("/snapshots/{id}")
    fun snapshot(
        @PathVariable id: UUID,
    ): CurveSnapshotView = query.snapshot(id) ?: throw NotFoundException("CurveSnapshot", id)

    @PostMapping("/snapshots/{id}/override")
    @ResponseStatus(HttpStatus.CREATED)
    fun override(
        @PathVariable id: UUID,
        @RequestBody body: OverrideRequest,
    ): CurveSnapshotView = command.override(id, body.pillar, body.price, body.note)

    @PostMapping("/snapshots/{id}/submit")
    fun submit(
        @PathVariable id: UUID,
    ): CurveSnapshotView = command.submit(id)

    @GetMapping("/forward-price")
    fun forwardPrice(
        @RequestParam product: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) delivery: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): CurvePrice = query.forwardPrice(product, delivery, asOf) ?: throw NotFoundException("Forward price", "$product for $delivery on $asOf")

    @GetMapping("/volatility")
    fun volatility(
        @RequestParam product: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) expiry: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): CurvePrice = query.volatility(product, expiry, asOf) ?: throw NotFoundException("Volatility", "$product for $expiry on $asOf")

    @GetMapping("/vintage-price")
    fun vintagePrice(
        @RequestParam bucket: String,
        @RequestParam year: Int,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): CurvePrice = query.vintagePrice(bucket, year, asOf) ?: throw NotFoundException("Vintage price", "$bucket $year on $asOf")

    /** The factor for a payment in [currency] on [payDate], seen from [asOf]; exactly 1 without an official discount curve. */
    @GetMapping("/discount-factor")
    fun discountFactor(
        @RequestParam currency: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) payDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) asOf: LocalDate,
    ): DiscountFactor = discounting.discountFactor(currency, payDate, asOf)

    data class InterpolationRequest(val interpolation: Interpolation)

    data class BuildRequest(val asOf: LocalDate, val note: String? = null)

    data class OverrideRequest(val pillar: String, val price: BigDecimal, val note: String)
}
