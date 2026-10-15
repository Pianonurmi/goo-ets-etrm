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

/**
 * Unrealised MtM by book type, at each book type's signed-off treatment, and cash flow hedges (ADR 0045): both
 * reversed on the first day of the next period. Margined futures carry none (ADR 0046).
 */
@Component
@Order(1)
class MtmStep(
    private val valuations: ValuationQuery,
    private val repository: PeriodEndRepository,
    private val journal: JournalRepository,
    private val postings: PeriodEndPostings,
    /** Cash flow hedges (ADR 0045). */
    private val hedges: HedgePositions,
    /** Cleared futures are settled daily by variation margin (ADR 0046) and carry no unrealised MtM. */
    private val clearing: ClearingQuery,
) : PeriodEndStep {
    override fun apply(ctx: PeriodEndContext) {
        val valuationRunId = ctx.valuationRunId
        val all = valuations.results(valuationRunId)
        val margined = clearing.marginedTradeIds(ctx.entity.id)
        val results = all.filter { it.status == ResultStatus.OK && it.mtmBase != null && (margined.isEmpty() || it.tradeId !in margined) }
        val excluded = all.size - results.size
        if (excluded > 0) ctx.warnings += "$excluded delivery schedules of the valuation have no MtM (missing price, rate or bucket, or excluded)"
        val hedged = valuations.run(valuationRunId)?.let { hedges.positions(ctx.entity.id, it, ctx.warnings) }.orEmpty()
        // The designated proportion of a hedging trade is accounted for by its hedge, the rest by its book type.
        val designated = hedged.flatMap { p -> p.hedge.instruments.map { it.tradeId to it.proportion } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, p) -> p.fold(BigDecimal.ZERO, BigDecimal::add) }
        val policies = repository.mtmPolicies()
        BookType.entries.forEach { bookType ->
            val values = results.filter { it.bookType == bookType }.map { r ->
                if (designated.isEmpty()) r.mtmBase!! else r.mtmBase!! * (BigDecimal.ONE - (designated[r.tradeId] ?: BigDecimal.ZERO))
            }
            val asset = values.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
            val liability = values.filter { it.signum() < 0 }.fold(BigDecimal.ZERO, BigDecimal::add).negate()
            val policy = PeriodEndMath.treatment(policies, ctx.entity.id, bookType)
            val dims = mapOf("bookType" to bookType.name)
            val bookedAsset = journal.balance(
                ctx.entity.id,
                ctx.account(MTM_EVENT, PostingSide.DEBIT, "assetIncrease"),
                ctx.end,
                MTM_EVENT,
                dimensions = dims,
            ).baseNet
            val bookedLiability =
                journal.balance(
                    ctx.entity.id,
                    ctx.account(MTM_EVENT, PostingSide.CREDIT, "liabilityIncrease"),
                    ctx.end,
                    MTM_EVENT,
                    dimensions = dims,
                ).baseNet.negate()
            if (values.isEmpty() && bookedAsset.signum() == 0 && bookedLiability.signum() == 0) return@forEach
            if (policy != null && !policy.signedOff && values.isNotEmpty()) ctx.warnings += "MtM policy for $bookType is not signed off by accounting"
            if (!PeriodEndMath.posts(policy)) {
                if (bookedAsset.signum() != 0 || bookedLiability.signum() != 0) {
                    ctx.warnings += "MtM of $bookType books was posted by an earlier run under another treatment and stays booked " +
                        "now that the policy only discloses; reverse it by a manual journal"
                }
                ctx.components += periodEndComponent(PeriodEndComponentKind.MTM, "$bookType:ASSET", asset, bookedAsset, BigDecimal.ZERO, disclosed = true)
                ctx.components += periodEndComponent(
                    PeriodEndComponentKind.MTM,
                    "$bookType:LIABILITY",
                    liability,
                    bookedLiability,
                    BigDecimal.ZERO,
                    disclosed = true,
                )
                return@forEach
            }
            val (assetUp, assetDown) = PeriodEndMath.movement(asset, bookedAsset)
            val (liabilityUp, liabilityDown) = PeriodEndMath.movement(liability, bookedLiability)
            val amounts =
                mapOf("assetIncrease" to assetUp, "assetDecrease" to assetDown, "liabilityIncrease" to liabilityUp, "liabilityDecrease" to liabilityDown)
            val attributes = mapOf("treatment" to policy!!.treatment.name)
            postings.post(ctx, MTM_EVENT, ctx.end, "Unrealised MtM of $bookType books", amounts, attributes, dims)
            val reversal =
                mapOf("assetIncrease" to assetDown, "assetDecrease" to assetUp, "liabilityIncrease" to liabilityDown, "liabilityDecrease" to liabilityUp)
            postings.post(ctx, MTM_EVENT, ctx.nextDay, "Reversal of unrealised MtM of $bookType books at ${ctx.end}", reversal, attributes, dims)
            ctx.components += periodEndComponent(PeriodEndComponentKind.MTM, "$bookType:ASSET", asset, bookedAsset, assetUp - assetDown, policy.treatment)
            ctx.components += periodEndComponent(
                PeriodEndComponentKind.MTM,
                "$bookType:LIABILITY",
                liability,
                bookedLiability,
                liabilityUp - liabilityDown,
                policy.treatment,
            )
        }
        hedged.forEach { hedge(ctx, it) }
    }

    /**
     * One cash flow hedge (ADR 0045): the instruments' designated value as derivative asset or liability, its effective
     * portion in the hedging reserve (2950) and the rest in profit or loss, each the difference from what is booked
     * and reversed on the first day of the next period, like the unrealised MtM of the books.
     */
    private fun hedge(ctx: PeriodEndContext, position: HedgeService.HedgePosition) {
        val value = position.instrumentValue
        val reserve = position.reserve
        val ineffective = value - reserve
        val dims = mapOf("hedge" to position.hedge.number)

        fun booked(side: PostingSide, amount: String) =
            journal.balance(ctx.entity.id, ctx.account(HEDGE_EVENT, side, amount), ctx.end, HEDGE_EVENT, dimensions = dims).baseNet
        val bookedReserve = booked(PostingSide.CREDIT, "reserveIncrease").negate()
        val bookedGain = booked(PostingSide.CREDIT, "gainIncrease").negate()
        val bookedLoss = booked(PostingSide.DEBIT, "lossIncrease")
        val moves = listOf(
            "asset" to PeriodEndMath.movement(value.max(BigDecimal.ZERO), booked(PostingSide.DEBIT, "assetIncrease")),
            "liability" to PeriodEndMath.movement(value.negate().max(BigDecimal.ZERO), booked(PostingSide.CREDIT, "liabilityIncrease").negate()),
            "reserve" to PeriodEndMath.movement(reserve, bookedReserve),
            "gain" to PeriodEndMath.movement(ineffective.max(BigDecimal.ZERO), bookedGain),
            "loss" to PeriodEndMath.movement(ineffective.negate().max(BigDecimal.ZERO), bookedLoss),
        )
        val bookedPl = bookedGain - bookedLoss
        val amounts = moves.flatMap { (name, m) -> listOf("${name}Increase" to m.first, "${name}Decrease" to m.second) }.toMap()
        val reversal = moves.flatMap { (name, m) -> listOf("${name}Increase" to m.second, "${name}Decrease" to m.first) }.toMap()
        val number = position.hedge.number
        postings.post(ctx, HEDGE_EVENT, ctx.end, "Cash flow hedge $number: reserve and ineffectiveness", amounts, emptyMap(), dims)
        postings.post(ctx, HEDGE_EVENT, ctx.nextDay, "Reversal of cash flow hedge $number at ${ctx.end}", reversal, emptyMap(), dims)
        val detail = mapOf("instrumentValue" to value.toPlainString(), "status" to position.hedge.status.name)
        ctx.components += periodEndComponent(PeriodEndComponentKind.HEDGE, "$number:RESERVE", reserve, bookedReserve, reserve - bookedReserve, detail = detail)
        ctx.components += periodEndComponent(
            PeriodEndComponentKind.HEDGE,
            "$number:INEFFECTIVE",
            ineffective,
            bookedPl,
            ineffective - bookedPl,
            detail = detail,
        )
    }
}
