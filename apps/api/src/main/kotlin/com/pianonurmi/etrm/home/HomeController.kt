package com.pianonurmi.etrm.home

import com.pianonurmi.etrm.allocation.api.ObligationQuery
import com.pianonurmi.etrm.audit.api.AuditFilter
import com.pianonurmi.etrm.audit.api.AuditQuery
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceQuery
import com.pianonurmi.etrm.inventory.api.BreakFilter
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.ledger.api.GlInterfaceQuery
import com.pianonurmi.etrm.ledger.api.LedgerQuery
import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.position.api.PositionQuery
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.RiskQuery
import com.pianonurmi.etrm.settlement.api.InvoiceFilter
import com.pianonurmi.etrm.settlement.api.InvoiceKind
import com.pianonurmi.etrm.settlement.api.InvoiceQuery
import com.pianonurmi.etrm.settlement.api.InvoiceStatus
import com.pianonurmi.etrm.settlement.api.PaymentStatus
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeSearch
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.workflow.api.InboxFilter
import com.pianonurmi.etrm.workflow.api.InboxSort
import com.pianonurmi.etrm.workflow.api.WorkflowQuery
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * The home dashboard in one call (ADR 0034): what needs the user, and the headline figures of one legal entity,
 * each section only for the roles that may read it. A section that fails is left out, so one module cannot break
 * the home page.
 */
@RestController
@RequestMapping("/api/v1/home")
class HomeController(
    private val actors: ActorProvider,
    private val clock: Clock,
    private val workflow: WorkflowQuery,
    private val trades: TradeQuery,
    private val positions: PositionQuery,
    private val obligations: ObligationQuery,
    private val lots: LotQuery,
    private val valuations: ValuationQuery,
    private val reconciliation: ReconciliationQuery,
    private val referenceData: ReferenceDataQuery,
    private val risk: RiskQuery,
    private val invoices: InvoiceQuery,
    private val ledger: LedgerQuery,
    private val glInterface: GlInterfaceQuery,
    private val ets: EtsComplianceQuery,
    private val audit: AuditQuery,
) {
    private val log = LoggerFactory.getLogger(HomeController::class.java)

    @GetMapping("/summary")
    fun summary(
        @RequestParam legalEntityId: UUID,
    ): HomeSummaryView {
        val actor = actors.current()
        actor.requireEntity(legalEntityId)
        val today = LocalDate.now(clock)
        return HomeSummaryView(
            legalEntityId = legalEntityId,
            asOf = today,
            tasks = section("tasks") { tasks(legalEntityId) },
            trades = section("trades") { trades(legalEntityId, today) },
            goo = section("goo") { goo(legalEntityId, today) },
            eua = section("eua") { eua(legalEntityId, today) },
            valuation = section("valuation", actor, VALUATION_READERS) { valuation(legalEntityId, today) },
            reconciliation = section("reconciliation", actor, RECONCILIATION_READERS) { breaks(legalEntityId) },
            risk = section("risk", actor, RISK_READERS) { HomeRisk(risk.breaches(legalEntityId, openOnly = true).size) },
            settlement = section("settlement", actor, SETTLEMENT_READERS) { overdue(legalEntityId, today) },
            ledger = section("ledger", actor, LEDGER_READERS) { ledger(legalEntityId, today) },
            deadlines = section("deadlines") { deadlines(legalEntityId, today) }.orEmpty(),
            // The activity feed is the audit trail, which spans every entity.
            activity = if (actor.seesAllEntities) section("activity", actor, AUDIT_READERS) { activity() } else null,
        )
    }

    private fun tasks(legalEntityId: UUID): HomeTasks {
        val names = workflow.definitions().associate { it.taskType to it.displayName }
        val counts = workflow.counts(actionable = true)
        val recent = workflow.inbox(InboxFilter(actionable = true, legalEntityId = legalEntityId, sort = InboxSort.DUE, limit = 6)).items
        val now = clock.instant()
        return HomeTasks(
            actionable = counts.values.sum(),
            recent = recent.map {
                HomeTask(it.id, it.type, names[it.type] ?: it.type, it.summary, it.requestedBy, it.requestedAt, it.dueAt, it.overdue(now))
            },
        )
    }

    private fun trades(legalEntityId: UUID, today: LocalDate): HomeTrades {
        val pending = trades.searchTrades(TradeSearch(legalEntityId = legalEntityId, statuses = setOf(TradeStatus.PENDING_APPROVAL), limit = 200))
        val recent = trades.searchTrades(TradeSearch(legalEntityId = legalEntityId, tradeDateFrom = today.minusDays(7), limit = 200))
        return HomeTrades(pendingApproval = pending.items.size, lastSevenDays = recent.items.size)
    }

    private fun goo(legalEntityId: UUID, today: LocalDate): HomeGoo {
        val report = positions.gooPosition(legalEntityId)
        val coverage = report.coverage
        val byObligation = coverage.obligations.associateBy { it.obligationId }
        val months = obligations.open(legalEntityId)
            .groupBy { YearMonth.from(it.deliveryDate) }
            .toSortedMap()
            .entries.take(MONTHS)
            .map { (month, open) ->
                HomeCoverageMonth(
                    month = month.toString(),
                    demandMwh = open.sumOf { byObligation[it.id]?.demandMwh ?: (it.quantityMwh - it.fulfilledMwh) },
                    coveredMwh = open.sumOf { byObligation[it.id]?.coveredMwh ?: 0L },
                )
            }
        val expiring = lots.visibleLots(
            LotFilter(
                holderEntityId = legalEntityId,
                statuses = setOf(LotStatus.HELD, LotStatus.RESERVED, LotStatus.IN_TRANSFER),
                expiringBefore = today.plusDays(EXPIRY_HORIZON_DAYS),
            ),
        )

        fun expiringWithin(days: Long) = expiring.filter { it.expiryDate < today.plusDays(days) }.sumOf { it.quantityMwh }
        return HomeGoo(
            liveMwh = report.totalLiveMwh,
            openObligationMwh = report.totalOpenObligationMwh,
            demandMwh = coverage.totalDemandMwh,
            coveredMwh = coverage.totalCoveredMwh,
            shortfallMwh = coverage.totalShortfallMwh,
            coveragePct = if (coverage.totalDemandMwh > 0) {
                BigDecimal(coverage.totalCoveredMwh * 100).divide(BigDecimal(coverage.totalDemandMwh), 1, RoundingMode.HALF_UP)
            } else {
                null
            },
            expiring30Mwh = expiringWithin(30),
            expiring60Mwh = expiringWithin(60),
            expiring90Mwh = expiringWithin(EXPIRY_HORIZON_DAYS),
            byMonth = months,
        )
    }

    private fun eua(legalEntityId: UUID, today: LocalDate): HomeEua {
        val ladder = positions.complianceLadder(legalEntityId, today.year)
        return HomeEua(
            complianceYear = today.year,
            heldAllowances = ladder.heldAllowances,
            obligationOutstanding = ladder.obligationOutstanding,
            netPosition = ladder.netPosition,
            surrenderDeadline = ladder.surrenderDeadline,
        )
    }

    private fun valuation(legalEntityId: UUID, today: LocalDate): HomeValuation? {
        val latest = valuations.official(legalEntityId, today, orEarlier = true) ?: return null
        val previous = valuations.official(legalEntityId, latest.valuationDate.minusDays(1), orEarlier = true)
        val history = valuations.runs(legalEntityId, today.minusDays(HISTORY_DAYS), today)
            .filter { it.status.name == "OFFICIAL" }
            .sortedBy { it.valuationDate }
            .map { HomeDatedAmount(it.valuationDate, it.totalMtmBase) }
        return HomeValuation(
            valuationDate = latest.valuationDate,
            currency = latest.baseCurrency,
            totalMtm = latest.totalMtmBase,
            previousDate = previous?.valuationDate,
            previousMtm = previous?.totalMtmBase,
            change = previous?.let { latest.totalMtmBase - it.totalMtmBase },
            history = history,
        )
    }

    private fun breaks(legalEntityId: UUID): HomeBreaks {
        val accounts = referenceData.registryAccounts(HolderType.LEGAL_ENTITY, legalEntityId).map { it.id }
        val open = accounts.flatMap { reconciliation.breaks(BreakFilter(registryAccountId = it)) }
        return HomeBreaks(open = open.size, oldestFirstSeen = open.mapNotNull { it.firstSeenOn }.minOrNull())
    }

    private fun overdue(legalEntityId: UUID, today: LocalDate): HomeSettlement {
        val overdue = invoices.invoices(InvoiceFilter(legalEntityId = legalEntityId, statuses = setOf(InvoiceStatus.ISSUED)))
            .filter { it.kind != InvoiceKind.CREDIT_NOTE && it.paymentStatus != PaymentStatus.PAID && it.dueDate?.isBefore(today) == true }
        return HomeSettlement(
            overdueInvoices = overdue.size,
            overdueAmounts = overdue.groupBy { it.currency }.map { (currency, list) ->
                HomeAmount(list.sumOf { it.grossTotal - it.paidAmount }, currency)
            },
        )
    }

    private fun ledger(legalEntityId: UUID, today: LocalDate): HomeLedger {
        val previous = YearMonth.from(today).minusMonths(1)
        val status = ledger.periods(legalEntityId).firstOrNull { it.period == previous.toString() }?.status?.name ?: "OPEN"
        return HomeLedger(period = previous.toString(), status = status, blockers = glInterface.hardCloseBlockers(legalEntityId, previous))
    }

    private fun deadlines(legalEntityId: UUID, today: LocalDate): List<HomeDeadline> =
        ets.deadlines(legalEntityId, today, today.plusDays(DEADLINE_HORIZON_DAYS))
            .filter { !it.met }
            .take(DEADLINES)
            .map { HomeDeadline(it.kind.name, it.installationName, it.complianceYear, it.dueOn) }

    private fun activity(): List<HomeActivity> = audit.events(AuditFilter(limit = ACTIVITY)).map {
        HomeActivity(it.occurredAt, it.actor, it.eventType, it.aggregateType, it.aggregateRef)
    }

    private fun <T> section(name: String, actor: Actor? = null, roles: Set<Role>? = null, block: () -> T): T? {
        if (actor != null && roles != null && roles.none(actor::has)) return null
        return try {
            block()
        } catch (failure: DomainException) {
            log.debug("Home section {} left out: {}", name, failure.message)
            null
        }
    }

    companion object {
        private const val MONTHS = 6
        private const val DEADLINES = 5
        private const val ACTIVITY = 8
        private const val EXPIRY_HORIZON_DAYS = 90L
        private const val DEADLINE_HORIZON_DAYS = 120L
        private const val HISTORY_DAYS = 60L
        private val VALUATION_READERS = setOf(Role.RISK, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.AUDITOR, Role.TRADER)
        private val RECONCILIATION_READERS =
            setOf(Role.REGISTRY_OPERATOR, Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.ACCOUNTANT, Role.RISK, Role.AUDITOR)
        private val RISK_READERS = setOf(Role.RISK, Role.MIDDLE_OFFICE, Role.TRADER, Role.AUDITOR)
        private val SETTLEMENT_READERS = setOf(Role.ACCOUNTANT, Role.BACK_OFFICE, Role.MIDDLE_OFFICE, Role.RISK, Role.AUDITOR)
        private val LEDGER_READERS = setOf(Role.ACCOUNTANT, Role.AUDITOR, Role.BACK_OFFICE)
        private val AUDIT_READERS = setOf(Role.AUDITOR, Role.ACCOUNTANT, Role.MIDDLE_OFFICE, Role.BACK_OFFICE, Role.RISK)
    }
}

data class HomeSummaryView(
    val legalEntityId: UUID,
    val asOf: LocalDate,
    val tasks: HomeTasks?,
    val trades: HomeTrades?,
    val goo: HomeGoo?,
    val eua: HomeEua?,
    val valuation: HomeValuation?,
    val reconciliation: HomeBreaks?,
    val risk: HomeRisk?,
    val settlement: HomeSettlement?,
    val ledger: HomeLedger?,
    val deadlines: List<HomeDeadline>,
    val activity: List<HomeActivity>?,
)

data class HomeTasks(val actionable: Int, val recent: List<HomeTask>)

data class HomeTask(
    val id: UUID,
    val type: String,
    val typeName: String,
    val summary: String?,
    val requestedBy: String,
    val requestedAt: Instant,
    val dueAt: Instant?,
    val overdue: Boolean,
)

data class HomeTrades(val pendingApproval: Int, val lastSevenDays: Int)

data class HomeGoo(
    val liveMwh: Long,
    val openObligationMwh: Long,
    val demandMwh: Long,
    val coveredMwh: Long,
    val shortfallMwh: Long,
    /** Covered share of the open demand in percent (one decimal); null without open demand. */
    val coveragePct: BigDecimal?,
    val expiring30Mwh: Long,
    val expiring60Mwh: Long,
    val expiring90Mwh: Long,
    val byMonth: List<HomeCoverageMonth>,
)

data class HomeCoverageMonth(val month: String, val demandMwh: Long, val coveredMwh: Long)

data class HomeEua(
    val complianceYear: Int,
    val heldAllowances: Long,
    val obligationOutstanding: Long,
    val netPosition: Long,
    val surrenderDeadline: LocalDate?,
)

data class HomeValuation(
    val valuationDate: LocalDate,
    val currency: String,
    val totalMtm: BigDecimal,
    val previousDate: LocalDate?,
    val previousMtm: BigDecimal?,
    /** Change against the previous official run. */
    val change: BigDecimal?,
    val history: List<HomeDatedAmount>,
)

data class HomeDatedAmount(val date: LocalDate, val amount: BigDecimal)

data class HomeAmount(val amount: BigDecimal, val currency: String)

data class HomeBreaks(val open: Int, val oldestFirstSeen: LocalDate?)

data class HomeRisk(val openBreaches: Int)

data class HomeSettlement(val overdueInvoices: Int, val overdueAmounts: List<HomeAmount>)

data class HomeLedger(val period: String, val status: String, val blockers: List<String>)

data class HomeDeadline(val kind: String, val installationName: String, val complianceYear: Int, val dueOn: LocalDate)

data class HomeActivity(
    val occurredAt: Instant,
    val actor: String,
    val eventType: String,
    val aggregateType: String?,
    val aggregateRef: String?,
)
