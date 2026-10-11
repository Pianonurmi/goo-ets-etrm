package com.pianonurmi.etrm.demo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Dates of the demo, relative to today so the data never looks stale. */
data class DemoCalendar(val today: LocalDate) {
    val previousMonth: YearMonth = YearMonth.from(today).minusMonths(1)

    /** Last weekday of the previous month: the official valuation the period end needs. */
    val monthEnd: LocalDate = weekdayOnOrBefore(previousMonth.atEndOfMonth())
    val dayBeforeMonthEnd: LocalDate = weekdayOnOrBefore(monthEnd.minusDays(1))

    /** Most recent weekday before today, for current prices. */
    val lastClose: LocalDate = weekdayOnOrBefore(today.minusDays(1))

    /** Trades are dated at the start of the previous month, so they are valued at its end. */
    val tradeDate: LocalDate = weekdayOnOrAfter(previousMonth.atDay(1))

    /** GoO deliveries settle in mid-December of the current year (next year once December has begun). */
    val deliveryYear: Int = if (today.monthValue >= 12) today.year + 1 else today.year
    val gooDeliveryDate: LocalDate = LocalDate.of(deliveryYear, 12, 15)
    val gooWindowStart: LocalDate = today.plusDays(1)
    val gooWindowEnd: LocalDate = LocalDate.of(deliveryYear + 1, 1, 1)
    val euaDeliveryDate: LocalDate = LocalDate.of(deliveryYear, 12, 14)

    companion object {
        fun weekdayOnOrBefore(date: LocalDate): LocalDate = generateSequence(date) { it.minusDays(1) }.first { it.isWeekday() }

        fun weekdayOnOrAfter(date: LocalDate): LocalDate = generateSequence(date) { it.plusDays(1) }.first { it.isWeekday() }

        private fun LocalDate.isWeekday() = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
    }
}

/** What the reference data step created, for the later steps. */
data class DemoEntity(
    val id: String,
    val code: String,
    val tradingBookId: String,
    val ownUseBookId: String,
    val gooAccountId: String,
    val deviceGsrns: List<String>,
)

/**
 * The demo world: two legal entities (Finland and Sweden) with books, fictitious counterparties, simulator
 * accounts and own devices; certificates across vintages; trades in several states; signed-off curves, an official
 * valuation and last month's period end, risk limits and an ETS installation, plus open approvals in the personas' inboxes.
 */
class DemoScenario(private val api: DemoClient, private val calendar: DemoCalendar) {
    fun run() {
        val counterparties = counterparties()
        val finland = entity("DEMO-FI", "Demo Energia Oy", "FI", "529900DEMOFI00000137", gsrnSeed = 1)
        val sweden = entity("DEMO-SE", "Demo Kraft AB", "SE", "529900DEMOSE00000115", gsrnSeed = 4)
        marketData()
        DemoInventory(api, calendar).receive(finland, issuanceSeed = 1)
        DemoInventory(api, calendar).receive(sweden, issuanceSeed = 2)
        DemoTrades(api, calendar).book(finland, sweden, counterparties)
        accounting(finland)
        DemoCompliance(api, calendar).seed(finland, counterparties)
        val (nordvind, rheinstrom, aurora) = counterparties
        DemoOperations(api, calendar).seed(finland, DemoCounterparties(nordvind, rheinstrom, aurora))
    }

    /** Nordvind has a netting agreement with us, so its invoices can be settled net. */
    private fun counterparties(): List<String> = listOf(
        Triple("CP-NORDVIND", "Nordvind Energi AS (fictitious)", "NO") to true,
        Triple("CP-RHEINSTROM", "Rheinstrom Handel GmbH (fictitious)", "DE") to false,
        Triple("CP-AURORA", "Aurora Power Oy (fictitious)", "FI") to false,
    ).map { (party, netting) ->
        val (code, name, country) = party
        api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/counterparties",
            """{"code":"$code","name":"$name","country":"$country","nettingAgreed":$netting}""",
        ).id()
    }

    private fun entity(code: String, name: String, country: String, lei: String, gsrnSeed: Int): DemoEntity {
        val timeZone = if (country == "SE") "Europe/Stockholm" else "Europe/Helsinki"
        val id = api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/legal-entities",
            """{"code":"$code","name":"$name","country":"$country","timeZone":"$timeZone","validFrom":"${calendar.today.year - 1}-01-01",
               "lei":"$lei","emirClassification":"NFC_PLUS"}""",
        ).id()

        fun book(suffix: String, label: String, type: String) = api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/books",
            """{"legalEntityId":"$id","code":"$code-$suffix","name":"$label","bookType":"$type"}""",
        ).id()
        val trading = book("TRD", "Trading", "TRADING")
        val ownUse = book("OWN", "Own use", "OWN_USE")
        val account = api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/registry-accounts",
            """{"registryCode":"SIMULATOR","accountNumber":"SIM-$code","name":"$name GoO","accountType":"HOLDING",
               "holderType":"LEGAL_ENTITY","holderId":"$id","defaultBookId":"$trading"}""",
        ).id()
        val devices = DEVICES.mapIndexed { index, device ->
            val gsrn = gsrn(gsrnSeed + index)
            api.post(
                DemoUser.ADMIN,
                "/api/v1/reference/production-devices",
                """{"gsrn":"$gsrn","name":"${device.name} ($code)","country":"$country","capacityKw":${device.capacityKw},
                   "commissioningDate":"${device.commissioned}","energySourceCode":"${device.source}","technologyCode":"${device.technology}",
                   "registryCode":"SIMULATOR","ownerType":"LEGAL_ENTITY","ownerId":"$id","ownDevice":true}""",
            )
            gsrn
        }
        return DemoEntity(id, code, trading, ownUse, account, devices)
    }

    /** EUR prices; curves are built, submitted by one risk analyst and signed off by another. */
    private fun marketData() {
        val year = calendar.deliveryYear
        ensureInstrument("EUA_SPOT", """{"code":"EUA_SPOT","kind":"EUA_SPOT","description":"EUA spot","allowanceProductCode":"EUA","currency":"EUR"}""")
        listOf(year - 1, year).forEach { vintage ->
            ensureInstrument(
                "GOO_NORDIC_HYDRO_$vintage",
                """{"code":"GOO_NORDIC_HYDRO_$vintage","kind":"GOO_VINTAGE","description":"Nordic hydro $vintage","bucketCode":"NORDIC_HYDRO",
                   "productionYear":$vintage,"currency":"EUR"}""",
            )
        }
        val curves = api.getList(DemoUser.RISK, "/api/v1/market-data/curves")
        val gooCurve = curves.firstOrNull { it["bucketCode"] == "NORDIC_HYDRO" && it["active"] == true }?.get("code")?.toString()
            ?: api.post(
                DemoUser.RISK,
                "/api/v1/market-data/curves",
                """{"code":"GOO_NORDIC_HYDRO","kind":"GOO_VINTAGE","description":"Nordic hydro vintages","bucketCode":"NORDIC_HYDRO","currency":"EUR"}""",
            )["code"].toString()
        val euaCurve = curves.firstOrNull { it["allowanceProductCode"] == "EUA" && it["active"] == true }?.get("code")?.toString()
            ?: api.post(
                DemoUser.RISK,
                "/api/v1/market-data/curves",
                """{"code":"EUA_FWD","kind":"EUA_FORWARD","description":"EUA forward","allowanceProductCode":"EUA","currency":"EUR"}""",
            )["code"].toString()

        val prices = mapOf(
            calendar.dayBeforeMonthEnd to Prices(eua = "70.00", hydro = "0.30", rate = "11.40"),
            calendar.monthEnd to Prices(eua = "72.00", hydro = "0.31", rate = "11.45"),
            calendar.lastClose to Prices(eua = "74.50", hydro = "0.33", rate = "11.50"),
        )
        prices.forEach { (date, price) ->
            api.put(DemoUser.RISK, "/api/v1/market-data/fx-rates", """{"date":"$date","base":"EUR","quote":"SEK","rate":${price.rate}}""")
            quote("EUA_SPOT", date, "CLOSE", price.eua)
            listOf(year - 1, year).forEach { quote("GOO_NORDIC_HYDRO_$it", date, "MID", price.hydro) }
        }
        listOf(calendar.dayBeforeMonthEnd, calendar.monthEnd).forEach { date ->
            listOf(euaCurve, gooCurve).forEach { officialCurve(it, date) }
        }
        // Today's EUA curve waits in the second analyst's inbox.
        val pending = api.post(DemoUser.RISK, "/api/v1/market-data/curves/$euaCurve/snapshots", """{"asOf":"${calendar.lastClose}"}""").id()
        api.post(DemoUser.RISK, "/api/v1/market-data/curves/snapshots/$pending/submit")
    }

    private fun ensureInstrument(code: String, json: String) {
        val known = api.getList(DemoUser.RISK, "/api/v1/market-data/instruments").any { it["code"] == code }
        if (!known) api.post(DemoUser.RISK, "/api/v1/market-data/instruments", json)
    }

    private fun quote(instrument: String, date: LocalDate, type: String, value: String) = api.put(
        DemoUser.RISK,
        "/api/v1/market-data/quotes",
        """{"instrumentCode":"$instrument","quoteDate":"$date","quoteType":"$type","value":$value,"currency":"EUR"}""",
    )

    private fun officialCurve(code: String, date: LocalDate) {
        val snapshot = api.post(DemoUser.RISK, "/api/v1/market-data/curves/$code/snapshots", """{"asOf":"$date"}""").id()
        api.post(DemoUser.RISK, "/api/v1/market-data/curves/snapshots/$snapshot/submit")
        api.approveTask(DemoUser.RISK2, "CURVE_SIGN_OFF", snapshot)
    }

    /** Official valuations at the previous month's last two weekdays, then that month's period end. */
    private fun accounting(entity: DemoEntity) {
        listOf(calendar.dayBeforeMonthEnd, calendar.monthEnd).forEach { date ->
            val run = api.post(
                DemoUser.RISK,
                "/api/v1/valuation/runs",
                """{"legalEntityId":"${entity.id}","valuationDate":"$date","kind":"EOD"}""",
            ).id()
            api.post(DemoUser.RISK, "/api/v1/valuation/runs/$run/submit")
            api.approveTask(DemoUser.RISK2, "VALUATION_SIGN_OFF", run)
        }
        api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/period-end/runs", """{"legalEntityId":"${entity.id}","period":"${calendar.previousMonth}"}""")
    }

    private data class Prices(val eua: String, val hydro: String, val rate: String)

    private data class Device(val name: String, val source: String, val technology: String, val capacityKw: Int, val commissioned: String)

    companion object {
        private val DEVICES = listOf(
            Device("Koski hydro", "HYDRO_RESERVOIR", "HYDRO_TURBINE_STORAGE", 25_000, "2012-05-01"),
            Device("Virta run-of-river", "HYDRO_RUN_OF_RIVER", "HYDRO_TURBINE_ROR", 8_000, "2019-03-15"),
            Device("Tuuli wind park", "WIND_ONSHORE", "WIND_TURBINE_ONSHORE", 42_000, "2021-09-01"),
        )

        /** A valid 18-digit GS1 GSRN in a range reserved for demo devices (mod-10 check digit). */
        fun gsrn(sequence: Int): String {
            val body = "64390" + (900_000_000 + sequence).toString().padStart(12, '0')
            val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
            return body + ((10 - sum % 10) % 10)
        }
    }
}
