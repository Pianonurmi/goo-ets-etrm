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

/** Jurisdiction of the allowances that settle EU ETS obligations. */
internal const val EU_ETS = "EU_ETS"
internal const val MTM_EVENT = "PeriodEndMtm"
internal const val HEDGE_EVENT = "PeriodEndHedge"
internal const val NRV_EVENT = "PeriodEndNrv"
internal const val PROVISION_EVENT = "PeriodEndProvision"
internal const val GRANT_EVENT = "PeriodEndGrantRelease"
internal const val FX_EVENT = "PeriodEndFxRevaluation"
internal const val REALISED_FX_EVENT = "RealisedFx"
internal const val ALLOWANCE_KEY = "ALLOWANCE:"

/** What one period-end run is working with; each [PeriodEndStep] adds its components, entries and warnings. */
class PeriodEndContext(
    val entity: LegalEntityView,
    val period: YearMonth,
    val runId: UUID,
    val rules: List<PostingRuleView>,
    /** The official valuation of the period's last business day. */
    val valuationRunId: UUID,
) {
    val base: String = entity.baseCurrency.currencyCode
    val end: LocalDate = period.atEndOfMonth()
    val nextDay: LocalDate = period.plusMonths(1).atDay(1)
    val components = mutableListOf<PeriodEndComponent>()
    val entries = mutableListOf<JournalEntryView>()
    val warnings = mutableListOf<String>()

    /** The account the period end's own rule for [eventType] posts [amount] to on [side] (ADR 0008). */
    fun account(eventType: String, side: PostingSide, amount: String, attributes: Map<String, String> = emptyMap()) =
        RuleAccounts.account(rules, entity.id, eventType, end, side, amount, attributes)
}

/** One component of the period end (ADR 0021): measured as the balance it should have, posted as the difference. */
fun interface PeriodEndStep {
    fun apply(ctx: PeriodEndContext)
}

internal fun PeriodEndRepository.effectiveNrvPolicy(legalEntityId: UUID): NrvPolicyView {
    val all = nrvPolicies()
    return all.firstOrNull { it.legalEntityId == legalEntityId } ?: all.firstOrNull { it.legalEntityId == null }
        ?: throw ValidationException("No net realisable value policy")
}

@Suppress("LongParameterList") // the component's figures, as the run view shows them
internal fun periodEndComponent(
    kind: PeriodEndComponentKind,
    key: String,
    required: BigDecimal,
    booked: BigDecimal,
    posted: BigDecimal,
    treatment: MtmTreatment? = null,
    disclosed: Boolean = false,
    detail: Map<String, String> = emptyMap(),
) = PeriodEndComponent(
    kind,
    key,
    required.setScale(2, RoundingMode.HALF_EVEN),
    booked.setScale(2, RoundingMode.HALF_EVEN),
    posted.setScale(2, RoundingMode.HALF_EVEN),
    disclosed,
    detail + (treatment?.let { mapOf("treatment" to it.name) } ?: emptyMap()),
)

/** Posts a step's adjustments, converts into the base currency, and reads what the period end has booked. */
@Component
class PeriodEndPostings(private val ledger: LedgerService, private val journal: JournalRepository, private val fxRates: FxRates) {
    fun post(
        ctx: PeriodEndContext,
        eventType: String,
        date: LocalDate,
        description: String,
        amounts: Map<String, BigDecimal>,
        attributes: Map<String, String> = emptyMap(),
        dimensions: Map<String, String> = emptyMap(),
    ) {
        if (amounts.values.all { it.signum() == 0 }) return
        ctx.entries += ledger.postAdjustment(
            PostingFacts(
                legalEntityId = ctx.entity.id,
                eventType = eventType,
                eventId = null,
                postingDate = date,
                currency = ctx.base,
                sourceType = "PERIOD_END",
                sourceRef = ctx.period.toString(),
                description = "$description (period end ${ctx.period})",
                amounts = amounts,
                attributes = attributes,
                dimensions = dimensions + mapOf("periodEndRun" to ctx.runId.toString()),
            ),
        )
    }

    fun convert(ctx: PeriodEndContext, amount: BigDecimal, currency: String): BigDecimal? =
        if (currency == ctx.base) amount else fxRates.rate(ctx.end, currency, ctx.base)?.let { amount * it.rate }

    fun nrvPosting(ctx: PeriodEndContext, asset: String, required: BigDecimal) {
        val booked = nrvBooked(ctx, nrvAccount(ctx, asset))
        val (up, down) = PeriodEndMath.movement(required, booked)
        post(ctx, NRV_EVENT, ctx.end, "Write-down to net realisable value ($asset)", mapOf("increase" to up, "decrease" to down), mapOf("asset" to asset))
        ctx.components += periodEndComponent(PeriodEndComponentKind.NRV, asset, required, booked, up - down)
    }

    /** The valuation allowance the NRV rule for [asset] writes down. */
    fun nrvAccount(ctx: PeriodEndContext, asset: String) = ctx.account(NRV_EVENT, PostingSide.CREDIT, "increase", mapOf("asset" to asset))

    /** Credit balance of a valuation allowance from period-end postings. */
    fun nrvBooked(ctx: PeriodEndContext, account: String): BigDecimal = journal.balance(ctx.entity.id, account, ctx.end, NRV_EVENT).baseNet.negate()
}
