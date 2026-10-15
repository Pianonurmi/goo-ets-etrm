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

/** Certificates at the lower of cost and NRV: firm sale price if reserved to one, else the vintage mark, less cost to sell. */
@Component
@Order(2)
class GooNrvStep(
    private val repository: PeriodEndRepository,
    private val lots: LotQuery,
    private val buckets: ProductBucketQuery,
    private val criteria: CriteriaService,
    private val curves: CurveQuery,
    private val firmSales: FirmSalePrices,
    private val postings: PeriodEndPostings,
) : PeriodEndStep {
    /** Certificates at the lower of cost and NRV: firm sale price if reserved to one, else the vintage mark, less cost to sell. */
    override fun apply(ctx: PeriodEndContext) {
        val policy = repository.effectiveNrvPolicy(ctx.entity.id)
        val live = lots.lots(LotFilter(holderEntityId = ctx.entity.id, statuses = LotStatus.LIVE, limit = Int.MAX_VALUE))
            .filter { (it.costBasisPerMwh?.signum() ?: 0) > 0 }
        if (live.isEmpty() && postings.nrvBooked(ctx, postings.nrvAccount(ctx, "GOO")).signum() == 0) return
        if (!policy.signedOff) ctx.warnings += "Net realisable value policy is not signed off by accounting"
        val costToSell = postings.convert(ctx, policy.gooCostToSellPerMwh, policy.currency) ?: BigDecimal.ZERO
        val bound = buckets.bind(criteria.contextFor(ctx.end))
        var unpriced = 0
        val required = live.fold(BigDecimal.ZERO) { total, lot ->
            val quantity = BigDecimal(lot.quantityMwh)
            val carrying = postings.convert(ctx, lot.costBasisPerMwh!! * quantity, lot.costCurrency ?: ctx.base)
            val price = firmSales.price(lot)?.let { (amount, currency) -> postings.convert(ctx, amount, currency) } ?: when {
                ChronoUnit.DAYS.between(ctx.end, lot.expiryDate) <= policy.expiryDays -> BigDecimal.ZERO
                else -> bound.best(lot)?.let {
                    curves.vintagePrice(it.code, lot.productionPeriod.start.year, ctx.end)
                }?.let { postings.convert(ctx, it.value, it.currency) }
            }
            if (carrying == null || price == null) {
                unpriced++
                total
            } else {
                total + PeriodEndMath.writeDown(carrying, quantity, price - costToSell)
            }
        }
        if (unpriced > 0) ctx.warnings += "$unpriced certificate lots have no price or rate and are not tested for net realisable value"
        postings.nrvPosting(ctx, "GOO", required)
    }
}

/** The contract price of the sale a reserved lot is planned for. */
@Component
class FirmSalePrices(
    private val plans: AllocationQuery,
    private val obligations: ObligationQuery,
    private val trades: TradeQuery,
    /** Index fixings price a reserved lot's index-priced sale. */
    private val fixings: IndexFixings,
) {
    /** The unit price and its currency, or null when the lot is not reserved to a sale or its price is not known. */
    fun price(lot: LotView): Pair<BigDecimal, String>? = lot.reservationRef
        ?.takeIf { lot.status == LotStatus.RESERVED }
        // A reservation may come from elsewhere than a plan (a manual one, say). `plan` answers null for it; `requirePlan`
        // would throw inside the allocation service's transaction and mark this period end rollback-only.
        ?.let(plans::plan)
        ?.lines?.firstOrNull { it.reservedLotId == lot.id }
        ?.let { obligations.obligation(it.obligationId)?.sourceRef }
        ?.let(trades::obligation)
        ?.let { sale -> deliveredUnitPrice(sale, fixings)?.let { it to sale.priceCurrency.currencyCode } }
}

/** Allowances held as inventory at the lower of cost and spot less cost to sell; intangible allowances are not tested here. */
@Component
@Order(3)
class AllowanceNrvStep(
    private val repository: PeriodEndRepository,
    private val costLayers: CostLayerRepository,
    private val curves: CurveQuery,
    private val postings: PeriodEndPostings,
) : PeriodEndStep {
    override fun apply(ctx: PeriodEndContext) {
        if (ctx.entity.accountingPolicy.allowanceClassification != AllowanceClassification.INVENTORY) return
        val policy = repository.effectiveNrvPolicy(ctx.entity.id)
        val costToSell = postings.convert(ctx, policy.allowanceCostToSell, policy.currency) ?: BigDecimal.ZERO
        val byProduct = costLayers.openAll(ctx.entity.id, ALLOWANCE_KEY).groupBy { it.assetKey.substringAfterLast(':') }
        val required = byProduct.entries.fold(BigDecimal.ZERO) { total, (product, layers) ->
            val carrying = layers.sumOf { postings.convert(ctx, it.unitCost * it.remainingQuantity, it.currency) ?: BigDecimal.ZERO }
            val spot = curves.forwardPrice(product, ctx.end, ctx.end)?.let { postings.convert(ctx, it.value, it.currency) }
            if (spot == null) {
                ctx.warnings += "No official $product price on ${ctx.end}; allowances not tested for net realisable value"
                total
            } else {
                total + PeriodEndMath.writeDown(carrying, layers.sumOf { it.remainingQuantity }, spot - costToSell)
            }
        }
        postings.nrvPosting(ctx, "ALLOWANCE", required)
    }
}
