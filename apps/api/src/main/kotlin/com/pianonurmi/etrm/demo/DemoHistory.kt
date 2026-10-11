package com.pianonurmi.etrm.demo

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** The counterparties of the demo by code, as the counterparties step stores them. */
typealias DemoParties = Map<String, String>

/**
 * Six months of business, one month at a time and in date order, so each month-end valuation sees only the trades
 * that existed then: the month's trades (captured, approved by middle office, confirmed by back office), official
 * curves at month end, official valuations for both entities, position snapshots, period end, and the month closed
 * the way finance does it: soft close, GL export, delivery, acknowledgement, hard close.
 */
class DemoHistory(
    private val api: DemoClient,
    private val calendar: DemoCalendar,
    private val market: DemoMarket,
    private val desk: DemoDesk,
) {
    /** One past month, from six months ago to two months ago (the last month is the existing trades and accounting steps). */
    fun month(month: YearMonth, finland: DemoEntity, sweden: DemoEntity, parties: DemoParties) {
        val monthsAgo = ChronoUnit.MONTHS.between(month, YearMonth.from(calendar.today)).toInt()
        trade(month, monthsAgo, finland, sweden, parties)
        val monthEnd = DemoCalendar.weekdayOnOrBefore(month.atEndOfMonth())
        market.officialCurves(listOf(monthEnd))
        listOf(finland, sweden).forEach { entity ->
            desk.officialValuation(entity, monthEnd)
            api.post(DemoUser.MIDDLE, "/api/v1/positions/snapshots?legalEntityId=${entity.id}&asOf=${month.atEndOfMonth()}")
            close(entity, month, hard = monthsAgo >= HARD_CLOSE_FROM, acknowledge = true)
        }
    }

    /** Period end and the close of one month; the month after must still be open (MtM reverses on its first day). */
    fun close(entity: DemoEntity, month: YearMonth, hard: Boolean, acknowledge: Boolean) {
        api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/period-end/runs", """{"legalEntityId":"${entity.id}","period":"$month"}""")
        api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/periods/$month/close?legalEntityId=${entity.id}")
        val export = api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/exports/$month?legalEntityId=${entity.id}&format=GENERIC_CSV").id()
        api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/exports/$export/delivered")
        if (!acknowledge) return
        api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/exports/$export/acknowledge", """{"reference":"SAP-${entity.code}-$month"}""")
        if (hard) api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/periods/$month/close?legalEntityId=${entity.id}&hard=true")
    }

    private fun trade(month: YearMonth, monthsAgo: Int, finland: DemoEntity, sweden: DemoEntity, parties: DemoParties) {
        val day = { dayOfMonth: Int -> DemoCalendar.weekdayOnOrAfter(month.atDay(dayOfMonth.coerceAtMost(month.lengthOfMonth()))) }
        val bucket = DemoMarket.BUCKETS[monthsAgo % DemoMarket.BUCKETS.size]
        val buyers = listOf("CP-NORDVIND", "CP-RHEINSTROM", "CP-VINDEN", "CP-POLDER")
        val sellers = listOf("CP-SOLEIL", "CP-IBERSOL", "CP-POLDER")
        val trades = buildList {
            add(
                desk.gooForward(
                    finland,
                    parties.getValue(buyers[monthsAgo % buyers.size]),
                    "SELL",
                    bucket.code,
                    400 + monthsAgo * 50,
                    gooPrice(monthsAgo),
                    day(4),
                ),
            )
            add(desk.gooForward(finland, parties.getValue(sellers[monthsAgo % sellers.size]), "BUY", "EU_WIND", 300 + monthsAgo * 25, "0.42", day(9)))
            add(
                desk.euaForward(
                    finland,
                    parties.getValue(if (monthsAgo % 2 == 0) "CP-RHEINSTROM" else "CP-VINDEN"),
                    "BUY",
                    1000 + monthsAgo * 250,
                    euaPrice(monthsAgo),
                    day(12),
                    broker = parties.getValue("CP-NORDBROKER").takeIf { monthsAgo % 2 == 0 },
                ),
            )
            add(desk.gooForward(sweden, parties.getValue("CP-POLDER"), "SELL", "NORDIC_WIND", 250 + monthsAgo * 20, "0.38", day(16)))
            when (monthsAgo) {
                6 -> add(desk.euaFuture(finland, parties.getValue("CP-CLEARING"), "BUY", 5, market.futures[0], "67.80", day(18)))
                5 -> add(desk.gooIndexed(finland, parties.getValue("CP-NORDVIND"), "SELL", 600, "0.05", day(20)))
                4 -> add(desk.euaFuture(finland, parties.getValue("CP-CLEARING"), "BUY", 3, market.futures[1], "71.10", day(18)))
                3 -> {
                    add(desk.gooIndexed(finland, parties.getValue("CP-VINDEN"), "SELL", 450, "0.04", day(20)))
                    add(desk.intercompany(finland, sweden, parties.getValue("CP-DEMO-SE"), 300, "0.36", day(22)))
                }
                2 -> add(desk.euaFuture(finland, parties.getValue("CP-CLEARING"), "SELL", 2, market.futures[0], "72.40", day(18)))
            }
        }
        // One trade a month is booked through a broker; one is cancelled before approval and one amended after confirmation.
        val cancelled = if (monthsAgo == 5) desk.gooForward(finland, parties.getValue("CP-RHEINSTROM"), "SELL", "NORDIC_HYDRO", 150, "0.40", day(24)) else null
        cancelled?.let { desk.cancel(it, "Booked twice; the trade stays on the other ticket") }
        desk.approveAndConfirm(trades)
        if (monthsAgo == 4) desk.amendReference(trades.first(), "Broker ticket number added after confirmation")
    }

    private fun gooPrice(monthsAgo: Int) = listOf("0.31", "0.33", "0.35", "0.34", "0.36", "0.37", "0.39")[monthsAgo]

    private fun euaPrice(monthsAgo: Int) = listOf("74.10", "73.20", "72.60", "70.90", "69.40", "68.30", "66.90")[monthsAgo]

    companion object {
        /** Months this far back are hard closed; the newer ones stay soft closed. */
        const val HARD_CLOSE_FROM = 3
        const val MONTHS = 6
    }
}

/** How the demo desk books and processes trades through the API. */
class DemoDesk(private val api: DemoClient, private val calendar: DemoCalendar) {
    fun gooForward(entity: DemoEntity, counterpartyId: String, side: String, bucket: String, quantity: Int, price: String, tradeDate: LocalDate): String =
        capture(
            entity,
            counterpartyId,
            "GUARANTEE_OF_ORIGIN",
            "FORWARD",
            tradeDate,
            """{"side":"$side","criterion":${criterion(bucket)},"quantity":$quantity,"unit":"MWH","price":$price,"priceCurrency":"EUR",
               "deliveryAccountId":"${entity.gooAccountId}","counterpartyAccountRef":"SIMULATOR:SIM-EXT-${counterpartyId.takeLast(6)}",
               "deliveries":[${gooDelivery(quantity)}]}""",
        )

    /** Priced at the monthly average of the Nordic hydro index plus a spread. */
    fun gooIndexed(entity: DemoEntity, counterpartyId: String, side: String, quantity: Int, spread: String, tradeDate: LocalDate): String =
        capture(
            entity,
            counterpartyId,
            "GUARANTEE_OF_ORIGIN",
            "FORWARD",
            tradeDate,
            """{"side":"$side","criterion":${criterion("NORDIC_HYDRO")},"quantity":$quantity,"unit":"MWH","price":$spread,"priceCurrency":"EUR",
               "priceType":"INDEX","priceIndex":"${DemoMarket.INDEX}","deliveryAccountId":"${entity.gooAccountId}",
               "counterpartyAccountRef":"SIMULATOR:SIM-EXT-${counterpartyId.takeLast(6)}","deliveries":[${gooDelivery(quantity)}]}""",
        )

    fun euaForward(
        entity: DemoEntity,
        counterpartyId: String,
        side: String,
        quantity: Int,
        price: String,
        tradeDate: LocalDate,
        broker: String? = null,
    ): String {
        val delivery = calendar.euaDeliveryDate
        return capture(
            entity,
            counterpartyId,
            "EMISSION_ALLOWANCE",
            "FORWARD",
            tradeDate,
            """{"side":"$side","allowanceProductCode":"EUA","quantity":$quantity,"unit":"TCO2E","price":$price,"priceCurrency":"EUR",
               "deliveries":[{"deliveryWindow":{"start":"$delivery","endExclusive":"${delivery.plusDays(
                1,
            )}"},"deliveryDate":"$delivery","quantity":$quantity}]}""",
            regulatory = """{"executionTimestamp":"${tradeDate}T10:15:00Z","venueMic":"XOFF"}""",
            broker = broker,
        )
    }

    fun euaFuture(entity: DemoEntity, clearingId: String, side: String, lots: Int, future: DemoFuture, price: String, tradeDate: LocalDate): String {
        val quantity = lots * FUTURES_LOT
        val delivery = future.deliveryDate
        return capture(
            entity,
            clearingId,
            "EMISSION_ALLOWANCE",
            "FUTURE",
            tradeDate,
            """{"side":"$side","allowanceProductCode":"EUA","quantity":$quantity,"unit":"TCO2E","price":$price,"priceCurrency":"EUR",
               "futuresContractCode":"${future.code}",
               "deliveries":[{"deliveryWindow":{"start":"$delivery","endExclusive":"${delivery.plusDays(
                1,
            )}"},"deliveryDate":"$delivery","quantity":$quantity}]}""",
            regulatory = """{"executionTimestamp":"${tradeDate}T11:02:00Z","venueMic":"XEEE"}""",
        )
    }

    /** A sale from the Finnish to the Swedish entity; the mirror trade is created and confirmed with it. */
    fun intercompany(finland: DemoEntity, sweden: DemoEntity, counterpartyId: String, quantity: Int, price: String, tradeDate: LocalDate): String =
        capture(
            finland,
            counterpartyId,
            "GUARANTEE_OF_ORIGIN",
            "FORWARD",
            tradeDate,
            """{"side":"SELL","criterion":${criterion("NORDIC_WIND")},"quantity":$quantity,"unit":"MWH","price":$price,"priceCurrency":"EUR",
               "deliveryAccountId":"${finland.gooAccountId}","counterpartyAccountId":"${sweden.gooAccountId}",
               "deliveries":[${gooDelivery(quantity)}]}""",
            mirrorBookId = sweden.tradingBookId,
        )

    /** A GoO spot trade delivered in full on [deliveredOn] (both sides confirmed, delivery recorded by back office). */
    fun deliveredSpot(entity: DemoEntity, counterpartyId: String, side: String, quantity: Int, price: String, deliveredOn: LocalDate): String {
        val tradeId = capture(
            entity,
            counterpartyId,
            "GUARANTEE_OF_ORIGIN",
            "SPOT",
            deliveredOn,
            """{"side":"$side","criterion":${criterion("NORDIC_HYDRO")},"quantity":$quantity,"unit":"MWH","price":$price,"priceCurrency":"EUR",
               "deliveryAccountId":"${entity.gooAccountId}","counterpartyAccountRef":"SIMULATOR:SIM-EXT-${counterpartyId.takeLast(6)}",
               "deliveries":[{"deliveryWindow":{"start":"$deliveredOn","endExclusive":"${deliveredOn.plusDays(
                2,
            )}"},"deliveryDate":"$deliveredOn","quantity":$quantity}]}""",
        )
        api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", tradeId)
        confirm(tradeId)
        val trade = api.get(DemoUser.BACKOFFICE, "/api/v1/trades/$tradeId") as Map<*, *>
        val schedule = ((trade["legs"] as List<*>).single() as Map<*, *>)["deliveries"].let { (it as List<*>).single() as Map<*, *> }
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/trades/$tradeId/deliveries",
            """{"scheduleId":"${schedule["id"]}","quantity":$quantity,"deliveredOn":"$deliveredOn","reference":"DEMO-DLV-${tradeId.takeLast(8)}",
               "idempotencyKey":"demo:$tradeId"}""",
        )
        return tradeId
    }

    fun cancel(tradeId: String, reason: String) {
        api.post(DemoUser.TRADER, "/api/v1/trades/$tradeId/cancel", """{"reason":"$reason"}""")
    }

    /** Middle office approves everything waiting (bulk), back office confirms each trade. */
    fun approveAndConfirm(tradeIds: List<String>) {
        api.approveAll(DemoUser.MIDDLE, api.openTasks(DemoUser.MIDDLE, "TRADE_APPROVAL"))
        tradeIds.forEach { confirm(it) }
    }

    fun confirm(tradeId: String) {
        val trade = api.get(DemoUser.BACKOFFICE, "/api/v1/trades/$tradeId") as Map<*, *>
        if (trade["status"] == "APPROVED") {
            api.post(DemoUser.BACKOFFICE, "/api/v1/trades/$tradeId/confirm", """{"confirmationRef":"DEMO-CONF-${tradeId.takeLast(8)}"}""")
        }
    }

    /** Version 2 of a confirmed trade: approved and confirmed again. */
    fun amendReference(tradeId: String, reason: String) {
        val trade = api.get(DemoUser.TRADER, "/api/v1/trades/$tradeId") as Map<*, *>
        api.put(
            DemoUser.TRADER,
            "/api/v1/trades/$tradeId",
            """{"expectedVersion":${trade["version"]},"reason":"$reason","externalRef":"BRK-${tradeId.takeLast(6).uppercase()}"}""",
        )
        approveAndConfirm(listOf(tradeId))
    }

    /** An end-of-day valuation, submitted by one analyst and signed off by the other. */
    fun officialValuation(entity: DemoEntity, date: LocalDate) {
        val run = api.post(DemoUser.RISK, "/api/v1/valuation/runs", """{"legalEntityId":"${entity.id}","valuationDate":"$date","kind":"EOD"}""").id()
        api.post(DemoUser.RISK, "/api/v1/valuation/runs/$run/submit")
        api.approveTask(DemoUser.RISK2, "VALUATION_SIGN_OFF", run)
    }

    private fun capture(
        entity: DemoEntity,
        counterpartyId: String,
        family: String,
        instrument: String,
        tradeDate: LocalDate,
        leg: String,
        regulatory: String? = null,
        broker: String? = null,
        mirrorBookId: String? = null,
    ): String = api.post(
        DemoUser.TRADER,
        "/api/v1/trades",
        """{"legalEntityId":"${entity.id}","bookId":"${entity.tradingBookId}","counterpartyId":"$counterpartyId","productFamily":"$family",
           "instrumentType":"$instrument","tradeDate":"$tradeDate","legs":[$leg]
           ${regulatory?.let { ""","regulatory":$it""" } ?: ""}${broker?.let { ""","brokerId":"$it"""" } ?: ""}
           ${mirrorBookId?.let { ""","mirrorBookId":"$it"""" } ?: ""},"masterAgreementRef":"EFET-GOO-2024"}""",
    ).id()

    private fun gooDelivery(quantity: Int) =
        """{"deliveryWindow":{"start":"${calendar.gooWindowStart}","endExclusive":"${calendar.gooWindowEnd}"},""" +
            """"deliveryDate":"${calendar.gooDeliveryDate}","quantity":$quantity}"""

    companion object {
        const val FUTURES_LOT = 1000

        /** The criteria of each priced bucket, as a trader would enter them. */
        fun criterion(bucket: String): String = when (bucket) {
            "NORDIC_HYDRO" -> regionAndSource("NORDIC", "HYDRO")
            "NORDIC_WIND" -> regionAndSource("NORDIC", "WIND")
            "EU_WIND" -> regionAndSource("EEA", "WIND")
            "EU_SOLAR" -> regionAndSource("EEA", "SOLAR")
            else -> error("No criteria for bucket $bucket")
        }

        private fun regionAndSource(region: String, source: String) =
            """{"type":"all","of":[{"type":"countryInRegion","region":"$region"},""" +
                """{"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"$source"}]}"""
    }
}
