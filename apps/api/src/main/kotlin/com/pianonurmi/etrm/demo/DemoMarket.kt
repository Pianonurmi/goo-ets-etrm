package com.pianonurmi.etrm.demo

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/**
 * Market data with history: instruments, two EUA December futures, GoO vintages for four buckets (and two generic
 * ones, [genericCurves]), a GoO price index,
 * and seven months of daily prices and FX rates, loaded the way a desk would, as file imports. Prices are random
 * walks from a fixed seed, so every seed run produces the same history relative to its date.
 */
class DemoMarket(private val api: DemoClient, private val calendar: DemoCalendar) {
    private val year = calendar.deliveryYear
    val futures = listOf(year, year + 1).map { DemoFuture(it) }

    fun instruments() {
        ensureInstrument("EUA_SPOT", """{"code":"EUA_SPOT","kind":"EUA_SPOT","description":"EUA spot","allowanceProductCode":"EUA","currency":"EUR"}""")
        val contracts = api.getList(DemoUser.MIDDLE, "/api/v1/products/futures-contracts").map { it["code"] }
        futures.filter { it.code !in contracts }.forEach { future ->
            api.post(
                DemoUser.MIDDLE,
                "/api/v1/products/futures-contracts",
                """{"code":"${future.code}","allowanceProductCode":"EUA","venueMic":"XEEE","deliveryMonth":"${future.deliveryMonth}",
                   "deliveryDate":"${future.deliveryDate}","lastTradingDay":"${future.lastTradingDay}","contractSize":1000,"currency":"EUR"}""",
            )
        }
        futures.forEach { future ->
            ensureInstrument(
                future.instrument,
                """{"code":"${future.instrument}","kind":"EUA_FUTURE","description":"EUA Dec-${future.year % 100} future (EEX)",
                   "futuresContractCode":"${future.code}","allowanceProductCode":"EUA","currency":"EUR"}""",
            )
        }
        BUCKETS.forEach { bucket ->
            vintages().forEach { vintage ->
                ensureInstrument(
                    vintageCode(bucket.code, vintage),
                    """{"code":"${vintageCode(bucket.code, vintage)}","kind":"GOO_VINTAGE","description":"${bucket.label} $vintage",
                       "bucketCode":"${bucket.code}","productionYear":$vintage,"currency":"EUR"}""",
                )
            }
        }
        if (api.getList(DemoUser.RISK, "/api/v1/market-data/indices").none { it["code"] == INDEX }) {
            api.post(
                DemoUser.RISK,
                "/api/v1/market-data/indices",
                """{"code":"$INDEX","description":"Nordic hydro GoO, monthly average of broker mids","instrumentCode":"${vintageCode("NORDIC_HYDRO", year)}",
                   "quoteType":"MID","fixingRule":"MONTH_AVERAGE"}""",
            )
        }
        api.put(
            DemoUser.RISK,
            "/api/v1/market-data/import-formats",
            """{"code":"DEMO_EUA_CLOSE_CSV","kind":"QUOTES_LONG_CSV","description":"Broker EUA closing prices: date, instrument, close","source":"BROKER",
               "columnMapping":{"date":"Date","instrument":"Instrument","value":"Close"},"constants":{"quoteType":"CLOSE","currency":"EUR"}}""",
        )
        val curves = api.getList(DemoUser.RISK, "/api/v1/market-data/curves").map { it["code"] }
        if (EUA_CURVE !in curves) {
            api.post(
                DemoUser.RISK,
                "/api/v1/market-data/curves",
                """{"code":"$EUA_CURVE","kind":"EUA_FORWARD","description":"EUA forward","allowanceProductCode":"EUA","currency":"EUR"}""",
            )
        }
        BUCKETS.filter { it.curve !in curves }.forEach { bucket ->
            api.post(
                DemoUser.RISK,
                "/api/v1/market-data/curves",
                """{"code":"${bucket.curve}","kind":"GOO_VINTAGE","description":"${bucket.label} vintages","bucketCode":"${bucket.code}","currency":"EUR"}""",
            )
        }
    }

    /**
     * Daily FX for sixteen months (lots issued a year ago are costed in their entity's currency) and seven months of
     * prices, up to the last close, imported as four files. One GoO price jumps for a day and is flagged suspect.
     */
    fun history() {
        val random = Random(SEED)
        var sek = BigDecimal("11.32")
        var nok = BigDecimal("11.58")
        var usd = BigDecimal("1.0840")
        val fx = StringBuilder("Date,USD,SEK,NOK\n")
        businessDays(calendar.fxHistoryStart, calendar.lastClose).forEach { day ->
            sek = walk(sek, random, BigDecimal("0.003"), 4)
            nok = walk(nok, random, BigDecimal("0.003"), 4)
            usd = walk(usd, random, BigDecimal("0.003"), 4)
            fx.append("$day,$usd,$sek,$nok\n")
        }
        val days = businessDays(calendar.historyStart, calendar.lastClose)
        var spot = BigDecimal("66.40")
        val goo = BUCKETS.associate { it.code to it.start }.toMutableMap()
        val eua = StringBuilder("Date,Instrument,Close\n")
        val settlements = StringBuilder("Date,Contract,Settlement,Currency\n")
        val broker = StringBuilder("Date,Product,Mid\n")
        days.forEachIndexed { index, day ->
            spot = walk(spot, random, BigDecimal("0.018"), 2)
            eua.append("$day,EUA_SPOT,$spot\n")
            futures.forEach { future ->
                val carry = BigDecimal(future.year - day.year + 1).multiply(BigDecimal("1.35"))
                settlements.append("$day,${future.instrument},${(spot + carry).setScale(2, RoundingMode.HALF_UP)},EUR\n")
            }
            BUCKETS.forEach { bucket ->
                val price = walk(goo.getValue(bucket.code), random, BigDecimal("0.012"), 3).max(BigDecimal("0.05"))
                goo[bucket.code] = price
                vintages().forEach { vintage ->
                    // A fat-finger print a few weeks ago: 40 % off for one day, flagged suspect for review.
                    val printed = if (bucket.code == "EU_SOLAR" && vintage == year && index == days.size - SUSPECT_DAYS_AGO) {
                        price.multiply(BigDecimal("1.40")).setScale(3, RoundingMode.HALF_UP)
                    } else {
                        price - VINTAGE_DISCOUNT.multiply(BigDecimal(year - vintage))
                    }
                    broker.append("$day,${vintageCode(bucket.code, vintage)},$printed\n")
                }
            }
        }
        import("ECB_EUROFXREF_HIST", "ecb-history.csv", fx.toString())
        import("DEMO_EUA_CLOSE_CSV", "broker-eua-close.csv", eua.toString())
        import("GENERIC_SETTLEMENT_CSV", "eex-settlements.csv", settlements.toString())
        import("GENERIC_BROKER_GOO_CSV", "broker-goo-mids.csv", broker.toString())
    }

    /** Builds, submits and (as the second analyst) signs off every curve on each date. */
    fun officialCurves(dates: List<LocalDate>) {
        dates.forEach { date ->
            curveCodes().forEach { code ->
                val snapshot = api.post(DemoUser.RISK, "/api/v1/market-data/curves/$code/snapshots", """{"asOf":"$date"}""").id()
                api.post(DemoUser.RISK, "/api/v1/market-data/curves/snapshots/$snapshot/submit")
            }
        }
        api.approveAll(DemoUser.RISK2, api.openTasks(DemoUser.RISK2, "CURVE_SIGN_OFF"))
    }

    /** Today's EUA curve waits in the second analyst's inbox. */
    fun pendingCurve() {
        val pending = api.post(DemoUser.RISK, "/api/v1/market-data/curves/$EUA_CURVE/snapshots", """{"asOf":"${calendar.lastClose}"}""").id()
        api.post(DemoUser.RISK, "/api/v1/market-data/curves/snapshots/$pending/submit")
    }

    /**
     * Generic products priced below their children (ADR 0035): EU_RENEWABLE and ANY_GOO. A generic sale delivered with a
     * Nordic hydro certificate then gives away the difference, which allocation reports. Their history comes from a
     * separate random stream, so the specific buckets' prices are the same as before they existed.
     */
    fun genericCurves() {
        GENERIC.forEach { bucket ->
            vintages().forEach { vintage ->
                ensureInstrument(
                    vintageCode(bucket.code, vintage),
                    """{"code":"${vintageCode(bucket.code, vintage)}","kind":"GOO_VINTAGE","description":"${bucket.label} $vintage",
                       "bucketCode":"${bucket.code}","productionYear":$vintage,"currency":"EUR"}""",
                )
            }
        }
        val curves = api.getList(DemoUser.RISK, "/api/v1/market-data/curves").map { it["code"] }
        GENERIC.filter { it.curve !in curves }.forEach { bucket ->
            api.post(
                DemoUser.RISK,
                "/api/v1/market-data/curves",
                """{"code":"${bucket.curve}","kind":"GOO_VINTAGE","description":"${bucket.label} vintages","bucketCode":"${bucket.code}","currency":"EUR"}""",
            )
        }
        val random = Random(SEED + 1)
        val price = GENERIC.associate { it.code to it.start }.toMutableMap()
        val broker = StringBuilder("Date,Product,Mid\n")
        businessDays(calendar.historyStart, calendar.lastClose).forEach { day ->
            GENERIC.forEach { bucket ->
                val mid = walk(price.getValue(bucket.code), random, BigDecimal("0.008"), 3).max(BigDecimal("0.05"))
                price[bucket.code] = mid
                vintages().forEach { vintage ->
                    val printed = mid - VINTAGE_DISCOUNT.multiply(BigDecimal(year - vintage))
                    broker.append("$day,${vintageCode(bucket.code, vintage)},$printed\n")
                }
            }
        }
        import("GENERIC_BROKER_GOO_CSV", "broker-goo-generic-mids.csv", broker.toString())
    }

    fun curveCodes(): List<String> = listOf(EUA_CURVE) + (BUCKETS + GENERIC).map { it.curve }

    private fun vintages() = listOf(year - 1, year)

    private fun import(format: String, fileName: String, content: String) {
        val result = api.postFile(DemoUser.RISK, "/api/v1/market-data/imports?format=$format", fileName, content)
        val rejected = (result["rejected"] as? Number)?.toInt() ?: 0
        if (rejected > 0) throw DemoRequestFailed("Import $fileName rejected $rejected lines: ${result["errors"]}")
    }

    private fun ensureInstrument(code: String, json: String) {
        val known = api.getList(DemoUser.RISK, "/api/v1/market-data/instruments").any { it["code"] == code }
        if (!known) api.post(DemoUser.RISK, "/api/v1/market-data/instruments", json)
    }

    private fun walk(value: BigDecimal, random: Random, volatility: BigDecimal, scale: Int): BigDecimal {
        val shock = BigDecimal(random.nextInt(-1000, 1001)).divide(BigDecimal(1000))
        return value.multiply(BigDecimal.ONE + volatility.multiply(shock)).setScale(scale, RoundingMode.HALF_UP)
    }

    data class Bucket(val code: String, val label: String, val start: BigDecimal) {
        val curve get() = "GOO_$code"
    }

    companion object {
        const val SEED = 42
        const val INDEX = "GOO_NORDIC_HYDRO_IDX"
        const val EUA_CURVE = "EUA_FWD"
        private const val SUSPECT_DAYS_AGO = 15
        private val VINTAGE_DISCOUNT = BigDecimal("0.040")

        val BUCKETS = listOf(
            Bucket("NORDIC_HYDRO", "Nordic hydro", BigDecimal("0.320")),
            Bucket("NORDIC_WIND", "Nordic wind", BigDecimal("0.360")),
            Bucket("EU_WIND", "European wind", BigDecimal("0.450")),
            Bucket("EU_SOLAR", "European solar", BigDecimal("0.410")),
        )

        /** Generic buckets, below the cheapest specific one so a generic sale never pays more than its children. */
        val GENERIC = listOf(
            Bucket("EU_RENEWABLE", "European renewable", BigDecimal("0.240")),
            Bucket("ANY_GOO", "Any guarantee of origin", BigDecimal("0.180")),
        )

        fun vintageCode(bucket: String, year: Int) = "GOO_${bucket}_$year"

        fun businessDays(from: LocalDate, to: LocalDate): List<LocalDate> =
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
                .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.toList()
    }
}

/** An EUA December future on EEX: lots of 1 000 t, delivered mid-December, last traded three days before. */
data class DemoFuture(val year: Int) {
    val code = "EUAZ${year % 100}"
    val instrument = "EUA_FUT_Z${year % 100}"
    val deliveryMonth: YearMonth = YearMonth.of(year, 12)
    val deliveryDate: LocalDate = LocalDate.of(year, 12, 14).let { DemoCalendar.weekdayOnOrBefore(it) }
    val lastTradingDay: LocalDate = DemoCalendar.weekdayOnOrBefore(deliveryDate.minusDays(3))
}
