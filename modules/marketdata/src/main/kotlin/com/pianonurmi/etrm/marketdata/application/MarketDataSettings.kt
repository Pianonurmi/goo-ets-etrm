package com.pianonurmi.etrm.marketdata.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal
import java.time.Duration

/**
 * ```yaml
 * etrm.marketdata:
 *   max-move-pct: 25            # a price moving more than this from the previous one is held as suspect
 *   fx-max-staleness-days: 10   # FX rates older than this are not used
 *   ecb:
 *     enabled: false            # pull the ECB reference rates on working days
 *     base-url: https://www.ecb.europa.eu
 * ```
 */
@ConfigurationProperties(prefix = "etrm.marketdata")
data class MarketDataSettings(
    val maxMovePct: BigDecimal = BigDecimal(25),
    val fxMaxStalenessDays: Long = 10,
    val ecb: EcbSettings = EcbSettings(),
)

data class EcbSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "https://www.ecb.europa.eu",
    val dailyPath: String = "/stats/eurofxref/eurofxref-daily.xml",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(20),
    val maxAttempts: Int = 3,
    val retryBackoff: Duration = Duration.ofSeconds(2),
)
