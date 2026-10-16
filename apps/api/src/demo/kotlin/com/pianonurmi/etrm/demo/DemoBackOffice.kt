package com.pianonurmi.etrm.demo

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The work of back office, finance, compliance and risk on top of the trading history, so every screen has
 * something in every state: certificates expired, cancelled, reserved, split and withdrawn; allocation plans executed
 * and one waiting; allowance transfers confirmed and failed; invoices paid, partly paid, overdue and credited; a
 * settled netting; confirmations matched and disputed; Swedish reconciliation breaks; verified emissions and last
 * year's surrender; limit breaches open and acknowledged; a valuation waiting for sign-off; EMIR exports.
 */
class DemoBackOffice(private val api: DemoClient, private val calendar: DemoCalendar, private val desk: DemoDesk) {
    /** Addresses and VAT numbers of every external counterparty; a EUR collection account for the Swedish entity. */
    fun partyDetails(parties: DemoParties, sweden: DemoEntity) {
        ADDRESSES.forEach { (code, address) ->
            val id = parties[code] ?: return@forEach
            api.put(
                DemoUser.MIDDLE,
                "/api/v1/reference/parties/COUNTERPARTY/$id/addresses",
                """{"addressType":"REGISTERED","line1":"${address.line1}","postalCode":"${address.postalCode}","city":"${address.city}",
                   "country":"${address.country}"}""",
            )
            api.put(
                DemoUser.ACCOUNTANT,
                "/api/v1/reference/parties/COUNTERPARTY/$id/vat-registrations",
                """{"country":"${address.country}","vatNumber":"${address.vat}","validFrom":"${calendar.today.year - 2}-01-01"}""",
            )
        }
        api.put(
            DemoUser.ACCOUNTANT,
            "/api/v1/reference/parties/LEGAL_ENTITY/${sweden.id}/vat-registrations",
            """{"country":"SE","vatNumber":"SE000000000001","validFrom":"${calendar.today.year - 1}-01-01"}""",
        )
        val request = api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/reference/parties/LEGAL_ENTITY/${sweden.id}/bank-accounts/change-requests",
            """{"action":"CREATE","reason":"EUR collection account","currency":"EUR","iban":"SE4550000000058398257466","bic":"ESSESESS",
               "accountName":"${sweden.code} EUR collections"}""",
        ).id()
        api.approveTask(DemoUser.RISK, "REFERENCE_DATA_CHANGE", request)
    }

    /** Certificates in every state: expired by the nightly job, reserved, split, withdrawn and cancelled for own use. */
    fun certificateLifecycle(finland: DemoEntity, sweden: DemoEntity) {
        api.post(DemoUser.BACKOFFICE, "/api/v1/inventory/jobs/expire?asOf=${calendar.today}")
        val held = { entity: DemoEntity ->
            api.getList(DemoUser.BACKOFFICE, "/api/v1/inventory/lots?holderEntityId=${entity.id}&statuses=HELD")
                .filter { it["status"] == "HELD" && it["registryAccountId"] == entity.gooAccountId }
                .sortedBy { (it["range"] as Map<*, *>)["start"].toString().toLong() }
        }
        val fi = held(finland)
        // The newest lots stay untouched for allocation; operations work on older ones.
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/inventory/lots/${fi[2].id()}/reserve",
            """{"reservationRef":"${java.util.UUID.nameUUIDFromBytes("demo-reserve".toByteArray())}"}""",
        )
        api.post(DemoUser.BACKOFFICE, "/api/v1/inventory/lots/${fi[3].id()}/split", """{"quantity":50}""")
        api.post(DemoUser.BACKOFFICE, "/api/v1/inventory/lots/${fi[4].id()}/withdraw", """{"reason":"Issued twice by the registry; withdrawn on its notice"}""")
        val se = held(sweden)
        val lot = se[1]
        val period = lot["productionPeriod"] as Map<*, *>
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/inventory/lots/${lot.id()}/cancel",
            """{"quantity":100,"details":{"purpose":"OWN_CONSUMPTION","beneficiaryName":"Demo Kraft AB","consumptionCountry":"SE",
               "consumptionPeriod":{"start":"${period["start"]}","endExclusive":"${period["endExclusive"]}"},"registryCancellationRef":"SIM-CXL-0001"}}""",
        )
    }

    /**
     * Allocation: an own-consumption obligation with a hydro share, two executed plans (instructions confirmed by the
     * simulator, deliveries recorded) and one plan waiting for approval.
     */
    fun allocation(finland: DemoEntity, sweden: DemoEntity) {
        val open = { entity: DemoEntity ->
            api.getList(DemoUser.BACKOFFICE, "/api/v1/allocation/obligations?holderEntityId=${entity.id}").filter { it["status"] == "OPEN" }
        }
        val previous = calendar.previousMonth
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/allocation/obligations",
            """{"kind":"OWN_CONSUMPTION","holderEntityId":"${finland.id}","criterion":${DemoDesk.criterion("NORDIC_HYDRO")},"quantityMwh":120,
               "deliveryWindow":{"start":"${calendar.today}","endExclusive":"${calendar.today.plusDays(45)}"},"deliveryDate":"${calendar.today.plusDays(30)}",
               "sourceRef":"${java.util.UUID.nameUUIDFromBytes("demo-own-use".toByteArray())}",
               "cancellation":{"purpose":"OWN_CONSUMPTION","beneficiaryName":"Demo Energia Oy offices","consumptionCountry":"FI",
                 "consumptionPeriod":{"start":"${previous.atDay(1)}","endExclusive":"${previous.plusMonths(1).atDay(1)}"}},
               "constraints":[{"type":"maxSharePerDevice","percent":80}]}""",
        )
        val fiOpen = open(finland).filter { it["counterpartyId"] != null || it["kind"] == "OWN_CONSUMPTION" }
        val hydro = fiOpen.filter { it["criteriaSummary"].toString().contains("HYDRO", ignoreCase = true) }.take(2).map { it.id() }
        executePlan(finland, hydro, "demo-plan-fi-hydro")
        val wind = open(finland).filter { it["criteriaSummary"].toString().contains("WIND", ignoreCase = true) }.take(1).map { it.id() }
        executePlan(finland, wind, "demo-plan-fi-wind")
        val waiting = open(sweden).take(2).map { it.id() } + genericSale(sweden)
        if (waiting.isNotEmpty()) {
            api.post(
                DemoUser.TRADER,
                "/api/v1/allocation/plans",
                """{"holderEntityId":"${sweden.id}","obligationIds":${ids(
                    waiting,
                )},"solver":"EXACT","idempotencyKey":"demo-plan-se","softReservationHours":720}""",
            )
        }
    }

    /**
     * A generic EU renewable sale. Nordic hydro is the cheapest certificate that qualifies, so the plan delivers it and
     * shows what that gives away against the generic mark (ADR 0035).
     */
    private fun genericSale(entity: DemoEntity): String = api.post(
        DemoUser.BACKOFFICE,
        "/api/v1/allocation/obligations",
        """{"kind":"SALE","holderEntityId":"${entity.id}","criterion":${DemoDesk.criterion("EU_RENEWABLE")},"quantityMwh":200,
           "deliveryWindow":{"start":"${calendar.today}","endExclusive":"${calendar.today.plusDays(30)}"},"deliveryDate":"${calendar.today.plusDays(20)}",
           "sourceRef":"${java.util.UUID.nameUUIDFromBytes("demo-generic-sale".toByteArray())}",
           "targetRegistryCode":"SIMULATOR","targetAccountRef":"SIM-EXT-GENERIC"}""",
    ).id()

    private fun executePlan(entity: DemoEntity, obligationIds: List<String>, key: String) {
        if (obligationIds.isEmpty()) return
        val plan = api.post(
            DemoUser.TRADER,
            "/api/v1/allocation/plans",
            """{"holderEntityId":"${entity.id}","obligationIds":${ids(obligationIds)},"solver":"GREEDY","idempotencyKey":"$key"}""",
        ).id()
        api.post(DemoUser.MIDDLE, "/api/v1/allocation/plans/$plan/approve", "{}")
    }

    /**
     * Future supply (ADR 0038): forecasts for the Finnish hydro plant (one month entered, two loaded from a file; the
     * rest falls back to the load factor), a forward purchase of next year's Nordic hydro, and a sale of that vintage
     * that nothing held can serve. Its plan, left for approval, counts on the purchase and on expected issuance with
     * virtual lines.
     */
    fun futureSupply(finland: DemoEntity, parties: DemoParties) {
        val hydro = finland.deviceGsrns.first()
        val device = api.getList(DemoUser.MIDDLE, "/api/v1/reference/production-devices").first { it["gsrn"] == hydro }.id()
        val next = YearMonth.from(calendar.today).plusMonths(1)
        api.put(DemoUser.MIDDLE, "/api/v1/reference/devices/$device/forecasts/$next", """{"expectedMwh":9200,"p90Mwh":7800}""")
        api.postText(
            DemoUser.MIDDLE,
            "/api/v1/reference/forecasts/import",
            "gsrn,month,expected_mwh,p90_mwh\n" +
                "$hydro,${next.plusMonths(1)},8800,7400\n" +
                "$hydro,${next.plusMonths(2)},8100,6600\n",
        )

        val vintage = calendar.today.year + 1
        val nextYearHydro = """{"type":"all","of":[{"type":"countryInRegion","region":"NORDIC"},
            {"type":"descendantOf","attribute":"ENERGY_SOURCE","code":"HYDRO"},{"type":"vintage","rule":{"type":"productionYear","year":$vintage}}]}"""
        val arrives = LocalDate.of(vintage, 3, 31)
        val seller = parties.getValue("CP-NORDVIND")
        val purchase = api.post(
            DemoUser.TRADER,
            "/api/v1/trades",
            """{"legalEntityId":"${finland.id}","bookId":"${finland.tradingBookId}","counterpartyId":"$seller","productFamily":"GUARANTEE_OF_ORIGIN",
               "instrumentType":"FORWARD","tradeDate":"${calendar.lastClose}","legs":[{"side":"BUY","criterion":$nextYearHydro,
               "quantity":1500,"unit":"MWH","price":0.62,"priceCurrency":"EUR","deliveryAccountId":"${finland.gooAccountId}",
               "counterpartyAccountRef":"SIMULATOR:SIM-EXT-${seller.takeLast(6)}",
               "deliveries":[{"deliveryWindow":{"start":"${arrives.minusDays(30)}","endExclusive":"${arrives.plusDays(1)}"},
                 "deliveryDate":"$arrives","quantity":1500}]}]}""",
        ).id()
        api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", purchase)
        api.post(DemoUser.BACKOFFICE, "/api/v1/trades/$purchase/confirm", """{"confirmationRef":"DEMO-CONF-${purchase.takeLast(8)}"}""")

        val delivery = LocalDate.of(vintage, 6, 15)
        val sale = api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/allocation/obligations",
            """{"kind":"SALE","holderEntityId":"${finland.id}","criterion":$nextYearHydro,"quantityMwh":4000,
               "deliveryWindow":{"start":"${delivery.minusDays(14)}","endExclusive":"${delivery.plusDays(1)}"},"deliveryDate":"$delivery",
               "sourceRef":"${java.util.UUID.nameUUIDFromBytes("demo-future-sale".toByteArray())}",
               "targetRegistryCode":"SIMULATOR","targetAccountRef":"SIM-EXT-FUTURE"}""",
        ).id()
        api.post(
            DemoUser.TRADER,
            "/api/v1/allocation/plans",
            """{"holderEntityId":"${finland.id}","obligationIds":${ids(listOf(sale))},"solver":"GREEDY","idempotencyKey":"demo-plan-fi-future",
               "softReservationHours":720}""",
        )
    }

    /** Union Registry: allowances bought into a trading account, one transfer to the operator account confirmed, one failed. */
    fun registry(finland: DemoEntity) {
        val trading = api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/registry-accounts",
            """{"registryCode":"UNION_REGISTRY","accountNumber":"EU-200-${finland.code}","name":"${finland.code} trading","accountType":"TRADING",
               "holderType":"LEGAL_ENTITY","holderId":"${finland.id}"}""",
        ).id()
        val operator = api.getList(DemoUser.ADMIN, "/api/v1/reference/registry-accounts?holderType=LEGAL_ENTITY&holderId=${finland.id}")
            .first { it["accountType"] == "OPERATOR_HOLDING" }.id()
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/inventory/holdings/movements",
            """{"holderEntityId":"${finland.id}","registryAccountId":"$trading","productCode":"EUA","type":"PURCHASE","quantity":6000,
               "effectiveDate":"${calendar.previousMonth.atDay(15)}","reference":"DEMO-EUA-BUY-1","costPerUnit":70.10,"costCurrency":"EUR"}""",
        )
        val confirmed = allowanceTransfer(trading, operator, 2500, "demo-ur-1")
        api.post(DemoUser.BACKOFFICE, "/api/v1/registry/instructions/$confirmed/confirm", """{"externalRef":"EU-TX-${calendar.today.year}-001184"}""")
        val failed = allowanceTransfer(trading, operator, 800, "demo-ur-2")
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/registry/instructions/$failed/fail",
            """{"reason":"Registry refused: the acquiring account is not on the trusted list"}""",
        )
    }

    private fun allowanceTransfer(from: String, to: String, quantity: Int, key: String): String {
        val id = api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/registry/instructions/allowance-transfers",
            """{"fromAccountId":"$from","productCode":"EUA","quantity":$quantity,"toAccountId":"$to","idempotencyKey":"$key","movementType":"TRANSFER_OUT"}""",
        ).id()
        api.approveTask(DemoUser.MIDDLE, "INSTRUCTION_APPROVAL", id)
        return id
    }

    /**
     * Invoices across their life: Rheinstrom's older sale overdue, Polder's paid in part, Soleil's credited, Vinden's
     * sales and purchase ready for netting, a bank file with an unapplied receipt and a VAT rule waiting for approval.
     * Nordvind and Aurora are left as the operations step made them (the e2e journeys use them).
     */
    fun invoicing(finland: DemoEntity, parties: DemoParties) {
        val overdueDate = calendar.today.minusDays(OVERDUE_DAYS)
        val rheinstrom = parties.getValue("CP-RHEINSTROM")
        desk.deliveredSpot(finland, rheinstrom, "SELL", 220, "0.41", overdueDate)
        invoiceRun(finland, rheinstrom, overdueDate, invoiceDate = overdueDate).forEach { issue(it) }

        val polder = parties.getValue("CP-POLDER")
        desk.deliveredSpot(finland, polder, "SELL", 300, "0.45", calendar.lastClose)
        desk.deliveredSpot(finland, polder, "SELL", 150, "0.46", calendar.lastClose)
        invoiceRun(finland, polder, calendar.today).forEach { invoiceId ->
            issue(invoiceId)
            val invoice = api.get(DemoUser.ACCOUNTANT, "/api/v1/settlement/invoices/$invoiceId") as Map<*, *>
            val part = BigDecimal(invoice["grossTotal"].toString()).divide(BigDecimal(2)).setScale(2, java.math.RoundingMode.DOWN)
            api.post(
                DemoUser.ACCOUNTANT,
                "/api/v1/settlement/payments",
                """{"legalEntityId":"${finland.id}","direction":"INCOMING","amount":$part,"currency":"EUR","valueDate":"${calendar.lastClose}",
                   "bankReference":"DEMO-BANK-PART-1","counterpartyId":"$polder","invoiceIds":["$invoiceId"]}""",
            )
        }

        val soleil = parties.getValue("CP-SOLEIL")
        desk.deliveredSpot(finland, soleil, "SELL", 120, "0.43", calendar.lastClose)
        invoiceRun(finland, soleil, calendar.today).forEach { invoiceId ->
            issue(invoiceId)
            issue(
                api.post(
                    DemoUser.ACCOUNTANT,
                    "/api/v1/settlement/invoices/$invoiceId/credit-note",
                    """{"reason":"Delivered from the wrong vintage; re-invoiced"}""",
                ).id(),
            )
        }

        val vinden = parties.getValue("CP-VINDEN")
        desk.deliveredSpot(finland, vinden, "BUY", 160, "0.37", calendar.lastClose)
        issue(
            api.post(
                DemoUser.ACCOUNTANT,
                "/api/v1/settlement/purchase-invoices",
                """{"legalEntityId":"${finland.id}","counterpartyId":"$vinden","supplierInvoiceRef":"VE-${calendar.today.year}-2231",
                   "invoiceDate":"${calendar.lastClose}","currency":"EUR","netAmount":59.20,"vatAmount":0}""",
            ).id(),
        )
        invoiceRun(finland, vinden, calendar.today).forEach { issue(it) }

        api.postText(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/payments/csv?legalEntityId=${finland.id}",
            "bank_reference,value_date,amount,currency,counterparty,remittance\n" +
                "DEMO-BANK-${calendar.today.year}-0901,${calendar.lastClose},1250.00,EUR,CP-POLDER,Payment on account\n",
        )
        // Proposed by the controller, so it waits for the accountant.
        api.post(
            DemoUser.CONTROLLER,
            "/api/v1/settlement/vat-rules",
            """{"code":"SE-GOO-DOMESTIC","description":"Swedish domestic GoO sales","productFamily":"GUARANTEE_OF_ORIGIN","supplierCountry":"SE",
               "scope":"DOMESTIC","treatment":"STANDARD","rate":25,"validFrom":"${calendar.today.withDayOfMonth(1)}"}""",
        )
    }

    /** A netting statement with Vinden, agreed by a second accountant and settled with the net receipt. */
    fun netting(finland: DemoEntity, parties: DemoParties) {
        val vinden = parties.getValue("CP-VINDEN")
        val statement = api.post(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/netting",
            """{"legalEntityId":"${finland.id}","counterpartyId":"$vinden","currency":"EUR","valueDate":"${calendar.today.plusDays(30)}"}""",
        )
        api.post(DemoUser.CONTROLLER, "/api/v1/settlement/netting/${statement.id()}/agree", """{"counterpartyRef":"VE-NET-0042"}""")
        val net = BigDecimal(statement["netAmount"].toString())
        val payment = api.post(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/payments",
            """{"legalEntityId":"${finland.id}","direction":"${if (net.signum() >= 0) "INCOMING" else "OUTGOING"}","amount":${net.abs()},"currency":"EUR",
               "valueDate":"${calendar.lastClose}","bankReference":"DEMO-BANK-NET-0042","counterpartyId":"$vinden"}""",
        ).id()
        api.post(DemoUser.CONTROLLER, "/api/v1/settlement/netting/${statement.id()}/settle", """{"paymentId":"$payment"}""")
    }

    /** Counterparty confirmations: two matching ours (the trades confirm themselves), one disputed. */
    fun confirmations(finland: DemoEntity, parties: DemoParties) {
        listOf("CP-POLDER" to "0.44", "CP-SOLEIL" to "0.43").forEach { (code, price) ->
            val trade = desk.gooForward(finland, parties.getValue(code), "SELL", "EU_WIND", 180, price, calendar.lastClose)
            api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", trade)
            counterpartyConfirmation(trade, priceOverride = null, reference = "$code-CONF-${trade.takeLast(4)}")
        }
        val disputed = desk.gooForward(finland, parties.getValue("CP-IBERSOL"), "SELL", "EU_SOLAR", 140, "0.40", calendar.lastClose)
        api.approveTask(DemoUser.MIDDLE, "TRADE_APPROVAL", disputed)
        val theirs = counterpartyConfirmation(disputed, priceOverride = BigDecimal("0.36"), reference = "IB-CONF-7731")
        val task = (api.get(DemoUser.BACKOFFICE, "/api/v1/workflow/tasks?subjectRef=$theirs&type=CONFIRMATION_BREAK&status=OPEN") as Map<*, *>)
            .let { (it["items"] as List<*>).filterIsInstance<Map<*, *>>().single() }
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/workflow/tasks/${task["id"]}/reject",
            """{"reason":"Ibersol quotes 0.36; our recording shows 0.40. Disputed with their desk."}""",
        )
    }

    private fun counterpartyConfirmation(tradeId: String, priceOverride: BigDecimal?, reference: String): String {
        val ours = api.getList(DemoUser.MIDDLE, "/api/v1/trades/$tradeId/confirmations").first { it["direction"] == "OURS" }

        @Suppress("UNCHECKED_CAST")
        val terms = (ours["terms"] as Map<String, Any?>).toMutableMap()
        if (priceOverride != null) {
            @Suppress("UNCHECKED_CAST")
            val leg = (terms["legs"] as List<Map<String, Any?>>).single().toMutableMap()
            leg["price"] = priceOverride
            leg["amount"] = priceOverride.multiply(BigDecimal(leg["quantity"].toString())).setScale(2)
            terms["legs"] = listOf(leg)
        }
        return api.postBody(DemoUser.MIDDLE, "/api/v1/confirmations/counterparty", mapOf("terms" to terms, "reference" to reference, "source" to "MANUAL")).id()
    }

    /**
     * The Swedish registry statement: one certificate block the registry shows that we have not booked (accepted, waiting
     * for a second person), and the last lot shorter in the registry (withdrawn on the registry's word).
     */
    fun reconciliation(sweden: DemoEntity) {
        val lots = api.getList(DemoUser.BACKOFFICE, "/api/v1/inventory/lots?holderEntityId=${sweden.id}")
            .filter { it["registryAccountId"] == sweden.gooAccountId && it["status"] in setOf("HELD", "RESERVED", "IN_TRANSFER") }
            .sortedBy { (it["range"] as Map<*, *>)["start"].toString().toLong() }
        val rows = lots.mapIndexed { index, lot ->
            val range = lot["range"] as Map<*, *>
            val period = lot["productionPeriod"] as Map<*, *>
            val end = range["end"].toString().toLong() - if (index == lots.lastIndex) MISSING_CERTIFICATES else 0
            "${lot["issuanceRef"]},${range["start"]},$end,${lot["deviceGsrn"]},${period["start"]},${period["endExclusive"]}"
        }
        val last = lots.last()
        val period = last["productionPeriod"] as Map<*, *>
        val extra = "SIM-UNBOOKED-77,900001,900120,${last["deviceGsrn"]},${period["start"]},${period["endExclusive"]}"
        val csv = (
            listOf(
                "issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end",
            ) + rows + extra
        ).joinToString("\n", postfix = "\n")
        api.postText(DemoUser.BACKOFFICE, "/api/v1/registry/statements/csv?registryAccountId=${sweden.gooAccountId}&statementDate=${calendar.today}", csv)
        val breaks = api.getList(DemoUser.BACKOFFICE, "/api/v1/inventory/breaks?registryAccountId=${sweden.gooAccountId}")
        breaks.firstOrNull { it["type"] == "MISSING_INTERNALLY" }?.let {
            api.post(
                DemoUser.BACKOFFICE,
                "/api/v1/registry/breaks/${it.id()}/accept",
                """{"note":"Issued to us after the statement cut-off; accept and book next week"}""",
            )
        }
        breaks.firstOrNull { it["type"] == "MISSING_IN_REGISTRY" }?.let {
            api.post(
                DemoUser.BACKOFFICE,
                "/api/v1/registry/breaks/${it.id()}/withdraw-lot",
                """{"note":"Registry corrected the issuance; the 20 MWh were never issued"}""",
            )
        }
    }

    /** ETS: last year verified and surrendered for Finland, a Swedish installation with this year's estimates and a verified figure waiting. */
    fun ets(finland: DemoEntity, sweden: DemoEntity) {
        val lastYear = calendar.today.year - 1
        val fiInstallation = (api.getList(DemoUser.MIDDLE, "/api/v1/ets/installations?legalEntityId=${finland.id}")).single()
        val fiPath = "/api/v1/ets/installations/${fiInstallation.id()}"
        val verified = api.post(
            DemoUser.RISK,
            "$fiPath/verified-emissions",
            """{"complianceYear":$lastYear,"quantityTco2e":13420,"verifier":"Nordic Verification Oy (fictitious)",
               "verifiedOn":"${lastYear + 1}-03-12","documentRef":"VER-$lastYear-FI-001"}""",
        ).id()
        api.approveTask(DemoUser.MIDDLE, "VERIFIED_EMISSIONS_APPROVAL", verified)
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/inventory/holdings/movements",
            """{"holderEntityId":"${finland.id}","registryAccountId":"${fiInstallation["registryAccountId"]}","productCode":"EUA","type":"FREE_ALLOCATION",
               "quantity":13420,"effectiveDate":"$lastYear-02-28","complianceYear":$lastYear}""",
        )
        val request = api.post(DemoUser.BACKOFFICE, "$fiPath/surrenders", """{"complianceYear":$lastYear}""")
        val instruction = request["instructionId"]?.toString()
            ?: api.getList(DemoUser.BACKOFFICE, "/api/v1/registry/instructions?type=SURRENDER").first().id()
        api.approveTask(DemoUser.MIDDLE, "INSTRUCTION_APPROVAL", instruction)
        api.post(DemoUser.BACKOFFICE, "/api/v1/registry/instructions/$instruction/confirm", """{"externalRef":"EU-SUR-$lastYear-FI-0007"}""")

        val year = calendar.today.year
        val account = api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/registry-accounts",
            """{"registryCode":"UNION_REGISTRY","accountNumber":"EU-100-${sweden.code}","name":"${sweden.code} operator holding","accountType":"OPERATOR_HOLDING",
               "holderType":"LEGAL_ENTITY","holderId":"${sweden.id}"}""",
        ).id()
        val installation = api.post(
            DemoUser.MIDDLE,
            "/api/v1/ets/installations",
            """{"legalEntityId":"${sweden.id}","kind":"INSTALLATION","eutlId":"SE-${sweden.code}-001","name":"Värme CHP plant (${sweden.code})","country":"SE",
               "activityCode":"20","permitId":"SE-PERMIT-DEMO-1","registryAccountId":"$account","validFrom":"$lastYear-01-01"}""",
        ).id()
        val sePath = "/api/v1/ets/installations/$installation"
        api.put(DemoUser.MIDDLE, "$sePath/free-allocation/$year", """{"allocated":5200}""")
        api.post(DemoUser.RISK, "$sePath/emissions", """{"complianceYear":$year,"kind":"FORECAST","quantityTco2e":9100}""")
        for (month in 1 until calendar.today.monthValue) {
            api.post(
                DemoUser.RISK,
                "$sePath/emissions",
                """{"complianceYear":$year,"kind":"MONTHLY_ESTIMATE","periodMonth":$month,"quantityTco2e":${SE_MONTHLY[month - 1]}}""",
            )
        }
        // Waiting for middle office or the second analyst.
        api.post(
            DemoUser.RISK,
            "$sePath/verified-emissions",
            """{"complianceYear":$lastYear,"quantityTco2e":8740,"verifier":"Nordic Verification Oy (fictitious)",
               "verifiedOn":"$year-03-20","documentRef":"VER-$lastYear-SE-001"}""",
        )
    }

    /** Swedish limits with an open breach; a Finnish credit breach already acknowledged; a valuation waiting for sign-off. */
    fun risk(finland: DemoEntity, sweden: DemoEntity, parties: DemoParties) {
        approvedLimit(
            """{"legalEntityId":"${sweden.id}","type":"CREDIT_EXPOSURE","scope":{"counterpartyId":"${parties.getValue("CP-POLDER")}"},"amount":30000,
               "currency":"EUR","warningPct":80,"reason":"Annual credit review"}""",
        )
        approvedLimit(
            """{"legalEntityId":"${sweden.id}","type":"POSITION_NET","scope":{"productFamily":"GUARANTEE_OF_ORIGIN"},"amount":1000,
               "reason":"Board mandate: open GoO position"}""",
        )
        approvedLimit(
            """{"legalEntityId":"${finland.id}","type":"CREDIT_EXPOSURE","scope":{"counterpartyId":"${parties.getValue("CP-VINDEN")}"},"amount":100,
               "currency":"EUR","warningPct":80,"reason":"Provisional limit until the guarantee arrives"}""",
        )
        listOf(finland, sweden).forEach { api.post(DemoUser.RISK, "/api/v1/risk/measure?legalEntityId=${it.id}") }
        api.getList(DemoUser.RISK, "/api/v1/risk/breaches?legalEntityId=${finland.id}").firstOrNull()?.let { breach ->
            val task = (api.get(DemoUser.RISK, "/api/v1/workflow/tasks?type=LIMIT_BREACH&subjectRef=${breach.id()}&status=OPEN") as Map<*, *>)
                .let { (it["items"] as List<*>).filterIsInstance<Map<*, *>>().firstOrNull() }
            task?.let {
                api.post(DemoUser.RISK, "/api/v1/workflow/tasks/${it["id"]}/acknowledge", """{"reason":"Guarantee requested from Vinden; trading paused"}""")
            }
        }
        val run = api.postRun(
            DemoUser.RISK,
            "/api/v1/valuation/runs",
            """{"legalEntityId":"${sweden.id}","valuationDate":"${calendar.lastClose}","kind":"EOD"}""",
        )
        api.post(DemoUser.RISK, "/api/v1/valuation/runs/$run/submit")
    }

    private fun approvedLimit(json: String) {
        val limit = api.post(DemoUser.RISK, "/api/v1/risk/limits", json).id()
        api.approveTask(DemoUser.RISK2, "LIMIT_CHANGE", limit)
    }

    /**
     * Ledger work besides the automatic postings: an accrual for an unbilled broker fee, an entry posted by mistake and
     * reversed, the ERP's balances for the month before last with one difference to explain, and a policy change and a
     * posting rule change waiting for a second accountant.
     */
    fun ledger(finland: DemoEntity) {
        val accounts = api.getList(DemoUser.ACCOUNTANT, "/api/v1/ledger/accounts?legalEntityId=${finland.id}")
        val expense = accounts.first { it["type"] == "EXPENSE" }["code"]
        val liability = accounts.first { it["type"] == "LIABILITY" }["code"]

        fun entry(description: String, amount: String) = api.post(
            DemoUser.ACCOUNTANT,
            "/api/v1/ledger/entries",
            """{"legalEntityId":"${finland.id}","postingDate":"${calendar.today}","description":"$description","currency":"EUR",
               "lines":[{"accountCode":"$expense","debit":$amount,"credit":0,"memo":"$description"},
                        {"accountCode":"$liability","debit":0,"credit":$amount,"memo":"$description"}]}""",
        ).id()
        entry("Accrued broker fees, Nordic Carbon Brokers", "1250.00")
        val mistake = entry("Accrual booked twice", "1250.00")
        api.post(DemoUser.ACCOUNTANT, "/api/v1/ledger/entries/$mistake/reverse", """{"reason":"Duplicate of the broker fee accrual"}""")

        val period = calendar.previousMonth.minusMonths(1)
        val balances = api.getList(DemoUser.ACCOUNTANT, "/api/v1/ledger/trial-balance?legalEntityId=${finland.id}&period=$period")
            .filter { it["glMapping"] != null }
            .groupBy { it["glMapping"].toString() }
            .map { (gl, rows) -> gl to rows.sumOf { BigDecimal(it["debit"].toString()) - BigDecimal(it["credit"].toString()) } }
        if (balances.isNotEmpty()) {
            // The ERP holds a manual journal we never received: one account differs by 125.00.
            val lines = balances.mapIndexed { index, (gl, balance) ->
                val shown = if (index == 0) balance + BigDecimal("125.00") else balance
                """{"glAccount":"$gl","balance":${shown.setScale(2, java.math.RoundingMode.HALF_UP)},"currency":"EUR"}"""
            }
            api.put(DemoUser.ACCOUNTANT, "/api/v1/ledger/gl-balances/$period?legalEntityId=${finland.id}", lines.joinToString(",", "[", "]"))
        }

        api.post(
            DemoUser.CONTROLLER,
            "/api/v1/ledger/period-end/policy-changes",
            """{"legalEntityId":"${finland.id}","kind":"MTM","reason":"Hedge documentation in place: own-use forwards through OCI",
               "bookType":"OWN_USE","treatment":"POST_OCI"}""",
        )
        api.getList(DemoUser.CONTROLLER, "/api/v1/ledger/rules").firstOrNull()?.let { rule ->
            val change = rule.filterKeys { it in RULE_FIELDS }.toMutableMap()
            change["description"] = "${rule["description"] ?: rule["ruleCode"]} (reviewed ${calendar.today.year})"
            api.put(DemoUser.CONTROLLER, "/api/v1/ledger/rules", com.pianonurmi.etrm.platform.json.Json.mapper.writeValueAsString(change))
        }
    }

    /** EMIR: one report marked in error; the activity report exported three times by different readers. */
    fun reporting(finland: DemoEntity) {
        val trades = api.getList(DemoUser.MIDDLE, "/api/v1/trades?legalEntityId=${finland.id}")
            .filter { it["productFamily"] == "EMISSION_ALLOWANCE" && it["instrumentType"] == "FORWARD" && it["status"] == "CONFIRMED" }
        trades.firstOrNull()?.let {
            api.post(
                DemoUser.MIDDLE,
                "/api/v1/reports/emir/trades/${it.id()}/error",
                """{"reason":"Rejected by the trade repository: counterparty LEI lapsed"}""",
            )
        }
        val range = "legalEntityId=${finland.id}&from=${calendar.historyStart}&to=${calendar.today}&format=json"
        listOf(DemoUser.MIDDLE, DemoUser.ACCOUNTANT, DemoUser.AUDITOR).forEach { api.get(it, "/api/v1/reports/EMIR_TRADE_ACTIVITY?$range") }
    }

    private fun invoiceRun(entity: DemoEntity, counterpartyId: String, upTo: LocalDate, invoiceDate: LocalDate? = null): List<String> =
        api.postForList(
            DemoUser.ACCOUNTANT,
            "/api/v1/settlement/invoice-runs",
            """{"legalEntityId":"${entity.id}","upTo":"$upTo","counterpartyId":"$counterpartyId"${invoiceDate?.let { ""","invoiceDate":"$it"""" } ?: ""}}""",
        ).map { it.id() }

    private fun issue(invoiceId: String) {
        api.post(DemoUser.ACCOUNTANT, "/api/v1/settlement/invoices/$invoiceId/submit")
        api.approveTask(DemoUser.CONTROLLER, "INVOICE_APPROVAL", invoiceId)
    }

    private fun ids(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }

    private data class Address(val line1: String, val postalCode: String, val city: String, val country: String, val vat: String)

    private companion object {
        const val OVERDUE_DAYS = 45L
        val RULE_FIELDS = setOf("legalEntityId", "ruleCode", "eventType", "conditions", "lines", "description", "priority", "effectiveFrom", "active")
        const val MISSING_CERTIFICATES = 20L
        val SE_MONTHLY = listOf(1200, 1100, 950, 700, 520, 330, 260, 300, 480, 760, 980, 1150)
        val ADDRESSES = mapOf(
            "CP-NORDVIND" to Address("Bryggen 12", "5003", "Bergen", "NO", "NO999999999MVA"),
            "CP-RHEINSTROM" to Address("Rheinufer 40", "50668", "Köln", "DE", "DE999999999"),
            "CP-AURORA" to Address("Revontulentie 5", "00100", "Helsinki", "FI", "FI99999999"),
            "CP-VINDEN" to Address("Havnegade 7", "1058", "København", "DK", "DK99999999"),
            "CP-POLDER" to Address("Dijkweg 21", "1013", "Amsterdam", "NL", "NL999999999B01"),
            "CP-SOLEIL" to Address("Rue du Soleil 3", "75002", "Paris", "FR", "FR99999999999"),
            "CP-IBERSOL" to Address("Calle del Sol 9", "28001", "Madrid", "ES", "ESX9999999X"),
            "CP-NORDBROKER" to Address("Strandvägen 1", "11451", "Stockholm", "SE", "SE999999999901"),
            "CP-CLEARING" to Address("Börsenplatz 2", "60313", "Frankfurt", "DE", "DE888888888"),
        )
    }
}
