package com.pianonurmi.etrm.demo

import java.math.BigDecimal

/** The counterparties of the demo, in the order [DemoScenario] creates them. */
data class DemoCounterparties(val nordvind: String, val rheinstrom: String, val aurora: String)

/**
 * Back and middle office work for the Finnish entity, so the operations screens have something to show: GoO
 * deliveries this week, invoiced (one paid, one waiting for the controller), a purchase invoice and a netting draft
 * with a counterparty we net with, a manual transfer waiting for a second person, a registry statement with one
 * break, and a counterparty confirmation that does not match ours.
 */
class DemoOperations(private val api: DemoClient, private val calendar: DemoCalendar) {
    fun seed(entity: DemoEntity, counterparties: DemoCounterparties) {
        partyDetails(entity)
        invoicing(entity, counterparties.aurora)
        netting(entity, counterparties.nordvind)
        reconciliation(entity)
        transfer(entity, counterparties.aurora)
        confirmationMismatch(entity, counterparties.rheinstrom)
    }

    /**
     * Invoices name our VAT number (reverse-charged purchases need it) and the account we are paid into; bank
     * details change with four eyes. The VAT number is a placeholder, like the entity.
     */
    private fun partyDetails(entity: DemoEntity) {
        api.put(
            DemoUser.ACCOUNTANT,
            "/api/v1/reference/parties/LEGAL_ENTITY/${entity.id}/vat-registrations",
            """{"country":"FI","vatNumber":"FI00000000","validFrom":"${calendar.today.year - 1}-01-01"}""",
        )
        val request = api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/reference/parties/LEGAL_ENTITY/${entity.id}/bank-accounts/change-requests",
            """{"action":"CREATE","reason":"Collection account","currency":"EUR","iban":"FI2112345600000785","bic":"NDEAFIHH",
               "accountName":"${entity.code} collections"}""",
        ).id()
        api.approveTask(DemoUser.RISK, "REFERENCE_DATA_CHANGE", request)
    }

    /** Two domestic sales: the first invoiced, approved and paid; the second's invoice waits in the controller's inbox. */
    private fun invoicing(entity: DemoEntity, aurora: String) {
        val paid = deliveredGoo(entity, "SELL", aurora, quantity = 500, price = "1.90")
        val first = invoiceRun(entity, aurora)
        issue(first)
        val invoice = api.get(DemoUser.ACCOUNTANT, "/api/v1/settlement/invoices/$first") as Map<*, *>
        api.post(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/payments",
            """{"legalEntityId":"${entity.id}","direction":"INCOMING","amount":${amount(invoice["grossTotal"])},"currency":"EUR",
               "valueDate":"${calendar.today}","bankReference":"DEMO-BANK-${paid.takeLast(8)}","remittanceInfo":"Invoice ${invoice["number"]}"}""",
        )
        deliveredGoo(entity, "SELL", aurora, quantity = 350, price = "1.92")
        api.post(DemoUser.ACCOUNTANT, "/api/v1/settlement/invoices/${invoiceRun(entity, aurora)}/submit")
    }

    /** A sale to and a purchase from a counterparty we net with: both invoices issued, the netting statement prepared. */
    private fun netting(entity: DemoEntity, nordvind: String) {
        deliveredGoo(entity, "SELL", nordvind, quantity = 300, price = "1.95")
        issue(invoiceRun(entity, nordvind))
        deliveredGoo(entity, "BUY", nordvind, quantity = 200, price = "1.70")
        val purchase = api.post(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/purchase-invoices",
            """{"legalEntityId":"${entity.id}","counterpartyId":"$nordvind","supplierInvoiceRef":"NV-${calendar.today.year}-0417",
               "invoiceDate":"${calendar.today}","currency":"EUR","netAmount":340.00,"vatAmount":0}""",
        ).id()
        issue(purchase)
        // Prepared by one accountant; recording the counterparty's agreement is for another.
        api.post(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/netting",
            """{"legalEntityId":"${entity.id}","counterpartyId":"$nordvind","currency":"EUR","valueDate":"${calendar.today.plusDays(NETTING_DAYS)}"}""",
        )
    }

    /** Today's holdings statement agrees with our lots except for the last one, which the registry shows shorter. */
    private fun reconciliation(entity: DemoEntity) {
        val lots = api.getList(DemoUser.BACKOFFICE, "/api/v1/inventory/lots?holderEntityId=${entity.id}")
            .filter { it["registryAccountId"] == entity.gooAccountId }
            .sortedBy { (it["range"] as Map<*, *>)["start"].toString().toLong() }
        val rows = lots.mapIndexed { index, lot ->
            val range = lot["range"] as Map<*, *>
            val period = lot["productionPeriod"] as Map<*, *>
            val end = range["end"].toString().toLong() - if (index == lots.lastIndex) MISSING_CERTIFICATES else 0
            "${lot["issuanceRef"]},${range["start"]},$end,${lot["deviceGsrn"]},${period["start"]},${period["endExclusive"]}"
        }
        val csv = (listOf("issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end") + rows).joinToString("\n", postfix = "\n")
        api.postText(
            DemoUser.BACKOFFICE,
            "/api/v1/registry/statements/csv?registryAccountId=${entity.gooAccountId}&statementDate=${calendar.today}",
            csv,
        )
    }

    /** A manual transfer out of one lot, waiting for a second operator in the inbox. */
    private fun transfer(entity: DemoEntity, aurora: String) {
        val lot = api.getList(DemoUser.BACKOFFICE, "/api/v1/inventory/lots?holderEntityId=${entity.id}")
            .first { it["registryAccountId"] == entity.gooAccountId }
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/registry/instructions/transfers",
            """{"lotId":"${lot.id()}","quantityMwh":50,"toRegistryCode":"SIMULATOR","toAccountRef":"SIM-EXT-${aurora.takeLast(6)}",
               "idempotencyKey":"demo-transfer-${entity.code}"}""",
        )
    }

    /** The counterparty confirms a higher price than we agreed; the break waits for middle office. */
    private fun confirmationMismatch(entity: DemoEntity, rheinstrom: String) {
        val tradeId = gooTrade(entity, "SELL", rheinstrom, quantity = 250, price = "2.00")
        api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", tradeId)
        val ours = api.getList(DemoUser.MIDDLE, "/api/v1/trades/$tradeId/confirmations").first { it["direction"] == "OURS" }

        @Suppress("UNCHECKED_CAST")
        val terms = (ours["terms"] as Map<String, Any?>).toMutableMap()

        @Suppress("UNCHECKED_CAST")
        val leg = (terms["legs"] as List<Map<String, Any?>>).single().toMutableMap()
        leg["price"] = BigDecimal("2.10")
        leg["amount"] = BigDecimal("525.00")
        terms["legs"] = listOf(leg)
        api.postBody(DemoUser.MIDDLE, "/api/v1/confirmations/counterparty", mapOf("terms" to terms, "reference" to "RS-CONF-88213", "source" to "MANUAL"))
    }

    private fun invoiceRun(entity: DemoEntity, counterpartyId: String): String {
        @Suppress("UNCHECKED_CAST")
        val drafts = api.postForList(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/invoice-runs",
            """{"legalEntityId":"${entity.id}","upTo":"${calendar.today}","counterpartyId":"$counterpartyId"}""",
        )
        return drafts.single().id()
    }

    /** Submitted by the accountant, approved by the controller. */
    private fun issue(invoiceId: String) {
        api.post(DemoUser.ACCOUNTANT, "/api/v1/settlement/invoices/$invoiceId/submit")
        api.approveTask(DemoUser.CONTROLLER, "INVOICE_APPROVAL", invoiceId)
    }

    /** A confirmed GoO trade delivered today in full. */
    private fun deliveredGoo(entity: DemoEntity, side: String, counterpartyId: String, quantity: Int, price: String): String {
        val tradeId = gooTrade(entity, side, counterpartyId, quantity, price)
        api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", tradeId)
        api.post(DemoUser.BACKOFFICE, "/api/v1/trades/$tradeId/confirm", """{"confirmationRef":"DEMO-CONF-${tradeId.takeLast(8)}"}""")
        val trade = api.get(DemoUser.BACKOFFICE, "/api/v1/trades/$tradeId") as Map<*, *>
        val schedule = ((trade["legs"] as List<*>).single() as Map<*, *>)["deliveries"].let { (it as List<*>).single() as Map<*, *> }
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/trades/$tradeId/deliveries",
            """{"scheduleId":"${schedule["id"]}","quantity":$quantity,"deliveredOn":"${calendar.today}","reference":"DEMO-DLV-${tradeId.takeLast(8)}",
               "idempotencyKey":"demo:$tradeId"}""",
        )
        return tradeId
    }

    private fun gooTrade(entity: DemoEntity, side: String, counterpartyId: String, quantity: Int, price: String): String = api.post(
        DemoUser.TRADER,
        "/api/v1/trades",
        """{"legalEntityId":"${entity.id}","bookId":"${entity.tradingBookId}","counterpartyId":"$counterpartyId","productFamily":"GUARANTEE_OF_ORIGIN",
           "instrumentType":"SPOT","tradeDate":"${calendar.lastClose}","legs":[{"side":"$side","criterion":{"type":"all","of":[
             {"type":"countryInRegion","region":"NORDIC"},{"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"HYDRO"}]},
           "quantity":$quantity,"unit":"MWH","price":$price,"priceCurrency":"EUR","deliveryAccountId":"${entity.gooAccountId}",
           "counterpartyAccountRef":"SIMULATOR:SIM-EXT-${counterpartyId.takeLast(6)}",
           "deliveries":[{"deliveryWindow":{"start":"${calendar.lastClose}","endExclusive":"${calendar.today.plusDays(1)}"},
             "deliveryDate":"${calendar.today}","quantity":$quantity}]}]}""",
    ).id()

    private fun amount(value: Any?): String = BigDecimal(value.toString()).toPlainString()

    private companion object {
        const val NETTING_DAYS = 30L
        const val MISSING_CERTIFICATES = 20L
    }
}
