package com.pianonurmi.etrm.reporting.application

import com.pianonurmi.etrm.allocation.api.PortfolioValueQuery
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceQuery
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.ledger.api.GlInterfaceQuery
import com.pianonurmi.etrm.ledger.api.HedgeQuery
import com.pianonurmi.etrm.ledger.api.LedgerQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.reporting.api.ReportParameter
import com.pianonurmi.etrm.reporting.api.ReportResult
import com.pianonurmi.etrm.reporting.domain.AncillaryCategory
import com.pianonurmi.etrm.reporting.domain.ReportDefinition
import com.pianonurmi.etrm.reporting.domain.ReportParameters
import com.pianonurmi.etrm.reporting.domain.ReportingRepository
import com.pianonurmi.etrm.risk.api.RiskQuery
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

private val ENTITY = ReportParameter("legalEntityId", "uuid", true, "Legal entity")
private val DATE = ReportParameter("date", "date", false, "As of (default today)")
private val FROM = ReportParameter("from", "date", true, "First date")
private val TO = ReportParameter("to", "date", true, "Last date (inclusive)")
private val YEAR = ReportParameter("year", "int", false, "Calendar or compliance year (default this year)")

private val FINANCE = setOf(Role.ACCOUNTANT, Role.AUDITOR)
private val RISK = setOf(Role.RISK, Role.MIDDLE_OFFICE, Role.AUDITOR)
private val OPERATIONS = setOf(Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK, Role.AUDITOR)
private val COMPLIANCE = setOf(Role.MIDDLE_OFFICE, Role.RISK, Role.ACCOUNTANT, Role.AUDITOR)

/** The report catalogue (ADR 0023). Each report reads other modules' APIs, which apply their own access rules too. */
@Component
class ReportCatalogue(
    private val referenceData: ReferenceDataQuery,
    private val trades: TradeQuery,
    private val valuations: ValuationQuery,
    private val lots: LotQuery,
    private val risk: RiskQuery,
    private val compliance: EtsComplianceQuery,
    private val ledger: LedgerQuery,
    private val gl: GlInterfaceQuery,
    private val fxRates: FxRates,
    private val repository: ReportingRepository,
    private val clock: Clock,
    /** Allocation's portfolio value, for the give-away report; absent where allocation is not wired (tests). */
    private val giveAway: PortfolioValueQuery? = null,
    /** Cash flow hedges, for the effectiveness report; absent where the ledger's hedges are not wired (tests). */
    private val hedges: HedgeQuery? = null,
) {
    private class Definition(
        override val code: String,
        override val title: String,
        override val description: String,
        override val roles: Set<Role>,
        override val parameters: List<ReportParameter>,
        val columns: List<String>,
        val rows: (ReportParameters) -> List<Map<String, Any?>>,
    ) : ReportDefinition {
        override fun run(parameters: ReportParameters): ReportResult = ReportResult(code, columns, rows(parameters))
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    val definitions: List<ReportDefinition> = listOf(
        Definition(
            "MTM_BY_TRADE",
            "MtM by trade",
            "Official valuation of each open delivery schedule",
            RISK + FINANCE,
            listOf(ENTITY, DATE),
            listOf(
                "tradeNumber",
                "legNo",
                "bookType",
                "counterpartyId",
                "productKey",
                "deliveryDate",
                "quantity",
                "contractPrice",
                "marketPrice",
                "mtm",
                "mtmBase",
                "status",
            ),
        ) { p ->
            val run = valuations.official(p.uuid("legalEntityId"), p.date("date", today()), orEarlier = true)
            run?.let { valuations.results(it.id) }.orEmpty().map {
                mapOf(
                    "tradeNumber" to it.tradeNumber,
                    "legNo" to it.legNo,
                    "bookType" to it.bookType,
                    "counterpartyId" to it.counterpartyId,
                    "productKey" to it.productKey,
                    "deliveryDate" to it.deliveryDate,
                    "quantity" to it.quantity,
                    "contractPrice" to it.contractPrice,
                    "marketPrice" to it.marketPrice,
                    "mtm" to it.mtm,
                    "mtmBase" to it.mtmBase,
                    "status" to it.status,
                )
            }
        },
        Definition(
            "OPTION_GREEKS",
            "Option greeks",
            "Options of the official valuation by underlying future and expiry: delta in tonnes, gamma, and vega per volatility point (ADR 0047)",
            RISK + FINANCE,
            listOf(ENTITY, DATE),
            listOf("underlyingContractCode", "expiryDate", "quantity", "deltaTonnes", "gamma", "vega", "mtm", "currency"),
        ) { p ->
            val run = valuations.official(p.uuid("legalEntityId"), p.date("date", today()), orEarlier = true)
            run?.let { valuations.results(it.id) }.orEmpty()
                .filter { it.option != null }
                .groupBy { Triple(it.option!!.underlyingContractCode, it.deliveryDate, it.contractCurrency) }
                .toSortedMap(compareBy({ it.second }, { it.first }, { it.third }))
                .map { (key, rows) ->
                    fun total(of: (ValuationResultView) -> BigDecimal) = rows.fold(BigDecimal.ZERO) { sum, r -> sum + of(r) }
                    mapOf(
                        "underlyingContractCode" to key.first,
                        "expiryDate" to key.second,
                        "quantity" to total { it.quantity },
                        "deltaTonnes" to total { it.quantity * it.option!!.delta }.setScale(2, RoundingMode.HALF_EVEN),
                        "gamma" to total { it.quantity * it.option!!.gamma }.setScale(6, RoundingMode.HALF_EVEN),
                        "vega" to total { it.quantity * it.option!!.vega }.setScale(2, RoundingMode.HALF_EVEN),
                        "mtm" to total { it.mtm ?: BigDecimal.ZERO },
                        "currency" to key.third,
                    )
                }
        },
        Definition(
            "PNL_DAILY",
            "Daily P&L",
            "MtM of each official valuation and its change from the previous one",
            RISK + FINANCE,
            listOf(ENTITY, FROM, TO),
            listOf("valuationDate", "mtmBase", "change", "currency"),
        ) { p ->
            val runs = valuations.runs(p.uuid("legalEntityId"), p.date("from"), p.date("to")).filter {
                it.status == RunStatus.OFFICIAL
            }.sortedBy { it.valuationDate }
            runs.mapIndexed { i, run ->
                mapOf(
                    "valuationDate" to run.valuationDate,
                    "mtmBase" to run.totalMtmBase,
                    "change" to runs.getOrNull(i - 1)?.let { run.totalMtmBase - it.totalMtmBase },
                    "currency" to run.baseCurrency,
                )
            }
        },
        Definition(
            "PNL_EXPLAIN",
            "P&L explain",
            "Change in MtM between two official valuations by component",
            RISK + FINANCE,
            listOf(ENTITY, FROM, TO),
            listOf("component", "amountBase", "currency"),
        ) { p ->
            val explain = valuations.explain(p.uuid("legalEntityId"), p.date("from"), p.date("to"))
            explain?.components.orEmpty().map { (component, amount) ->
                mapOf("component" to component, "amountBase" to amount, "currency" to explain!!.baseCurrency)
            }
        },
        Definition(
            "POSITION_BY_BOOK",
            "Position by book",
            "Open quantity and MtM by book and product from the official valuation",
            RISK + FINANCE,
            listOf(ENTITY, DATE),
            listOf("book", "bookType", "productKey", "quantity", "mtmBase"),
        ) { p ->
            val run = valuations.official(p.uuid("legalEntityId"), p.date("date", today()), orEarlier = true)
            run?.let { valuations.results(it.id) }.orEmpty().groupBy { it.bookId to it.productKey }.map { (key, rows) ->
                mapOf(
                    "book" to (referenceData.book(key.first)?.code ?: key.first.toString()),
                    "bookType" to rows.first().bookType,
                    "productKey" to key.second,
                    "quantity" to rows.sumOf { it.quantity },
                    "mtmBase" to rows.mapNotNull { it.mtmBase }.fold(BigDecimal.ZERO, BigDecimal::add),
                )
            }.sortedWith(compareBy({ it["book"].toString() }, { it["productKey"].toString() }))
        },
        Definition(
            "GOO_EXPIRY",
            "Certificate expiry ladder",
            "Live certificates by month of expiry",
            OPERATIONS + FINANCE,
            listOf(ENTITY, ReportParameter("months", "int", false, "Months ahead (18)")),
            listOf("month", "mwh"),
        ) { p ->
            lots.expiryLadder(p.uuid("legalEntityId"), today(), p.int("months", DEFAULT_MONTHS)).map { (month, mwh) ->
                mapOf("month" to YearMonth.from(month).toString(), "mwh" to mwh)
            }
        },
        Definition(
            "GOO_GIVE_AWAY",
            "GoO give-away",
            "Sales of the plans approved in the period, as valued then: certificates worth more than the sale's own product " +
                "hand over the difference (ADR 0035)",
            RISK + FINANCE + Role.MIDDLE_OFFICE,
            listOf(ENTITY, FROM, TO),
            listOf("approvedAt", "planId", "tradeRef", "deliveryDate", "saleBucket", "vintage", "mwh", "deliveredValueEur", "saleMarkEur", "giveAwayEur"),
        ) { p ->
            val query = giveAway ?: return@Definition emptyList()
            query.giveAwayLines(p.uuid("legalEntityId"), p.date("from"), p.date("to").plusDays(1)).map {
                mapOf(
                    "approvedAt" to it.approvedAt.toString(),
                    "planId" to it.planId,
                    "tradeRef" to it.tradeRef,
                    "deliveryDate" to it.deliveryDate,
                    "saleBucket" to it.saleBucketCode,
                    "vintage" to it.vintageYear,
                    "mwh" to it.mwh,
                    "deliveredValueEur" to it.deliveredValueEur,
                    "saleMarkEur" to it.saleMarkEur,
                    "giveAwayEur" to it.giveAwayEur,
                )
            }
        },
        Definition(
            "HEDGE_EFFECTIVENESS",
            "Hedge effectiveness",
            "Cash flow hedges measured on each official valuation in the period: instruments' value and change since " +
                "designation, the hypothetical derivative, the reserve and ineffectiveness by the lower of (ADR 0045)",
            FINANCE + Role.RISK,
            listOf(ENTITY, FROM, TO),
            listOf("hedge", "status", "valuationDate", "instrumentValue", "instrumentChange", "hypotheticalValue", "effective", "ineffective", "offsetRatio"),
        ) { p ->
            val query = hedges ?: return@Definition emptyList()
            val from = p.date("from")
            val to = p.date("to")
            query.hedges(p.uuid("legalEntityId")).flatMap { hedge ->
                query.hedge(hedge.id).measurements.filter { !it.valuationDate.isBefore(from) && !it.valuationDate.isAfter(to) }.map {
                    mapOf(
                        "hedge" to hedge.number,
                        "status" to hedge.status.name,
                        "valuationDate" to it.valuationDate,
                        "instrumentValue" to it.instrumentValue,
                        "instrumentChange" to it.instrumentChange,
                        "hypotheticalValue" to it.hypotheticalValue,
                        "effective" to it.effective,
                        "ineffective" to it.ineffective,
                        "offsetRatio" to it.offsetRatio,
                    )
                }
            }
        },
        Definition(
            "CREDIT_EXPOSURE",
            "Credit exposure",
            "Current exposure per counterparty and its components",
            RISK,
            listOf(ENTITY),
            listOf("counterparty", "mtm", "receivables", "payablesNetted", "pfe", "collateral", "exposure", "currency", "measuredAt"),
        ) { p ->
            risk.exposures(p.uuid("legalEntityId")).map {
                mapOf(
                    "counterparty" to (referenceData.counterparty(it.counterpartyId)?.code ?: it.counterpartyId.toString()),
                    "mtm" to it.mtm,
                    "receivables" to it.receivables,
                    "payablesNetted" to it.payablesNetted,
                    "pfe" to it.pfe,
                    "collateral" to it.collateral,
                    "exposure" to it.exposure,
                    "currency" to it.currency,
                    "measuredAt" to it.measuredAt,
                )
            }
        },
        Definition(
            "LIMIT_UTILISATION",
            "Limit utilisation",
            "Active limits with their latest measurement",
            RISK,
            listOf(ENTITY),
            listOf("limitId", "type", "scope", "amount", "unit", "value", "utilisationPct", "status", "measuredAt"),
        ) { p ->
            risk.limits(p.uuid("legalEntityId")).map { limit ->
                val u = risk.utilisation(limit.id)
                mapOf(
                    "limitId" to limit.id,
                    "type" to limit.type,
                    "scope" to limit.scope.toString(),
                    "amount" to limit.amount,
                    "unit" to limit.currency,
                    "value" to u?.value,
                    "utilisationPct" to u?.utilisation,
                    "status" to u?.status,
                    "measuredAt" to u?.measuredAt,
                )
            }
        },
        Definition(
            "ETS_COMPLIANCE_STATUS",
            "EU ETS compliance status",
            "Emissions, surrenders and status per installation for a compliance year",
            COMPLIANCE,
            listOf(ENTITY, YEAR),
            listOf(
                "installation",
                "eutlId",
                "emissions",
                "basis",
                "surrendered",
                "outstanding",
                "held",
                "freeAllocationOutstanding",
                "surrenderDeadline",
                "status",
            ),
        ) { p ->
            compliance.entityCompliance(p.uuid("legalEntityId"), p.int("year", today().year)).installations.map {
                mapOf(
                    "installation" to it.installation.name,
                    "eutlId" to it.installation.eutlId,
                    "emissions" to it.emissions,
                    "basis" to it.emissionsBasis,
                    "surrendered" to it.surrendered,
                    "outstanding" to it.outstanding,
                    "held" to it.held,
                    "freeAllocationOutstanding" to it.freeAllocation?.outstanding,
                    "surrenderDeadline" to it.surrenderDeadline,
                    "status" to it.status,
                )
            }
        },
        Definition(
            "EMIR_TRADE_ACTIVITY",
            "EMIR trade activity",
            "EMIR events recorded in the period with their report fields (field layout not verified)",
            COMPLIANCE,
            listOf(ENTITY, FROM, TO),
            EMIR_COLUMNS,
        ) { p ->
            repository.emirEvents(p.uuid("legalEntityId"), p.date("from"), p.date("to").plusDays(1)).map { e ->
                mapOf("tradeNumber" to e.tradeNumber, "tradeVersion" to e.tradeVersion, "recordedAt" to e.recordedAt) + EMIR_FIELDS.associateWith {
                    e.fields[it]
                }
            }
        },
        Definition(
            "MIFID_ANCILLARY_ACTIVITY",
            "MiFID II ancillary activity",
            "Emission allowance trades of a year by purpose, notional in EUR",
            COMPLIANCE,
            listOf(ENTITY, YEAR),
            listOf("category", "instrument", "trades", "notionalEur"),
        ) { p -> ancillary(p.uuid("legalEntityId"), p.int("year", today().year)) },
        Definition(
            "GL_TRIAL_BALANCE",
            "Trial balance",
            "Movements of a period per account in base currency",
            FINANCE,
            listOf(ENTITY, ReportParameter("period", "yyyy-MM", true, "Period")),
            listOf("account", "name", "type", "glMapping", "debit", "credit", "balance"),
        ) { p ->
            ledger.trialBalance(p.uuid("legalEntityId"), YearMonth.parse(p.string("period"))).map {
                mapOf(
                    "account" to it.accountCode,
                    "name" to it.accountName,
                    "type" to it.type,
                    "glMapping" to it.glMapping,
                    "debit" to it.debit,
                    "credit" to it.credit,
                    "balance" to it.balance,
                )
            }
        },
        Definition(
            "GL_RECONCILIATION",
            "GL reconciliation",
            "Closing balances per GL account: sub-ledger, exported and general ledger",
            FINANCE,
            listOf(ENTITY, ReportParameter("period", "yyyy-MM", true, "Period")),
            listOf("glAccount", "subLedger", "exported", "generalLedger", "notExported", "glDifference", "reconciled"),
        ) { p ->
            gl.reconciliation(p.uuid("legalEntityId"), YearMonth.parse(p.string("period"))).map {
                mapOf(
                    "glAccount" to it.glAccount,
                    "subLedger" to it.subLedger,
                    "exported" to it.exported,
                    "generalLedger" to it.generalLedger,
                    "notExported" to it.notExported,
                    "glDifference" to it.glDifference,
                    "reconciled" to it.reconciled,
                )
            }
        },
    )

    fun definition(code: String): ReportDefinition? = definitions.firstOrNull { it.code == code.uppercase() }

    /**
     * Allowance trades of the year that were agreed (not cancelled or rejected) by purpose: intragroup, else by the
     * book type (hedging; compliance and own use; trading). Spot and derivatives separately.
     */
    private fun ancillary(legalEntityId: java.util.UUID, year: Int): List<Map<String, Any?>> {
        val agreed = trades.trades(
            TradeFilter(
                legalEntityId = legalEntityId,
                productFamily = ProductFamily.EMISSION_ALLOWANCE,
                tradeDateFrom = LocalDate.of(year, 1, 1),
                tradeDateTo = LocalDate.of(year + 1, 1, 1),
                limit = Int.MAX_VALUE,
            ),
        ).filter { it.status != TradeStatus.CANCELLED && it.status != TradeStatus.DRAFT }
        val rows = agreed.groupBy { category(it) to if (it.emirReportable) "DERIVATIVE" else "SPOT" }.map { (key, list) ->
            mapOf(
                "category" to key.first,
                "instrument" to key.second,
                "trades" to list.size,
                "notionalEur" to list.sumOf {
                    eur(it)
                }.setScale(2, RoundingMode.HALF_EVEN),
            )
        }.sortedWith(compareBy({ it["category"].toString() }, { it["instrument"].toString() }))
        val total = rows.sumOf { it["notionalEur"] as BigDecimal }
        return rows + mapOf("category" to "TOTAL", "instrument" to null, "trades" to agreed.size, "notionalEur" to total)
    }

    private fun category(trade: TradeView): AncillaryCategory {
        if (referenceData.counterparty(trade.counterpartyId)?.counterpartyType == CounterpartyType.INTERCOMPANY) return AncillaryCategory.INTRAGROUP
        return when (referenceData.book(trade.bookId)?.bookType) {
            BookType.HEDGING -> AncillaryCategory.HEDGING
            BookType.COMPLIANCE, BookType.OWN_USE -> AncillaryCategory.COMPLIANCE
            BookType.TRADING, null -> AncillaryCategory.TRADING
        }
    }

    private fun eur(trade: TradeView): BigDecimal = trade.legs.sumOf { leg ->
        val currency = leg.priceCurrency.currencyCode
        if (currency == "EUR") leg.notional else fxRates.rate(trade.tradeDate, currency, "EUR")?.let { leg.notional * it.rate } ?: BigDecimal.ZERO
    }

    private companion object {
        const val DEFAULT_MONTHS = 18
        val EMIR_FIELDS = listOf(
            "ACTION_TYPE",
            "EVENT_DATE",
            "UTI",
            "REPORTING_COUNTERPARTY",
            "OTHER_COUNTERPARTY",
            "NATURE_OF_REPORTING_COUNTERPARTY",
            "COUNTERPARTY_SIDE",
            "ASSET_CLASS",
            "CONTRACT_TYPE",
            "UPI",
            "ISIN",
            "VENUE",
            "EXECUTION_TIMESTAMP",
            "EFFECTIVE_DATE",
            "EXPIRATION_DATE",
            "DELIVERY_TYPE",
            "PRICE",
            "PRICE_CURRENCY",
            "NOTIONAL",
            "NOTIONAL_CURRENCY",
            "TOTAL_QUANTITY",
            "QUANTITY_UNIT",
            "CLEARED",
            "CCP",
            "INTRAGROUP",
            "VALUATION_AMOUNT",
            "VALUATION_CURRENCY",
            "VALUATION_TIMESTAMP",
            "VALUATION_METHOD",
        )
        val EMIR_COLUMNS = listOf("tradeNumber", "tradeVersion", "recordedAt") + EMIR_FIELDS
    }
}
