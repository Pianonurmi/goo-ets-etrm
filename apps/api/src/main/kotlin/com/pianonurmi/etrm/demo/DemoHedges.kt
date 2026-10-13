package com.pianonurmi.etrm.demo

/**
 * Cash flow hedges (ADR 0045): DEMO-FI fixes the price of allowances it will buy for its surrender with forwards
 * bought for the purpose. One hedge is designated by the accountant and approved by the controller, so its
 * documentation is issued; a second waits in the controller's inbox. Effectiveness is measured from the next official
 * valuation on (a hedge is never backdated).
 */
class DemoHedges(private val api: DemoClient, private val calendar: DemoCalendar, private val desk: DemoDesk) {
    fun seed(finland: DemoEntity, parties: DemoParties) {
        val seller = parties.getValue("CP-RHEINSTROM")
        val first = confirmed(desk.euaForward(finland, seller, "BUY", ACTIVE_TONNES, "71.80", calendar.tradeDate))
        val active =
            designate(finland, first, ACTIVE_TONNES, "Fix the price of $ACTIVE_TONNES t of allowances bought for the ${calendar.deliveryYear} surrender")
        api.approveTask(DemoUser.CONTROLLER, "HEDGE_DESIGNATION", active)
        val second = confirmed(desk.euaForward(finland, seller, "BUY", PENDING_TONNES, "72.40", calendar.tradeDate))
        designate(finland, second, PENDING_TONNES, "Fix the price of the allowances for the expected growth in emissions in ${calendar.deliveryYear}")
    }

    /** Middle office approves this trade alone (others wait in the inbox on purpose), back office confirms it. */
    private fun confirmed(tradeId: String): String {
        val trade = api.get(DemoUser.MIDDLE, "/api/v1/trades/$tradeId") as Map<*, *>
        if (trade["status"] == "PENDING_APPROVAL") api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", tradeId)
        desk.confirm(tradeId)
        return tradeId
    }

    private fun designate(finland: DemoEntity, tradeId: String, tonnes: Int, objective: String): String = api.post(
        DemoUser.ACCOUNTANT,
        "/api/v1/ledger/hedges",
        """{"legalEntityId":"${finland.id}","objective":"$objective",
           "item":{"direction":"PURCHASE","productFamily":"EMISSION_ALLOWANCE","allowanceProductCode":"EUA","quantity":$tonnes,
             "expectedDate":"${calendar.euaDeliveryDate}","currency":"EUR"},
           "instruments":[{"tradeId":"$tradeId","proportion":1}]}""",
    ).id()

    private companion object {
        const val ACTIVE_TONNES = 4000
        const val PENDING_TONNES = 1500
    }
}
