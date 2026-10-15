package com.pianonurmi.etrm.demo

import java.time.YearMonth

/**
 * Certificates for a year of production: the first two devices issue every month, the others every third month,
 * and the oldest hydro production is already past its expiry. Costs differ by device.
 */
class DemoInventory(private val api: DemoClient, private val calendar: DemoCalendar) {
    fun receive(entity: DemoEntity, issuanceSeed: Int) {
        val thisMonth = YearMonth.from(calendar.today)
        var start = 1L
        entity.deviceGsrns.forEachIndexed { deviceIndex, gsrn ->
            val months = when (deviceIndex) {
                0 -> (1..MONTHS_BACK + 2)
                1 -> (1..MONTHS_BACK)
                else -> (2..MONTHS_BACK step 3)
            }.map { thisMonth.minusMonths(it.toLong()) }
            months.forEachIndexed { monthIndex, month ->
                val quantity = 150L + ((monthIndex * 7 + deviceIndex * 3) % 11) * 45L
                val end = start + quantity - 1
                val issued = month.plusMonths(1).atDay(ISSUE_DAY).let { if (it.isAfter(calendar.today)) calendar.today else it }
                api.post(
                    DemoUser.BACKOFFICE,
                    "/api/v1/inventory/lots",
                    """{"holderEntityId":"${entity.id}","registryAccountId":"${entity.gooAccountId}",
                       "issuanceRef":"DEMO-ISS-$issuanceSeed-$deviceIndex-$month","range":{"start":$start,"end":$end},
                       "deviceGsrn":"$gsrn","productionPeriod":{"start":"${month.atDay(1)}","endExclusive":"${month.plusMonths(1).atDay(1)}"},
                       "issueDate":"$issued","issuingBody":"SIM","issuingCountry":"${entity.code.takeLast(2)}",
                       "supportProduction":${deviceIndex == 2},"supportInvestment":false,"acquisitionSource":"ISSUANCE",
                       "costBasisPerMwh":${COSTS[deviceIndex]},"costCurrency":"EUR"}""",
                )
                start = end + 1
            }
        }
    }

    private companion object {
        const val MONTHS_BACK = 12
        const val ISSUE_DAY = 10
        val COSTS = listOf("0.85", "1.10", "1.35", "0.95")
    }
}

/**
 * Trades in the states a desk sees every day: confirmed (with open delivery obligations, valued, accounted) and
 * waiting for middle office approval in the inbox.
 */
class DemoTrades(private val api: DemoClient, private val calendar: DemoCalendar) {
    fun book(finland: DemoEntity, sweden: DemoEntity, counterparties: List<String>) {
        val (nordvind, rheinstrom, aurora) = counterparties
        confirm(gooSale(finland, finland.ownUseBookId, nordvind, quantity = 600, price = "1.95"))
        confirm(euaPurchase(finland, rheinstrom, quantity = 1000, price = "68.00"))
        confirm(gooSale(sweden, sweden.tradingBookId, aurora, quantity = 400, price = "1.80"))
        // Waiting for approval: one for each middle office persona to pick up.
        gooSale(finland, finland.tradingBookId, rheinstrom, quantity = 300, price = "2.05")
        euaPurchase(finland, nordvind, quantity = 500, price = "71.20")
    }

    private fun gooSale(entity: DemoEntity, bookId: String, counterpartyId: String, quantity: Int, price: String): String = api.post(
        DemoUser.TRADER,
        "/api/v1/trades",
        """{"legalEntityId":"${entity.id}","bookId":"$bookId","counterpartyId":"$counterpartyId","productFamily":"GUARANTEE_OF_ORIGIN",
           "instrumentType":"FORWARD","tradeDate":"${calendar.tradeDate}","legs":[{"side":"SELL","criterion":{"type":"all","of":[
             {"type":"countryInRegion","region":"NORDIC"},{"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"HYDRO"}]},
           "quantity":$quantity,"unit":"MWH","price":$price,"priceCurrency":"EUR","deliveryAccountId":"${entity.gooAccountId}",
           "counterpartyAccountRef":"SIMULATOR:SIM-EXT-${counterpartyId.takeLast(6)}",
           "deliveries":[{"deliveryWindow":{"start":"${calendar.gooWindowStart}","endExclusive":"${calendar.gooWindowEnd}"},
             "deliveryDate":"${calendar.gooDeliveryDate}","quantity":$quantity}]}]}""",
    ).id()

    private fun euaPurchase(entity: DemoEntity, counterpartyId: String, quantity: Int, price: String): String {
        val delivery = calendar.euaDeliveryDate
        return api.post(
            DemoUser.TRADER,
            "/api/v1/trades",
            """{"legalEntityId":"${entity.id}","bookId":"${entity.tradingBookId}","counterpartyId":"$counterpartyId",
               "productFamily":"EMISSION_ALLOWANCE","instrumentType":"FORWARD","tradeDate":"${calendar.tradeDate}",
               "regulatory":{"executionTimestamp":"${calendar.tradeDate}T09:30:00Z","venueMic":"XOFF"},
               "legs":[{"side":"BUY","allowanceProductCode":"EUA","quantity":$quantity,"unit":"TCO2E","price":$price,"priceCurrency":"EUR",
               "deliveries":[{"deliveryWindow":{"start":"$delivery","endExclusive":"${delivery.plusDays(1)}"},"deliveryDate":"$delivery",
               "quantity":$quantity}]}]}""",
        ).id()
    }

    private fun confirm(tradeId: String) {
        api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", tradeId)
        api.post(DemoUser.BACKOFFICE, "/api/v1/trades/$tradeId/confirm", """{"confirmationRef":"DEMO-CONF-${tradeId.takeLast(8)}"}""")
    }
}
