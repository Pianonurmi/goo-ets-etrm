package com.pianonurmi.etrm.demo

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Risk limits, credit support and an EU ETS installation for the Finnish entity: active limits measured against
 * the demo trades, one limit change waiting for the second risk analyst, and an installation with free allocation,
 * allowances and this year's emission estimates.
 */
class DemoCompliance(private val api: DemoClient, private val calendar: DemoCalendar) {
    fun seed(entity: DemoEntity, counterparties: List<String>) {
        val (nordvind, rheinstrom) = counterparties
        risk(entity, nordvind, rheinstrom)
        ets(entity)
    }

    private fun risk(entity: DemoEntity, nordvind: String, rheinstrom: String) {
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"CREDIT_EXPOSURE","scope":{"counterpartyId":"$nordvind"},"amount":50000,
               "currency":"EUR","warningPct":80,"reason":"Annual credit review"}""",
        )
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"TENOR_MAX_MONTHS","scope":{"productFamily":"GUARANTEE_OF_ORIGIN"},"amount":24,
               "reason":"Board mandate: GoO forwards up to two years"}""",
        )
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"COMPLIANCE_SHORT","scope":{"allowanceProductCode":"EUA","complianceYear":${calendar.today.year}},
               "amount":5000,"reason":"Board mandate: EUA short against the obligation"}""",
        )
        // Waits in the second analyst's inbox.
        api.post(
            DemoUser.RISK,
            "/api/v1/risk/limits",
            """{"legalEntityId":"${entity.id}","type":"STOP_LOSS_MTD","scope":{},"amount":25000,"currency":"EUR","reason":"New desk stop loss"}""",
        )
        val guarantee = api.post(
            DemoUser.RISK,
            "/api/v1/risk/credit-support",
            """{"legalEntityId":"${entity.id}","counterpartyId":"$rheinstrom","kind":"GUARANTEE_RECEIVED","amount":20000,"currency":"EUR",
               "validFrom":"${calendar.today.withDayOfYear(1)}","reference":"DEMO-PG-001"}""",
        ).id()
        api.approveTask(DemoUser.RISK2, "CREDIT_SUPPORT_CHANGE", guarantee)
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"CREDIT_EXPOSURE","scope":{"counterpartyId":"$rheinstrom"},"amount":${rheinstromLimit(
                entity,
                rheinstrom,
            )},
               "currency":"EUR","warningPct":75,"reason":"Annual credit review"}""",
        )
        api.post(DemoUser.RISK, "/api/v1/risk/measure?legalEntityId=${entity.id}")
    }

    /**
     * Rheinstrom's exposure follows the seed date (prices and which months it bought in), so a fixed limit breaks the
     * seed on some days: later steps sell to Rheinstrom and middle office approves. The limit sits at about 80 % of
     * today's exposure, net of the guarantee: in warning (75 %), with room for those sales.
     */
    private fun rheinstromLimit(entity: DemoEntity, rheinstrom: String): Long {
        val exposure = api.getList(DemoUser.RISK, "/api/v1/risk/exposures?legalEntityId=${entity.id}")
            .firstOrNull { it["counterpartyId"] == rheinstrom }?.get("exposure")?.let { BigDecimal(it.toString()) } ?: BigDecimal.ZERO
        val fourFifths = exposure.multiply(BigDecimal(5)).divide(BigDecimal(4), 0, RoundingMode.CEILING).toLong()
        return maxOf(MIN_RHEINSTROM_LIMIT, (fourFifths + LIMIT_STEP - 1) / LIMIT_STEP * LIMIT_STEP)
    }

    /**
     * GoO limits (ADR 0040) on the book as it stands at the end of the seed: a Nordic hydro concentration limit set
     * just above today's free certificates, so it shows in warning; an expiry limit; a basis limit in EUR; and an
     * expiry limit on last year's vintage alone (increment 10).
     */
    fun gooLimits(entity: DemoEntity) {
        @Suppress("UNCHECKED_CAST")
        val nodes = (
            api.get(
                DemoUser.RISK,
                "/api/v1/allocation/portfolio?holderEntityId=${entity.id}",
            ) as Map<String, Any?>
        )["nodes"] as List<Map<String, Any?>>
        val free = nodes.firstOrNull { it["bucketCode"] == "NORDIC_HYDRO" }?.get("freeMwh")?.toString()?.toLong() ?: 0L
        // About 90 % used: above the 80 % warning level, below the limit.
        val amount = if (free > 0) (free * 10 + 8) / 9 else 1000
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"GOO_CONCENTRATION","scope":{"bucketCode":"NORDIC_HYDRO"},"amount":$amount,"warningPct":80,
               "reason":"Desk mandate: unsold Nordic hydro"}""",
        )
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"GOO_EXPIRY","scope":{"horizonDays":90},"amount":5000,
               "reason":"Certificates expiring unsold within a quarter"}""",
        )
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"GOO_BASIS","scope":{},"amount":5000,"currency":"EUR",
               "reason":"Give-away on generic sales"}""",
        )
        approvedLimit(
            """{"legalEntityId":"${entity.id}","type":"GOO_EXPIRY","scope":{"horizonDays":180,"productionYear":${calendar.today.year - 1}},
               "amount":2000,"reason":"Last year's vintage expiring unsold within half a year"}""",
        )
        api.post(DemoUser.RISK, "/api/v1/risk/measure?legalEntityId=${entity.id}")
    }

    private fun approvedLimit(json: String) {
        val limit = api.post(DemoUser.RISK, "/api/v1/risk/limits", json).id()
        api.approveTask(DemoUser.RISK2, "LIMIT_CHANGE", limit)
    }

    private fun ets(entity: DemoEntity) {
        val year = calendar.today.year
        val account = api.post(
            DemoUser.ADMIN,
            "/api/v1/reference/registry-accounts",
            """{"registryCode":"UNION_REGISTRY","accountNumber":"EU-100-${entity.code}","name":"${entity.code} operator holding",
               "accountType":"OPERATOR_HOLDING","holderType":"LEGAL_ENTITY","holderId":"${entity.id}"}""",
        ).id()
        val installation = api.post(
            DemoUser.MIDDLE,
            "/api/v1/ets/installations",
            """{"legalEntityId":"${entity.id}","kind":"INSTALLATION","eutlId":"FI-${entity.code}-001","name":"Lämpö CHP plant (${entity.code})",
               "country":"FI","activityCode":"20","permitId":"FI-PERMIT-DEMO-1","registryAccountId":"$account","validFrom":"$year-01-01"}""",
        ).id()
        val path = "/api/v1/ets/installations/$installation"
        api.put(DemoUser.MIDDLE, "$path/free-allocation/$year", """{"allocated":8000}""")
        api.post(
            DemoUser.BACKOFFICE,
            "/api/v1/inventory/holdings/movements",
            """{"holderEntityId":"${entity.id}","registryAccountId":"$account","productCode":"EUA","type":"FREE_ALLOCATION","quantity":8000,
               "effectiveDate":"$year-02-28","complianceYear":$year}""",
        )
        api.post(DemoUser.RISK, "$path/emissions", """{"complianceYear":$year,"kind":"FORECAST","quantityTco2e":14000}""")
        for (month in 1 until calendar.today.monthValue) {
            api.post(
                DemoUser.RISK,
                "$path/emissions",
                """{"complianceYear":$year,"kind":"MONTHLY_ESTIMATE","periodMonth":$month,"quantityTco2e":${MONTHLY[month - 1]}}""",
            )
        }
    }

    companion object {
        /** A heating plant burns more in winter. */
        private val MONTHLY = listOf(1900, 1700, 1500, 1100, 800, 500, 400, 450, 700, 1100, 1500, 1800)

        /** Rheinstrom's credit limit in EUR: at least this, in steps of [LIMIT_STEP]. */
        private const val MIN_RHEINSTROM_LIMIT = 40_000L
        private const val LIMIT_STEP = 1_000L
    }
}
