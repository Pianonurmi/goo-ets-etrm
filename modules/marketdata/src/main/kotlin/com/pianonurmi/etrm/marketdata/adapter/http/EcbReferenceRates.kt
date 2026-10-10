package com.pianonurmi.etrm.marketdata.adapter.http

import com.pianonurmi.etrm.marketdata.api.ImportResult
import com.pianonurmi.etrm.marketdata.api.MarketDataCommand
import com.pianonurmi.etrm.marketdata.application.MarketDataService
import com.pianonurmi.etrm.marketdata.application.MarketDataSettings
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.LocalDate

/**
 * Pulls the ECB euro reference rates (published around 16:00 CET on TARGET working days) and imports them through
 * the `ECB_DAILY_XML` format, so a pull and a file upload of the same document are the same import. A GET is safe to
 * retry; transport errors and 5xx responses are retried with backoff.
 */
@Component
class EcbReferenceRateClient(private val settings: MarketDataSettings) {
    private val log = LoggerFactory.getLogger(EcbReferenceRateClient::class.java)

    fun fetchDaily(): ByteArray {
        val ecb = settings.ecb
        val http = HttpClient.newBuilder().connectTimeout(ecb.connectTimeout).followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI.create(ecb.baseUrl.trimEnd('/') + ecb.dailyPath)).timeout(ecb.readTimeout).GET().build()
        var last: Exception? = null
        repeat(ecb.maxAttempts.coerceAtLeast(1)) { attempt ->
            try {
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                when (response.statusCode()) {
                    in 200..299 -> return response.body()
                    in 500..599 -> last = IOException("ECB answered ${response.statusCode()}")
                    else -> throw IOException("ECB answered ${response.statusCode()}; not retried")
                }
            } catch (e: HttpTimeoutException) {
                last = e
            } catch (e: java.net.ConnectException) {
                last = e
            }
            log.warn("ECB reference rates attempt {} failed: {}", attempt + 1, last.toString())
            if (attempt + 1 < ecb.maxAttempts) Thread.sleep(ecb.retryBackoff.toMillis() * (attempt + 1))
        }
        throw IOException("ECB reference rates unavailable after ${ecb.maxAttempts} attempts", last)
    }
}

/** Imports the ECB daily rates on working days when enabled; the FX age gauge shows when they stop arriving. */
@Component
class EcbPullJob(
    private val client: EcbReferenceRateClient,
    private val marketData: MarketDataCommand,
    private val service: MarketDataService,
    private val settings: MarketDataSettings,
    private val clock: Clock,
    meters: ObjectProvider<MeterRegistry>,
) {
    private val log = LoggerFactory.getLogger(EcbPullJob::class.java)

    init {
        meters.ifAvailable { registry ->
            Gauge.builder("etrm.marketdata.fx.age.days") { service.fxAgeDays()?.toDouble() ?: Double.NaN }
                .description("Days since the newest FX rate")
                .register(registry)
        }
    }

    @Scheduled(cron = "\${etrm.marketdata.ecb.pull-cron:0 30 16,17 * * MON-FRI}", zone = "Europe/Berlin")
    @SchedulerLock(name = "marketdata-ecb-pull", lockAtMostFor = "PT10M")
    fun scheduled() {
        if (!settings.ecb.enabled) return
        runCatching { pull() }.onFailure { log.warn("ECB reference rates not imported on {}: {}", LocalDate.now(clock), it.message) }
    }

    /** Fetches and imports the current daily file; the same file twice is one import. */
    fun pull(): ImportResult = marketData.importFile("ECB_DAILY_XML", "eurofxref-daily-${LocalDate.now(clock)}.xml", client.fetchDaily())
}
