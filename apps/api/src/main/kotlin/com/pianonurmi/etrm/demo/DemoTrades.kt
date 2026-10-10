package com.pianonurmi.etrm.demo

import java.time.YearMonth

/** Certificates across vintages and devices; the oldest expire within weeks, so the expiry ladder has something to show. */
class DemoInventory(private val api: DemoClient, private val calendar: DemoCalendar) {
    fun receive(entity: DemoEntity, issuanceSeed: Int) {
        val months = (2..11 step 3).map { YearMonth.from(calendar.today).minusMonths(it.toLong()) }
        var start = 1L
        months.forEachIndexed { monthIndex, month ->
            entity.deviceGsrns.forEachIndexed { deviceIndex, gsrn ->
                val quantity = 250L + (monthIndex * 3 + deviceIndex) * 125L
                val end = start + quantity - 1
                val cost = listOf("0.85", "1.10", "1.35")[deviceIndex]
                api.post(
                    DemoUser.BACKOFFICE,
                    "/api/v1/inventory/lots",
                    """{"holderEntityId":"${entity.id}","registryAccountId":"${entity.gooAccountId}",
                       "issuanceRef":"DEMO-ISS-$issuanceSeed-$monthIndex-$deviceIndex","range":{"start":$start,"end":$end},
                       "deviceGsrn":"$gsrn","productionPeriod":{"start":"${month.atDay(1)}","endExclusive":"${month.plusMonths(1).atDay(1)}"},
                       "issueDate":"${month.plusMonths(1).atDay(10)}","issuingBody":"SIM","issuingCountry":"${entity.code.takeLast(2)}",
                       "supportProduction":false,"supportInvestment":false,"acquisitionSource":"ISSUANCE",
                       "costBasisPerMwh":$cost,"costCurrency":"EUR"}""",
                )
                start = end + 1
            }
        }
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
