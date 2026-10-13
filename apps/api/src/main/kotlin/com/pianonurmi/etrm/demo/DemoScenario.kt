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

    /** Price history starts seven months back, so every month-end valuation has prices before it. */
    val historyStart: LocalDate = weekdayOnOrAfter(YearMonth.from(today).minusMonths(HISTORY_MONTHS).atDay(1))

    /** FX goes back further: lots issued a year ago are costed in their entity's currency on their issue date. */
    val fxHistoryStart: LocalDate = weekdayOnOrAfter(YearMonth.from(today).minusMonths(FX_HISTORY_MONTHS).atDay(1))

    /** The months the history step books, oldest first: six months ago to two months ago. */
    val historyMonths: List<YearMonth> = (DemoHistory.MONTHS downTo 2).map { YearMonth.from(today).minusMonths(it.toLong()) }

    /** The last five weekdays before today, for the recent official curves and valuations. */
    val recentCloses: List<LocalDate> = generateSequence(lastCloseOf(today)) { weekdayOnOrBefore(it.minusDays(1)) }.take(RECENT_DAYS).toList().reversed()

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
        const val HISTORY_MONTHS = 7L
        const val FX_HISTORY_MONTHS = 16L
        const val RECENT_DAYS = 5

        private fun lastCloseOf(today: LocalDate) = weekdayOnOrBefore(today.minusDays(1))

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
    /** The steps in order; each names what it creates, and the run log shows them while seeding. */
    fun steps(): List<DemoStep> {
        val market = DemoMarket(api, calendar)
        val desk = DemoDesk(api, calendar)
        val history = DemoHistory(api, calendar, market, desk)
        val office = DemoBackOffice(api, calendar, desk)
        return listOf(
            DemoStep("Counterparties") { it.put(PARTIES, counterparties()) },
            DemoStep("Legal entity DEMO-FI") { it.put(FINLAND, entity("DEMO-FI", "Demo Energia Oy", "FI", "529900DEMOFI00000137", gsrnSeed = 1)) },
            DemoStep("Legal entity DEMO-SE") { it.put(SWEDEN, entity("DEMO-SE", "Demo Kraft AB", "SE", "529900DEMOSE00000115", gsrnSeed = 11)) },
            DemoStep("Intercompany counterparties") { it.put(PARTIES, it.parties() + intercompany(it.get(FINLAND), it.get(SWEDEN))) },
            DemoStep("Addresses, VAT numbers and bank accounts") { office.partyDetails(it.parties(), it.get(SWEDEN)) },
            DemoStep("Instruments, futures and curves") { market.instruments() },
            DemoStep("Price and FX history") { market.history() },
            DemoStep("Generic GoO curves") { market.genericCurves() },
            DemoStep("Clearing account") { history.clearingAccount(it.get(FINLAND), it.parties()) },
            DemoStep("Certificates DEMO-FI") { DemoInventory(api, calendar).receive(it.get(FINLAND), issuanceSeed = 1) },
            DemoStep("Certificates DEMO-SE") { DemoInventory(api, calendar).receive(it.get(SWEDEN), issuanceSeed = 2) },
        ) + calendar.historyMonths.map { month ->
            DemoStep("Month $month") { history.month(month, it.get(FINLAND), it.get(SWEDEN), it.parties()) }
        } + listOf(
            DemoStep("Trades") { DemoTrades(api, calendar).book(it.get(FINLAND), it.get(SWEDEN), it.counterparties()) },
            DemoStep("Last month and recent days") { lastMonth(it.get(FINLAND), it.get(SWEDEN), market, desk, history) },
            DemoStep("Risk limits and ETS") { DemoCompliance(api, calendar).seed(it.get(FINLAND), it.counterparties()) },
            DemoStep("Operations") {
                val (nordvind, rheinstrom, aurora) = it.counterparties()
                DemoOperations(api, calendar).seed(it.get(FINLAND), DemoCounterparties(nordvind, rheinstrom, aurora))
            },
            DemoStep("Certificate lifecycle") { office.certificateLifecycle(it.get(FINLAND), it.get(SWEDEN)) },
            DemoStep("Allocation plans") { office.allocation(it.get(FINLAND), it.get(SWEDEN)) },
            DemoStep("Production forecasts and forward purchases") { office.futureSupply(it.get(FINLAND), it.parties()) },
            DemoStep("GoO limits") { DemoCompliance(api, calendar).gooLimits(it.get(FINLAND)) },
            DemoStep("Union Registry transfers") { office.registry(it.get(FINLAND)) },
            DemoStep("Invoices and payments") { office.invoicing(it.get(FINLAND), it.parties()) },
            DemoStep("Netting with Vinden") { office.netting(it.get(FINLAND), it.parties()) },
            DemoStep("Counterparty confirmations") { office.confirmations(it.get(FINLAND), it.parties()) },
            DemoStep("Swedish reconciliation") { office.reconciliation(it.get(SWEDEN)) },
            DemoStep("EU ETS compliance") { office.ets(it.get(FINLAND), it.get(SWEDEN)) },
            DemoStep("Limits, breaches and pending sign-off") { office.risk(it.get(FINLAND), it.get(SWEDEN), it.parties()) },
            DemoStep("Cash flow hedges") { DemoHedges(api, calendar, desk).seed(it.get(FINLAND), it.parties()) },
            DemoStep("Ledger adjustments and pending changes") { office.ledger(it.get(FINLAND)) },
            DemoStep("EMIR reporting") { office.reporting(it.get(FINLAND)) },
        )
    }

    private fun DemoContext.parties(): DemoParties = get<Map<*, *>>(PARTIES).entries.associate { (code, id) -> code.toString() to id.toString() }

    /** The three counterparties the older steps were written for. */
    private fun DemoContext.counterparties(): List<String> = parties().let {
        listOf(it.getValue("CP-NORDVIND"), it.getValue("CP-RHEINSTROM"), it.getValue("CP-AURORA"))
    }

    /** Fictitious trading partners across Europe; Nordvind and Vinden net with us, one broker and one clearing house. */
    private fun counterparties(): Map<String, String> = COUNTERPARTIES.associate { party ->
        party.code to api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/counterparties",
            """{"code":"${party.code}","name":"${party.name}","country":"${party.country}","nettingAgreed":${party.netting},
               "counterpartyType":"${party.type}","paymentTermsDays":${party.paymentDays},"invoiceEmail":"invoices@${party.code.lowercase().removePrefix(
                "cp-",
            )}.example",
               "masterAgreements":[{"type":"EFET","reference":"EFET-${party.code.removePrefix("CP-")}","signedOn":"2023-03-01"}]}""",
        ).id()
    }

    /** Each entity is also a counterparty of the other, so they can trade with each other (intercompany). */
    private fun intercompany(finland: DemoEntity, sweden: DemoEntity): Map<String, String> =
        listOf(Triple("CP-DEMO-SE", "Demo Kraft AB (intercompany)", sweden), Triple("CP-DEMO-FI", "Demo Energia Oy (intercompany)", finland))
            .associate { (code, name, entity) ->
                code to api.post(
                    DemoUser.ADMIN,
                    "/api/v1/reference/counterparties",
                    """{"code":"$code","name":"$name","country":"${entity.code.takeLast(2)}","counterpartyType":"INTERCOMPANY",
                       "linkedLegalEntityId":"${entity.id}"}""",
                ).id()
            }

    /** Last month closes softly with its export not yet acknowledged; the last five days get official curves and valuations. */
    private fun lastMonth(finland: DemoEntity, sweden: DemoEntity, market: DemoMarket, desk: DemoDesk, history: DemoHistory) {
        market.officialCurves(listOf(calendar.dayBeforeMonthEnd, calendar.monthEnd))
        history.margin(finland, calendar.previousMonth.atDay(1), calendar.monthEnd)
        listOf(finland, sweden).forEach { entity ->
            listOf(calendar.dayBeforeMonthEnd, calendar.monthEnd).forEach { desk.officialValuation(entity, it) }
            api.post(DemoUser.MIDDLE, "/api/v1/positions/snapshots?legalEntityId=${entity.id}&asOf=${calendar.previousMonth.atEndOfMonth()}")
            history.close(entity, calendar.previousMonth, hard = false, acknowledge = false)
        }
        val recent = calendar.recentCloses.filter { it.isAfter(calendar.monthEnd) }
        history.margin(finland, calendar.monthEnd.plusDays(1), calendar.lastClose)
        history.clearingStatements(finland, days = STATEMENT_DAYS, disputed = calendar.recentCloses[calendar.recentCloses.size - 2])
        market.officialCurves(recent)
        recent.forEach { desk.officialValuation(finland, it) }
        market.pendingCurve()
    }

    private fun entity(code: String, name: String, country: String, lei: String, gsrnSeed: Int): DemoEntity {
        val timeZone = if (country == "SE") "Europe/Stockholm" else "Europe/Helsinki"
        // The Swedish entity books in SEK, so FX revaluation and translation have something to show.
        val currency = if (country == "SE") "SEK" else "EUR"
        val id = api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/legal-entities",
            """{"code":"$code","name":"$name","country":"$country","timeZone":"$timeZone","validFrom":"${calendar.today.year - 1}-01-01",
               "lei":"$lei","emirClassification":"NFC_PLUS","baseCurrency":"$currency"}""",
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
        val devices = (if (country == "SE") SWEDISH_DEVICES else FINNISH_DEVICES).mapIndexed { index, device ->
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

    private data class DemoParty(
        val code: String,
        val name: String,
        val country: String,
        val netting: Boolean = false,
        val type: String = "EXTERNAL",
        val paymentDays: Int = 14,
    )

    private data class Device(val name: String, val source: String, val technology: String, val capacityKw: Int, val commissioned: String)

    companion object {
        private const val PARTIES = "parties"
        private const val STATEMENT_DAYS = 15

        /** All fictitious; the first three are the ones the e2e journeys and older steps use. */
        private val COUNTERPARTIES = listOf(
            DemoParty("CP-NORDVIND", "Nordvind Energi AS (fictitious)", "NO", netting = true),
            DemoParty("CP-RHEINSTROM", "Rheinstrom Handel GmbH (fictitious)", "DE"),
            DemoParty("CP-AURORA", "Aurora Power Oy (fictitious)", "FI"),
            DemoParty("CP-VINDEN", "Vinden Energi A/S (fictitious)", "DK", netting = true),
            DemoParty("CP-POLDER", "Polder Groen B.V. (fictitious)", "NL", paymentDays = 30),
            DemoParty("CP-SOLEIL", "Soleil Negoce SAS (fictitious)", "FR"),
            DemoParty("CP-IBERSOL", "Ibersol Comercializadora S.L. (fictitious)", "ES", paymentDays = 30),
            DemoParty("CP-NORDBROKER", "Nordic Carbon Brokers AB (fictitious)", "SE", type = "BROKER"),
            DemoParty("CP-CLEARING", "Continental Clearing House AG (fictitious)", "DE", type = "CLEARING_HOUSE"),
        )
        private const val FINLAND = "finland"
        private const val SWEDEN = "sweden"

        private val FINNISH_DEVICES = listOf(
            Device("Koski hydro", "HYDRO_RESERVOIR", "HYDRO_TURBINE_STORAGE", 25_000, "2012-05-01"),
            Device("Virta run-of-river", "HYDRO_RUN_OF_RIVER", "HYDRO_TURBINE_ROR", 8_000, "2019-03-15"),
            Device("Tuuli wind park", "WIND_ONSHORE", "WIND_TURBINE_ONSHORE", 42_000, "2021-09-01"),
            Device("Metsä bio CHP", "BIOMASS_SOLID_FOREST", "THERMAL_STEAM_TURBINE_CHP", 30_000, "2016-10-01"),
        )
        private val SWEDISH_DEVICES = listOf(
            Device("Älvkraft hydro", "HYDRO_RESERVOIR", "HYDRO_TURBINE_STORAGE", 60_000, "2008-06-01"),
            Device("Fjällvind wind", "WIND_ONSHORE", "WIND_TURBINE_ONSHORE", 36_000, "2020-04-01"),
            Device("Solgård solar park", "SOLAR", "SOLAR_PV_GROUND", 18_000, "2023-05-15"),
            Device("Kustvind offshore", "WIND_OFFSHORE", "WIND_TURBINE_OFFSHORE", 120_000, "2022-11-01"),
        )

        /** A valid 18-digit GS1 GSRN in a range reserved for demo devices (mod-10 check digit). */
        fun gsrn(sequence: Int): String {
            val body = "64390" + (900_000_000 + sequence).toString().padStart(12, '0')
            val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
            return body + ((10 - sum % 10) % 10)
        }
    }
}
