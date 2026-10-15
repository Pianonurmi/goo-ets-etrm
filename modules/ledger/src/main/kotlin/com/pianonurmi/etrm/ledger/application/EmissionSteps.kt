package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.allocation.api.ObligationQuery
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.etscompliance.api.EmissionKind
import com.pianonurmi.etrm.etscompliance.api.EmissionStatus
import com.pianonurmi.etrm.etscompliance.api.EtsComplianceQuery
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.ledger.api.MtmPolicyView
import com.pianonurmi.etrm.ledger.api.MtmTreatment
import com.pianonurmi.etrm.ledger.api.NrvPolicyView
import com.pianonurmi.etrm.ledger.api.PeriodEndCommand
import com.pianonurmi.etrm.ledger.api.PeriodEndCompleted
import com.pianonurmi.etrm.ledger.api.PeriodEndComponent
import com.pianonurmi.etrm.ledger.api.PeriodEndComponentKind
import com.pianonurmi.etrm.ledger.api.PeriodEndQuery
import com.pianonurmi.etrm.ledger.api.PeriodEndRunView
import com.pianonurmi.etrm.ledger.api.PeriodStatus
import com.pianonurmi.etrm.ledger.api.PolicyChangeRequested
import com.pianonurmi.etrm.ledger.api.PolicyChangeStatus
import com.pianonurmi.etrm.ledger.api.PolicyChangeView
import com.pianonurmi.etrm.ledger.api.PolicyKind
import com.pianonurmi.etrm.ledger.api.PostingFacts
import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.PostingSide
import com.pianonurmi.etrm.ledger.api.RequestPolicyChange
import com.pianonurmi.etrm.ledger.domain.CostLayerRepository
import com.pianonurmi.etrm.ledger.domain.CostingMethod
import com.pianonurmi.etrm.ledger.domain.EmissionFigures
import com.pianonurmi.etrm.ledger.domain.JournalRepository
import com.pianonurmi.etrm.ledger.domain.PeriodEndMath
import com.pianonurmi.etrm.ledger.domain.PeriodEndRepository
import com.pianonurmi.etrm.ledger.domain.PeriodRepository
import com.pianonurmi.etrm.ledger.domain.RuleAccounts
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.marketdata.api.IndexFixings
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.AllowanceClassification
import com.pianonurmi.etrm.referencedata.api.AllowanceCostingMethod
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.FreeAllocationPolicy
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Emissions to date and for the whole year, and allowances surrendered, over the entity's installations. */
@Component
class EmissionsToDate(private val compliance: EtsComplianceQuery) {
    /** Emissions to date and for the whole year, and allowances surrendered, over the entity's installations. */
    data class YearEmissions(val year: Int, val toDate: Long, val annual: Long, val outstanding: Long)

    fun of(ctx: PeriodEndContext, year: Int): YearEmissions {
        val entityCompliance = compliance.entityCompliance(ctx.entity.id, year)
        var toDate = 0L
        var annual = 0L
        var outstanding = 0L
        entityCompliance.installations.forEach { installation ->
            val approved = compliance.emissions(installation.installation.id, year).filter { it.status == EmissionStatus.APPROVED }
            val figures = EmissionFigures(
                verified = approved.firstOrNull { it.kind == EmissionKind.VERIFIED }?.quantityTco2e,
                monthly = approved.filter { it.kind == EmissionKind.MONTHLY_ESTIMATE }.associate { it.periodMonth!! to it.quantityTco2e },
                forecast = approved.firstOrNull { it.kind == EmissionKind.FORECAST }?.quantityTco2e,
            )
            val incurred = PeriodEndMath.emissionsToDate(figures, year, ctx.period)
            toDate += incurred
            annual += PeriodEndMath.annualEmissions(figures)
            outstanding += maxOf(0, incurred - installation.surrendered)
        }
        return YearEmissions(year, toDate, annual, outstanding)
    }
}

/** The provision for emissions not yet surrendered: covered at the cost of the allowances held, the rest at market. */
@Component
@Order(4)
class EmissionProvisionStep(
    private val emissions: EmissionsToDate,
    private val catalogue: ProductCatalogueQuery,
    private val costLayers: CostLayerRepository,
    private val curves: CurveQuery,
    private val journal: JournalRepository,
    private val postings: PeriodEndPostings,
) : PeriodEndStep {
    override fun apply(ctx: PeriodEndContext) {
        val years = listOf(ctx.period.year - 1, ctx.period.year).map { emissions.of(ctx, it) }
        val outstanding = years.sumOf { it.outstanding }
        val booked = journal.balance(ctx.entity.id, ctx.account(PROVISION_EVENT, PostingSide.CREDIT, "increase"), ctx.end).baseNet.negate()
        if (outstanding == 0L && booked.signum() == 0) return
        val products = catalogue.allowanceProducts(activeOnly = false).filter { it.jurisdiction == EU_ETS }.map { it.code }.toSet()
        val layers = costLayers.openAll(ctx.entity.id, ALLOWANCE_KEY).filter { it.assetKey.substringAfterLast(':') in products }
            .mapNotNull { layer -> postings.convert(ctx, layer.unitCost, layer.currency)?.let { layer.copy(unitCost = it, currency = ctx.base) } }
        val method = when (ctx.entity.accountingPolicy.allowanceCostingMethod) {
            AllowanceCostingMethod.FIFO -> CostingMethod.FIFO
            AllowanceCostingMethod.WEIGHTED_AVERAGE -> CostingMethod.WEIGHTED_AVERAGE
        }
        val market = curves.forwardPrice("EUA", ctx.end, ctx.end)?.let { postings.convert(ctx, it.value, it.currency) }
        val measure = PeriodEndMath.provision(outstanding, layers, method, market)
        if (measure.shortfall > 0 && market == null) {
            ctx.warnings += "No official EUA price on ${ctx.end}; the shortfall of ${measure.shortfall} t is not provided for"
        }
        val (up, down) = PeriodEndMath.movement(measure.required, booked)
        postings.post(
            ctx,
            PROVISION_EVENT,
            ctx.end,
            "Provision for ${outstanding}t of emissions not yet surrendered",
            mapOf("increase" to up, "decrease" to down),
        )
        ctx.components += periodEndComponent(
            PeriodEndComponentKind.EMISSION_PROVISION,
            "EMISSIONS",
            measure.required,
            booked,
            up - down,
            detail = mapOf(
                "outstanding" to outstanding.toString(),
                "covered" to measure.covered.toString(),
                "coveredCost" to measure.coveredCost.toPlainString(),
                "shortfall" to measure.shortfall.toString(),
                "marketPrice" to (market?.toPlainString() ?: ""),
                "byYear" to years.joinToString { "${it.year}:${it.outstanding}" },
            ),
        )
    }
}

/** IAS 20: free allocation booked at fair value is released to profit or loss as the emissions it covers are incurred. */
@Component
@Order(5)
class GrantReleaseStep(
    private val emissions: EmissionsToDate,
    private val journal: JournalRepository,
    private val postings: PeriodEndPostings,
) : PeriodEndStep {
    /** IAS 20: free allocation booked at fair value is released to profit or loss as the emissions it covers are incurred. */
    override fun apply(ctx: PeriodEndContext) {
        if (ctx.entity.accountingPolicy.freeAllocationPolicy != FreeAllocationPolicy.FAIR_VALUE) return
        listOf(ctx.period.year - 1, ctx.period.year).forEach { year ->
            val dims = mapOf("complianceYear" to year.toString())
            // Net of any debit to the deferred grant (a correction or reversal), not its credits alone.
            val deferredAccount = ctx.account(GRANT_EVENT, PostingSide.DEBIT, "increase")
            val deferred = journal.balance(ctx.entity.id, deferredAccount, ctx.end, "HoldingChanged", dimensions = dims).baseNet.negate()
            val booked = journal.balance(ctx.entity.id, ctx.account(GRANT_EVENT, PostingSide.CREDIT, "increase"), ctx.end, GRANT_EVENT, dimensions = dims)
                .baseNet.negate()
            if (deferred.signum() == 0 && booked.signum() == 0) return@forEach
            val e = emissions.of(ctx, year)
            val share = PeriodEndMath.grantReleaseShare(e.toDate, e.annual, year, ctx.period)
            val required = (deferred * share).setScale(2, RoundingMode.HALF_EVEN)
            val (up, down) = PeriodEndMath.movement(required, booked)
            postings.post(
                ctx,
                GRANT_EVENT,
                ctx.end,
                "Free allocation $year released with emissions incurred",
                mapOf("increase" to up, "decrease" to down),
                dimensions = dims,
            )
            ctx.components += periodEndComponent(
                PeriodEndComponentKind.GRANT_RELEASE,
                year.toString(),
                required,
                booked,
                up - down,
                detail = mapOf("share" to share.toPlainString()),
            )
        }
    }
}
