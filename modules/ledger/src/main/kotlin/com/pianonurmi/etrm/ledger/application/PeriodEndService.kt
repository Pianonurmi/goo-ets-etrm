package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.allocation.api.AllocationQuery
import com.pianonurmi.etrm.allocation.api.ObligationQuery
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
import com.pianonurmi.etrm.ledger.api.RequestPolicyChange
import com.pianonurmi.etrm.ledger.domain.CostLayerRepository
import com.pianonurmi.etrm.ledger.domain.CostingMethod
import com.pianonurmi.etrm.ledger.domain.EmissionFigures
import com.pianonurmi.etrm.ledger.domain.JournalRepository
import com.pianonurmi.etrm.ledger.domain.PeriodEndMath
import com.pianonurmi.etrm.ledger.domain.PeriodEndRepository
import com.pianonurmi.etrm.ledger.domain.PeriodRepository
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.errors.NotFoundException
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
private const val EU_ETS = "EU_ETS"

/**
 * Period-end measurements and postings (ADR 0021). Every component is measured as the balance it should have at the
 * end of the period and posted as the difference to what is booked, so running the period end again posts only what
 * changed. MtM and FX revaluation are reversed on the first day of the next period.
 */
@Service
@Transactional
class PeriodEndService(
    private val ledger: LedgerService,
    private val journal: JournalRepository,
    private val costLayers: CostLayerRepository,
    private val periods: PeriodRepository,
    private val repository: PeriodEndRepository,
    private val referenceData: ReferenceDataQuery,
    private val catalogue: ProductCatalogueQuery,
    private val valuations: ValuationQuery,
    private val compliance: EtsComplianceQuery,
    private val lots: LotQuery,
    private val buckets: ProductBucketQuery,
    private val criteria: CriteriaService,
    private val curves: CurveQuery,
    private val fxRates: FxRates,
    private val plans: AllocationQuery,
    private val obligations: ObligationQuery,
    private val trades: TradeQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : PeriodEndCommand, PeriodEndQuery {
    /** What one run is working with. */
    private class Context(val entity: LegalEntityView, val period: YearMonth, val runId: UUID) {
        val base: String = entity.baseCurrency.currencyCode
        val end: LocalDate = period.atEndOfMonth()
        val nextDay: LocalDate = period.plusMonths(1).atDay(1)
        val components = mutableListOf<PeriodEndComponent>()
        val entries = mutableListOf<JournalEntryView>()
        val warnings = mutableListOf<String>()
    }

    override fun run(legalEntityId: UUID, period: YearMonth): PeriodEndRunView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        actor.requireEntity(legalEntityId)
        val entity = referenceData.requireLegalEntity(legalEntityId)
        if (status(legalEntityId, period) == PeriodStatus.CLOSED) throw ValidationException("Period $period is hard-closed")
        if (status(legalEntityId, period.plusMonths(1)) != PeriodStatus.OPEN) {
            throw ValidationException("Period ${period.plusMonths(1)} must be open: MtM and FX revaluation reverse on its first day")
        }
        val lastBusinessDay = PeriodEndMath.lastBusinessDay(period)
        val valuation = valuations.official(legalEntityId, period.atEndOfMonth(), orEarlier = true)
            ?.takeIf { !it.valuationDate.isBefore(lastBusinessDay) }
            ?: throw ValidationException("No official valuation of $lastBusinessDay for ${entity.code}; sign off the valuation run first")
        ledger.lockEntity(legalEntityId)
        ledger.ensureChartOfAccounts(legalEntityId)
        val ctx = Context(entity, period, Ids.newId())
        mtm(ctx, valuation.id)
        gooNrv(ctx)
        allowanceNrv(ctx)
        emissionProvision(ctx)
        grantRelease(ctx)
        fxRevaluation(ctx)
        val run = PeriodEndRunView(
            ctx.runId,
            legalEntityId,
            period.toString(),
            repository.nextRunSeq(legalEntityId, period.toString()),
            valuation.id,
            ctx.components,
            ctx.entries.map { it.id },
            ctx.warnings,
            actor.userId,
            clock.instant(),
        )
        repository.insertRun(run)
        events.publishEvent(PeriodEndCompleted(run))
        return run
    }

    private fun status(legalEntityId: UUID, period: YearMonth): PeriodStatus = periods.find(legalEntityId, period.toString())?.status ?: PeriodStatus.OPEN

    // ----------------------------------------------------------------------------------------------------------
    // MtM by book type
    // ----------------------------------------------------------------------------------------------------------

    private fun mtm(ctx: Context, valuationRunId: UUID) {
        val all = valuations.results(valuationRunId)
        val results = all.filter { it.status == ResultStatus.OK && it.mtmBase != null }
        val excluded = all.size - results.size
        if (excluded > 0) ctx.warnings += "$excluded delivery schedules of the valuation have no MtM (missing price, rate or bucket, or excluded)"
        val policies = repository.mtmPolicies()
        BookType.entries.forEach { bookType ->
            val values = results.filter { it.bookType == bookType }.map { it.mtmBase!! }
            val asset = values.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
            val liability = values.filter { it.signum() < 0 }.fold(BigDecimal.ZERO, BigDecimal::add).negate()
            val policy = PeriodEndMath.treatment(policies, ctx.entity.id, bookType)
            val dims = mapOf("bookType" to bookType.name)
            val bookedAsset = journal.balance(ctx.entity.id, "1800", ctx.end, MTM_EVENT, dimensions = dims).baseNet
            val bookedLiability = journal.balance(ctx.entity.id, "2800", ctx.end, MTM_EVENT, dimensions = dims).baseNet.negate()
            if (values.isEmpty() && bookedAsset.signum() == 0 && bookedLiability.signum() == 0) return@forEach
            if (policy != null && !policy.signedOff && values.isNotEmpty()) ctx.warnings += "MtM policy for $bookType is not signed off by accounting"
            if (!PeriodEndMath.posts(policy)) {
                ctx.components += component(PeriodEndComponentKind.MTM, "$bookType:ASSET", asset, bookedAsset, BigDecimal.ZERO, disclosed = true)
                ctx.components += component(PeriodEndComponentKind.MTM, "$bookType:LIABILITY", liability, bookedLiability, BigDecimal.ZERO, disclosed = true)
                return@forEach
            }
            val (assetUp, assetDown) = PeriodEndMath.movement(asset, bookedAsset)
            val (liabilityUp, liabilityDown) = PeriodEndMath.movement(liability, bookedLiability)
            val amounts =
                mapOf("assetIncrease" to assetUp, "assetDecrease" to assetDown, "liabilityIncrease" to liabilityUp, "liabilityDecrease" to liabilityDown)
            val attributes = mapOf("treatment" to policy!!.treatment.name)
            post(ctx, MTM_EVENT, ctx.end, "Unrealised MtM of $bookType books", amounts, attributes, dims)
            val reversal =
                mapOf("assetIncrease" to assetDown, "assetDecrease" to assetUp, "liabilityIncrease" to liabilityDown, "liabilityDecrease" to liabilityUp)
            post(ctx, MTM_EVENT, ctx.nextDay, "Reversal of unrealised MtM of $bookType books at ${ctx.end}", reversal, attributes, dims)
            ctx.components += component(PeriodEndComponentKind.MTM, "$bookType:ASSET", asset, bookedAsset, assetUp - assetDown, policy.treatment)
            ctx.components += component(
                PeriodEndComponentKind.MTM,
                "$bookType:LIABILITY",
                liability,
                bookedLiability,
                liabilityUp - liabilityDown,
                policy.treatment,
            )
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Net realisable value
    // ----------------------------------------------------------------------------------------------------------

    private fun effectiveNrvPolicy(legalEntityId: UUID): NrvPolicyView {
        val all = repository.nrvPolicies()
        return all.firstOrNull { it.legalEntityId == legalEntityId } ?: all.firstOrNull { it.legalEntityId == null }
            ?: throw ValidationException("No net realisable value policy")
    }

    /** Certificates at the lower of cost and NRV: firm sale price if reserved to one, else the vintage mark, less cost to sell. */
    private fun gooNrv(ctx: Context) {
        val policy = effectiveNrvPolicy(ctx.entity.id)
        val live = lots.lots(LotFilter(holderEntityId = ctx.entity.id, statuses = LotStatus.LIVE, limit = Int.MAX_VALUE))
            .filter { (it.costBasisPerMwh?.signum() ?: 0) > 0 }
        if (live.isEmpty() && booked(ctx, "1490").signum() == 0) return
        if (!policy.signedOff) ctx.warnings += "Net realisable value policy is not signed off by accounting"
        val costToSell = convert(ctx, policy.gooCostToSellPerMwh, policy.currency) ?: BigDecimal.ZERO
        val bound = buckets.bind(criteria.contextFor(ctx.end))
        var unpriced = 0
        val required = live.fold(BigDecimal.ZERO) { total, lot ->
            val quantity = BigDecimal(lot.quantityMwh)
            val carrying = convert(ctx, lot.costBasisPerMwh!! * quantity, lot.costCurrency ?: ctx.base)
            val price = firmSalePrice(ctx, lot) ?: when {
                ChronoUnit.DAYS.between(ctx.end, lot.expiryDate) <= policy.expiryDays -> BigDecimal.ZERO
                else -> bound.best(lot)?.let {
                    curves.vintagePrice(it.code, lot.productionPeriod.start.year, ctx.end)
                }?.let { convert(ctx, it.value, it.currency) }
            }
            if (carrying == null || price == null) {
                unpriced++
                total
            } else {
                total + PeriodEndMath.writeDown(carrying, quantity, price - costToSell)
            }
        }
        if (unpriced > 0) ctx.warnings += "$unpriced certificate lots have no price or rate and are not tested for net realisable value"
        nrvPosting(ctx, "GOO", "1490", required)
    }

    /** The contract price of the sale a reserved lot is planned for, in base currency. */
    private fun firmSalePrice(ctx: Context, lot: LotView): BigDecimal? = lot.reservationRef
        ?.takeIf { lot.status == LotStatus.RESERVED }
        ?.let { runCatching { plans.requirePlan(it) }.getOrNull() }
        ?.lines?.firstOrNull { it.reservedLotId == lot.id }
        ?.let { obligations.obligation(it.obligationId)?.sourceRef }
        ?.let(trades::obligation)
        ?.let { sale -> convert(ctx, sale.price, sale.priceCurrency.currencyCode) }

    /** Allowances held as inventory at the lower of cost and spot less cost to sell; intangible allowances are not tested here. */
    private fun allowanceNrv(ctx: Context) {
        if (ctx.entity.accountingPolicy.allowanceClassification != AllowanceClassification.INVENTORY) return
        val policy = effectiveNrvPolicy(ctx.entity.id)
        val costToSell = convert(ctx, policy.allowanceCostToSell, policy.currency) ?: BigDecimal.ZERO
        val byProduct = costLayers.openAll(ctx.entity.id, ALLOWANCE_KEY).groupBy { it.assetKey.substringAfterLast(':') }
        val required = byProduct.entries.fold(BigDecimal.ZERO) { total, (product, layers) ->
            val carrying = layers.sumOf { convert(ctx, it.unitCost * it.remainingQuantity, it.currency) ?: BigDecimal.ZERO }
            val spot = curves.forwardPrice(product, ctx.end, ctx.end)?.let { convert(ctx, it.value, it.currency) }
            if (spot == null) {
                ctx.warnings += "No official $product price on ${ctx.end}; allowances not tested for net realisable value"
                total
            } else {
                total + PeriodEndMath.writeDown(carrying, layers.sumOf { it.remainingQuantity }, spot - costToSell)
            }
        }
        nrvPosting(ctx, "ALLOWANCE", "1491", required)
    }

    private fun nrvPosting(ctx: Context, asset: String, account: String, required: BigDecimal) {
        val booked = booked(ctx, account)
        val (up, down) = PeriodEndMath.movement(required, booked)
        post(ctx, NRV_EVENT, ctx.end, "Write-down to net realisable value ($asset)", mapOf("increase" to up, "decrease" to down), mapOf("asset" to asset))
        ctx.components += component(PeriodEndComponentKind.NRV, asset, required, booked, up - down)
    }

    /** Credit balance of a valuation allowance from period-end postings. */
    private fun booked(ctx: Context, account: String): BigDecimal = journal.balance(ctx.entity.id, account, ctx.end, NRV_EVENT).baseNet.negate()

    // ----------------------------------------------------------------------------------------------------------
    // Emission provision and free allocation grant
    // ----------------------------------------------------------------------------------------------------------

    /** Emissions to date and for the whole year, and allowances surrendered, over the entity's installations. */
    private data class YearEmissions(val year: Int, val toDate: Long, val annual: Long, val outstanding: Long)

    private fun emissions(ctx: Context, year: Int): YearEmissions {
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

    private fun emissionProvision(ctx: Context) {
        val years = listOf(ctx.period.year - 1, ctx.period.year).map { emissions(ctx, it) }
        val outstanding = years.sumOf { it.outstanding }
        val booked = journal.balance(ctx.entity.id, "2300", ctx.end).baseNet.negate()
        if (outstanding == 0L && booked.signum() == 0) return
        val products = catalogue.allowanceProducts(activeOnly = false).filter { it.jurisdiction == EU_ETS }.map { it.code }.toSet()
        val layers = costLayers.openAll(ctx.entity.id, ALLOWANCE_KEY).filter { it.assetKey.substringAfterLast(':') in products }
            .mapNotNull { layer -> convert(ctx, layer.unitCost, layer.currency)?.let { layer.copy(unitCost = it, currency = ctx.base) } }
        val method = when (ctx.entity.accountingPolicy.allowanceCostingMethod) {
            AllowanceCostingMethod.FIFO -> CostingMethod.FIFO
            AllowanceCostingMethod.WEIGHTED_AVERAGE -> CostingMethod.WEIGHTED_AVERAGE
        }
        val market = curves.forwardPrice("EUA", ctx.end, ctx.end)?.let { convert(ctx, it.value, it.currency) }
        val measure = PeriodEndMath.provision(outstanding, layers, method, market)
        if (measure.shortfall > 0 && market == null) {
            ctx.warnings += "No official EUA price on ${ctx.end}; the shortfall of ${measure.shortfall} t is not provided for"
        }
        val (up, down) = PeriodEndMath.movement(measure.required, booked)
        post(ctx, PROVISION_EVENT, ctx.end, "Provision for ${outstanding}t of emissions not yet surrendered", mapOf("increase" to up, "decrease" to down))
        ctx.components += component(
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

    /** IAS 20: free allocation booked at fair value is released to profit or loss as the emissions it covers are incurred. */
    private fun grantRelease(ctx: Context) {
        if (ctx.entity.accountingPolicy.freeAllocationPolicy != FreeAllocationPolicy.FAIR_VALUE) return
        listOf(ctx.period.year - 1, ctx.period.year).forEach { year ->
            val dims = mapOf("complianceYear" to year.toString())
            val deferred = journal.balance(ctx.entity.id, "2400", ctx.end, "HoldingChanged", dimensions = dims).baseCredit
            val booked = journal.balance(ctx.entity.id, "4210", ctx.end, GRANT_EVENT, dimensions = dims).baseNet.negate()
            if (deferred.signum() == 0 && booked.signum() == 0) return@forEach
            val e = emissions(ctx, year)
            val share = PeriodEndMath.grantReleaseShare(e.toDate, e.annual, year, ctx.period)
            val required = (deferred * share).setScale(2, RoundingMode.HALF_EVEN)
            val (up, down) = PeriodEndMath.movement(required, booked)
            post(
                ctx,
                GRANT_EVENT,
                ctx.end,
                "Free allocation $year released with emissions incurred",
                mapOf("increase" to up, "decrease" to down),
                dimensions = dims,
            )
            ctx.components += component(
                PeriodEndComponentKind.GRANT_RELEASE,
                year.toString(),
                required,
                booked,
                up - down,
                detail = mapOf("share" to share.toPlainString()),
            )
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // FX revaluation of open invoiced items
    // ----------------------------------------------------------------------------------------------------------

    private fun fxRevaluation(ctx: Context) {
        listOf("1510", "2210").forEach { account ->
            journal.currencies(ctx.entity.id, account, ctx.end).filter { it != ctx.base }.forEach { currency ->
                val foreign = journal.balance(ctx.entity.id, account, ctx.end, currency = currency)
                val dims = mapOf("revaluedCurrency" to currency)
                val revalued = journal.balance(ctx.entity.id, account, ctx.end, FX_EVENT, dimensions = dims).baseNet
                val booked = foreign.baseNet + revalued
                val rate = fxRates.rate(ctx.end, currency, ctx.base)?.rate
                if (rate == null) {
                    ctx.warnings += "No $currency/${ctx.base} rate on ${ctx.end}; account $account not revalued"
                    return@forEach
                }
                val required = (foreign.net * rate).setScale(2, RoundingMode.HALF_EVEN)
                val (gain, loss) = PeriodEndMath.movement(required, booked)
                val attributes = mapOf("account" to account)
                post(ctx, FX_EVENT, ctx.end, "$account in $currency at the closing rate $rate", mapOf("gain" to gain, "loss" to loss), attributes, dims)
                post(
                    ctx,
                    FX_EVENT,
                    ctx.nextDay,
                    "Reversal of $account $currency revaluation at ${ctx.end}",
                    mapOf("gain" to loss, "loss" to gain),
                    attributes,
                    dims,
                )
                ctx.components += component(
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

    // ----------------------------------------------------------------------------------------------------------
    // Posting and helpers
    // ----------------------------------------------------------------------------------------------------------

    private fun post(
        ctx: Context,
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

    private fun convert(ctx: Context, amount: BigDecimal, currency: String): BigDecimal? =
        if (currency == ctx.base) amount else fxRates.rate(ctx.end, currency, ctx.base)?.let { amount * it.rate }

    private fun component(
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

    // ----------------------------------------------------------------------------------------------------------
    // Policies
    // ----------------------------------------------------------------------------------------------------------

    override fun requestPolicyChange(command: RequestPolicyChange): PolicyChangeView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        command.legalEntityId?.let { referenceData.requireLegalEntity(it) }
        command.legalEntityId?.let(actor::requireEntity)
        if (command.reason.isBlank()) throw ValidationException("A policy change needs a reason")
        when (command.kind) {
            PolicyKind.MTM -> if (command.bookType == null || command.treatment == null) {
                throw ValidationException(
                    "An MtM policy names a book type and a treatment",
                )
            }
            PolicyKind.NRV -> {
                val values = listOfNotNull(command.gooCostToSellPerMwh, command.allowanceCostToSell)
                if (values.size != 2 || command.expiryDays == null || command.currency.isNullOrBlank()) {
                    throw ValidationException("An NRV policy names costs to sell for certificates and allowances, expiry days and a currency")
                }
                if (values.any { it.signum() < 0 } || command.expiryDays < 0) throw ValidationException("Costs to sell and expiry days cannot be negative")
            }
        }
        val change = PolicyChangeView(
            Ids.newId(),
            command.legalEntityId,
            command.kind,
            command,
            PolicyChangeStatus.PENDING,
            actor.userId,
            clock.instant(),
            null,
            null,
        )
        repository.insertChange(change)
        events.publishEvent(PolicyChangeRequested(change))
        return change
    }

    /** From the approval task: the change applies and counts as signed off by the approver. */
    fun applyPolicyChange(changeId: UUID, by: String) {
        val change = pending(changeId)
        if (change.requestedBy == by) throw SegregationOfDutiesException("A policy change is approved by someone other than who requested it")
        val now = clock.instant()
        val c = change.change
        when (change.kind) {
            PolicyKind.MTM -> repository.upsertMtmPolicy(MtmPolicyView(Ids.newId(), c.legalEntityId, c.bookType!!, c.treatment!!, by, now))
            PolicyKind.NRV -> repository.upsertNrvPolicy(
                NrvPolicyView(
                    Ids.newId(),
                    c.legalEntityId,
                    c.gooCostToSellPerMwh!!,
                    c.allowanceCostToSell!!,
                    c.expiryDays!!,
                    c.currency!!.uppercase(),
                    by,
                    now,
                ),
            )
        }
        repository.decideChange(changeId, PolicyChangeStatus.APPLIED, by, now)
    }

    fun rejectPolicyChange(changeId: UUID, by: String) {
        pending(changeId)
        repository.decideChange(changeId, PolicyChangeStatus.REJECTED, by, clock.instant())
    }

    private fun pending(changeId: UUID): PolicyChangeView {
        val change = repository.change(changeId, lock = true) ?: throw NotFoundException("PolicyChange", changeId)
        if (change.status != PolicyChangeStatus.PENDING) throw ValidationException("Policy change $changeId is ${change.status}")
        return change
    }

    @Transactional(readOnly = true)
    override fun mtmPolicies(legalEntityId: UUID?): List<MtmPolicyView> {
        legalEntityId?.let(actorProvider.current()::requireEntity)
        return repository.mtmPolicies().filter { it.legalEntityId == null || it.legalEntityId == legalEntityId }
    }

    @Transactional(readOnly = true)
    override fun nrvPolicy(legalEntityId: UUID?): NrvPolicyView {
        legalEntityId?.let(actorProvider.current()::requireEntity)
        val all = repository.nrvPolicies()
        return all.firstOrNull { legalEntityId != null && it.legalEntityId == legalEntityId } ?: all.first { it.legalEntityId == null }
    }

    @Transactional(readOnly = true)
    override fun policyChanges(status: PolicyChangeStatus?): List<PolicyChangeView> =
        repository.changes(status).filter { actorProvider.current().canAccess(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun runs(legalEntityId: UUID, period: YearMonth?): List<PeriodEndRunView> {
        actorProvider.current().requireEntity(legalEntityId)
        return repository.runs(legalEntityId, period?.toString())
    }

    private companion object {
        const val MTM_EVENT = "PeriodEndMtm"
        const val NRV_EVENT = "PeriodEndNrv"
        const val PROVISION_EVENT = "PeriodEndProvision"
        const val GRANT_EVENT = "PeriodEndGrantRelease"
        const val FX_EVENT = "PeriodEndFxRevaluation"
        const val ALLOWANCE_KEY = "ALLOWANCE:"
    }
}
