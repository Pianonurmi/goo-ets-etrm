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

/** Open invoiced items in foreign currency at the closing rate, reversed on the first day of the next period. */
@Component
@Order(6)
class FxRevaluationStep(private val journal: JournalRepository, private val fxRates: FxRates, private val postings: PeriodEndPostings) : PeriodEndStep {
    override fun apply(ctx: PeriodEndContext) {
        RuleAccounts.conditionValues(ctx.rules, ctx.entity.id, FX_EVENT, ctx.end, "account").forEach { account ->
            journal.currencies(ctx.entity.id, account, ctx.end).filter { it != ctx.base }.forEach { currency ->
                val foreign = journal.balance(ctx.entity.id, account, ctx.end, currency = currency)
                val dims = mapOf("revaluedCurrency" to currency)
                val revalued = journal.balance(ctx.entity.id, account, ctx.end, FX_EVENT, dimensions = dims).baseNet
                // Realised FX on settled items is booked in base currency on the same account (SettlementPostings).
                val realised = journal.balance(ctx.entity.id, account, ctx.end, REALISED_FX_EVENT, dimensions = dims).baseNet
                val booked = foreign.baseNet + revalued + realised
                val rate = fxRates.rate(ctx.end, currency, ctx.base)?.rate
                if (rate == null) {
                    ctx.warnings += "No $currency/${ctx.base} rate on ${ctx.end}; account $account not revalued"
                    return@forEach
                }
                val required = (foreign.net * rate).setScale(2, RoundingMode.HALF_EVEN)
                val (gain, loss) = PeriodEndMath.movement(required, booked)
                val attributes = mapOf("account" to account)
                postings.post(
                    ctx,
                    FX_EVENT,
                    ctx.end,
                    "$account in $currency at the closing rate $rate",
                    mapOf("gain" to gain, "loss" to loss),
                    attributes,
                    dims,
                )
                postings.post(
                    ctx,
                    FX_EVENT,
                    ctx.nextDay,
                    "Reversal of $account $currency revaluation at ${ctx.end}",
                    mapOf("gain" to loss, "loss" to gain),
                    attributes,
                    dims,
                )
                ctx.components += periodEndComponent(
                    PeriodEndComponentKind.FX_REVALUATION,
                    "$account:$currency",
                    required,
                    booked,
                    gain - loss,
                    detail = mapOf("foreign" to foreign.net.toPlainString(), "rate" to rate.toPlainString()),
                )
            }
        }
    }
}
