package com.pianonurmi.etrm.marketdata.adapter.rest

import com.pianonurmi.etrm.marketdata.adapter.http.EcbPullJob
import com.pianonurmi.etrm.marketdata.api.CreateInstrument
import com.pianonurmi.etrm.marketdata.api.CreatePriceIndex
import com.pianonurmi.etrm.marketdata.api.FixingView
import com.pianonurmi.etrm.marketdata.api.FxRateView
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.ImportFormatView
import com.pianonurmi.etrm.marketdata.api.ImportResult
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.marketdata.api.InstrumentKind
import com.pianonurmi.etrm.marketdata.api.InstrumentView
import com.pianonurmi.etrm.marketdata.api.MarketDataCommand
import com.pianonurmi.etrm.marketdata.api.MarketDataQuery
import com.pianonurmi.etrm.marketdata.api.PriceIndexView
import com.pianonurmi.etrm.marketdata.api.QuoteView
import com.pianonurmi.etrm.marketdata.api.RecordFxRate
import com.pianonurmi.etrm.marketdata.api.RecordQuote
import com.pianonurmi.etrm.marketdata.api.UpsertImportFormat
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.AllowedRoles
import com.pianonurmi.etrm.platform.security.Role
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/market-data")
@Tag(name = "Market data", description = "Instruments, prices, FX rates, imports and price indices")
class MarketDataController(
    private val query: MarketDataQuery,
    private val command: MarketDataCommand,
    private val fx: FxRates,
    private val fixings: IndexFixings,
    private val ecb: EcbPullJob,
    private val actorProvider: ActorProvider,
) {
    @GetMapping("/instruments")
    fun instruments(
        @RequestParam(required = false) kind: InstrumentKind?,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ): List<InstrumentView> = query.instruments(kind, activeOnly)

    @PostMapping("/instruments")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun createInstrument(
        @RequestBody body: CreateInstrument,
    ): InstrumentView = command.createInstrument(body)

    @PutMapping("/aliases")
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun addAlias(
        @RequestBody body: AliasRequest,
    ): AliasRequest = body.also { command.addAlias(it.source, it.externalCode, it.instrumentCode) }

    @GetMapping("/quotes")
    fun quotes(
        @RequestParam instrument: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) knownAt: Instant?,
    ): List<QuoteView> = query.quotes(instrument, from, to, knownAt)

    @PutMapping("/quotes")
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun recordQuote(
        @RequestBody body: RecordQuote,
    ): QuoteView = command.recordQuote(body)

    @GetMapping("/quotes/suspect")
    fun suspect(): List<QuoteView> = query.suspectQuotes()

    @PostMapping("/quotes/{id}/acknowledge")
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun acknowledge(
        @PathVariable id: UUID,
        @RequestBody body: NoteRequest,
    ): QuoteView = command.acknowledgeQuote(id, body.note)

    @GetMapping("/fx-rates")
    fun fxRates(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(required = false) base: String?,
    ): List<RecordFxRate> = query.fxRates(date, base)

    @GetMapping("/fx-rates/convert")
    fun rate(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam from: String,
        @RequestParam to: String,
    ): FxRateView = fx.rate(date, from, to) ?: throw NotFoundException("FxRate", "$from/$to on $date")

    @PutMapping("/fx-rates")
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun recordFxRate(
        @RequestBody body: RecordFxRate,
    ): RecordFxRate = body.also { command.recordFxRate(it) }

    @GetMapping("/import-formats")
    fun importFormats(): List<ImportFormatView> = query.importFormats()

    @PutMapping("/import-formats")
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun upsertImportFormat(
        @RequestBody body: UpsertImportFormat,
    ): ImportFormatView = command.upsertImportFormat(body)

    @PostMapping("/imports", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun importFile(
        @RequestParam format: String,
        @RequestPart file: MultipartFile,
    ): ImportResult = command.importFile(format, file.originalFilename ?: "upload", file.bytes)

    @PostMapping("/ecb/pull")
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun pullEcb(): ImportResult {
        actorProvider.current().requireRole(Role.ADMIN)
        return ecb.pull()
    }

    @GetMapping("/indices")
    fun indices(): List<PriceIndexView> = query.indices()

    @PostMapping("/indices")
    @ResponseStatus(HttpStatus.CREATED)
    @AllowedRoles(Role.MIDDLE_OFFICE, Role.RISK)
    fun createIndex(
        @RequestBody body: CreatePriceIndex,
    ): PriceIndexView = command.createIndex(body)

    @GetMapping("/indices/{code}/fixings/{date}")
    fun fixing(
        @PathVariable code: String,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
    ): FixingView = fixings.fixing(code, date) ?: throw NotFoundException("Fixing", "$code on $date")

    data class AliasRequest(val source: String, val externalCode: String, val instrumentCode: String)

    data class NoteRequest(val note: String)
}
