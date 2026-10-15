package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.allocation.api.SaleProjection
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.MarginCallQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.marketdata.api.CurveQuery
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.position.api.PositionQuery
import com.pianonurmi.etrm.product.api.ProductBucketQuery
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.CounterpartyType
import com.pianonurmi.etrm.referencedata.api.CounterpartyView
import com.pianonurmi.etrm.referencedata.api.LegalEntityView
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.BreachStatus
import com.pianonurmi.etrm.risk.api.CounterpartyExposureView
import com.pianonurmi.etrm.risk.api.CreditSupportRequested
import com.pianonurmi.etrm.risk.api.CreditSupportStatus
import com.pianonurmi.etrm.risk.api.CreditSupportView
import com.pianonurmi.etrm.risk.api.LimitActivated
import com.pianonurmi.etrm.risk.api.LimitBreachResolved
import com.pianonurmi.etrm.risk.api.LimitBreachView
import com.pianonurmi.etrm.risk.api.LimitBreached
import com.pianonurmi.etrm.risk.api.LimitChangeRequested
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.LimitUtilisationView
import com.pianonurmi.etrm.risk.api.PfeAddOnView
import com.pianonurmi.etrm.risk.api.RequestCreditSupport
import com.pianonurmi.etrm.risk.api.RequestLimit
import com.pianonurmi.etrm.risk.api.RiskCommand
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.RiskQuery
import com.pianonurmi.etrm.risk.api.UtilisationStatus
import com.pianonurmi.etrm.risk.domain.CounterpartyGroups
import com.pianonurmi.etrm.risk.domain.CreditExposure
import com.pianonurmi.etrm.risk.domain.MeasurementGaps
import com.pianonurmi.etrm.risk.domain.PotentialFutureExposure
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.risk.domain.Tenor
import com.pianonurmi.etrm.risk.domain.Utilisation
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementReports
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.FindingSeverity
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.OptionValuation
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Trade statuses that count as open positions and exposures. */
private val OPEN = setOf(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)

/** Counterparties that carry no bilateral credit risk to limit. */
private val NO_CREDIT_LIMIT = setOf(CounterpartyType.INTERCOMPANY, CounterpartyType.EXCHANGE, CounterpartyType.CLEARING_HOUSE)

/**
 * Risk limits and their measurement (ADR 0022). Limits and credit support take effect only after a second person in
 * risk approves them. Measurement stores each counterparty's current exposure and each limit's utilisation, and
 * opens or closes breaches.
 */
@Service
@Transactional
class RiskService(
    private val repository: RiskRepository,
    private val referenceData: ReferenceDataQuery,
    private val trades: TradeQuery,
    private val valuations: ValuationQuery,
    private val settlement: SettlementReports,
    private val positions: PositionQuery,
    private val holdings: AllowanceHoldingQuery,
    private val lots: LotQuery,
    private val curves: CurveQuery,
    private val fxRates: FxRates,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    /** Every module's pre-trade check (this one's through [RiskTriggers]), for [recheck]; resolved when used. */
    private val checks: ObjectProvider<TradeApprovalCheck>,
    /** GoO measures from allocation (ADR 0040); resolved when used. */
    private val goo: ObjectProvider<GooRiskQuery>,
    private val buckets: ProductBucketQuery,
    private val savepoints: Savepoints,
    /** Cleared futures and the margin held for them (ADR 0046). */
    private val clearing: ClearingQuery,
    private val margin: MarginCallQuery,
) : RiskQuery, RiskCommand {
    private val log = LoggerFactory.getLogger(RiskService::class.java)

    // ----------------------------------------------------------------------------------------------------------
    // Limits and credit support under four eyes
    // ----------------------------------------------------------------------------------------------------------

    override fun requestLimit(command: RequestLimit): RiskLimitView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK)
        command.legalEntityId?.let { referenceData.requireLegalEntity(it) }
        command.legalEntityId?.let(actor::requireEntity)
        validate(command)
        val limit = RiskLimitView(
            Ids.newId(),
            command.legalEntityId,
            command.type,
            command.scope.copy(allowanceProductCode = command.scope.allowanceProductCode?.uppercase()),
            command.amount.setScale(2, RoundingMode.HALF_EVEN),
            command.currency?.uppercase(),
            command.warningPct,
            command.validFrom ?: LocalDate.now(clock),
            command.validTo,
            LimitStatus.PENDING_APPROVAL,
            command.replaces,
            command.reason.trim(),
            actor.userId,
            clock.instant(),
            null,
            null,
        )
        repository.insertLimit(limit)
        events.publishEvent(LimitChangeRequested(limit))
        return limit
    }

    private fun validate(command: RequestLimit) {
        if (command.reason.isBlank()) throw ValidationException("A limit change needs a reason")
        if (command.amount.signum() < 0) throw ValidationException("A limit cannot be negative")
        if (command.warningPct !in 1..PERCENT) throw ValidationException("The warning level is a percentage from 1 to 100")
        if (command.validTo != null && command.validFrom != null && !command.validTo.isAfter(
                command.validFrom,
            )
        ) {
            throw ValidationException("validTo must follow validFrom")
        }
        validateScope(command)
        if (command.type != LimitType.CREDIT_EXPOSURE && command.legalEntityId == null) throw ValidationException("Only credit limits apply across entities")
        command.scope.bookId?.let { book ->
            if (referenceData.requireBook(book).legalEntityId != command.legalEntityId) throw ValidationException("The book belongs to another legal entity")
        }
        command.replaces?.let { id ->
            val replaced = repository.limit(id) ?: throw NotFoundException("RiskLimit", id)
            if (replaced.status != LimitStatus.ACTIVE || replaced.type != command.type) {
                throw ValidationException(
                    "Only an active limit of the same type can be replaced",
                )
            }
        }
    }

    /** From the approval task: the limit becomes active, replacing the one it names. */
    fun approveLimit(id: UUID, by: String): RiskLimitView {
        val limit = repository.limit(id, lock = true) ?: throw NotFoundException("RiskLimit", id)
        if (limit.status != LimitStatus.PENDING_APPROVAL) throw ValidationException("Limit $id is ${limit.status}")
        if (limit.requestedBy == by) throw SegregationOfDutiesException("A limit is approved by someone other than who requested it")
        val now = clock.instant()
        limit.replaces?.let { replaced ->
            repository.setLimitStatus(replaced, LimitStatus.RETIRED, null, null)
            // A breach of the replaced limit ends with it; the new limit is measured on its own.
            repository.openBreach(replaced)?.let { breach ->
                val closed = breach.copy(closedAt = now, status = BreachStatus.CLOSED)
                repository.updateBreach(closed)
                events.publishEvent(LimitBreachResolved(closed, repository.limit(replaced)!!))
            }
        }
        repository.setLimitStatus(id, LimitStatus.ACTIVE, by, now)
        val active = limit.copy(status = LimitStatus.ACTIVE, approvedBy = by, approvedAt = now)
        events.publishEvent(LimitActivated(active))
        return active
    }

    fun rejectLimit(id: UUID) {
        val limit = repository.limit(id, lock = true) ?: throw NotFoundException("RiskLimit", id)
        if (limit.status != LimitStatus.PENDING_APPROVAL) throw ValidationException("Limit $id is ${limit.status}")
        repository.setLimitStatus(id, LimitStatus.REJECTED, null, null)
    }

    override fun requestCreditSupport(command: RequestCreditSupport): CreditSupportView {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.BACK_OFFICE)
        command.legalEntityId?.let(actor::requireEntity)
        referenceData.requireCounterparty(command.counterpartyId)
        if (command.amount.signum() <= 0) throw ValidationException("Credit support amount must be positive")
        val support = CreditSupportView(
            Ids.newId(),
            command.legalEntityId,
            command.counterpartyId,
            command.kind,
            command.amount,
            command.currency.uppercase(),
            command.validFrom,
            command.validTo,
            command.reference,
            CreditSupportStatus.PENDING_APPROVAL,
            actor.userId,
            clock.instant(),
            null,
            null,
        )
        repository.insertCreditSupport(support)
        events.publishEvent(CreditSupportRequested(support))
        return support
    }

    fun approveCreditSupport(id: UUID, by: String): CreditSupportView {
        val support = repository.creditSupport(id, lock = true) ?: throw NotFoundException("CreditSupport", id)
        if (support.status != CreditSupportStatus.PENDING_APPROVAL) throw ValidationException("Credit support $id is ${support.status}")
        if (support.requestedBy == by) throw SegregationOfDutiesException("Credit support is approved by someone other than who recorded it")
        val now = clock.instant()
        repository.setCreditSupportStatus(id, CreditSupportStatus.ACTIVE, by, now)
        return support.copy(status = CreditSupportStatus.ACTIVE, approvedBy = by, approvedAt = now)
    }

    fun rejectCreditSupport(id: UUID) {
        val support = repository.creditSupport(id, lock = true) ?: throw NotFoundException("CreditSupport", id)
        if (support.status != CreditSupportStatus.PENDING_APPROVAL) throw ValidationException("Credit support $id is ${support.status}")
        repository.setCreditSupportStatus(id, CreditSupportStatus.REJECTED, null, null)
    }

    // ----------------------------------------------------------------------------------------------------------
    // Measurement
    // ----------------------------------------------------------------------------------------------------------

    override fun measure(legalEntityId: UUID): List<LimitUtilisationView> {
        actorProvider.current().requireRole(Role.RISK)
        actorProvider.current().requireEntity(legalEntityId)
        return measureEntity(legalEntityId)
    }

    /** Exposures and limits of one entity, for jobs and listeners (no role check). */
    fun measureEntity(legalEntityId: UUID): List<LimitUtilisationView> {
        val entity = referenceData.requireLegalEntity(legalEntityId)
        val today = LocalDate.now(clock)
        measureExposures(entity, today)
        return activeLimits(legalEntityId, today).map { evaluate(it, today) }
    }

    /** What each limit type needs in its scope and currency. */
    private fun validateScope(command: RequestLimit) {
        when (command.type) {
            LimitType.CREDIT_EXPOSURE -> {
                val counterparty = command.scope.counterpartyId ?: throw ValidationException("A credit limit names its counterparty (or group parent)")
                referenceData.requireCounterparty(counterparty)
                if (command.currency.isNullOrBlank()) throw ValidationException("A credit limit has a currency")
            }
            LimitType.STOP_LOSS_MTD -> if (command.currency.isNullOrBlank()) throw ValidationException("A stop loss has a currency")
            LimitType.MARGIN_LIQUIDITY -> if (command.currency.isNullOrBlank()) throw ValidationException("A margin liquidity limit has a currency")
            LimitType.POSITION_NET -> if (command.scope.productFamily == null) throw ValidationException("A position limit names a product family")
            LimitType.TENOR_MAX_MONTHS, LimitType.COMPLIANCE_SHORT -> Unit
            LimitType.GOO_CONCENTRATION -> {
                val code = command.scope.bucketCode ?: throw ValidationException("A concentration limit names its product bucket")
                if (buckets.buckets(true).none { it.code == code }) throw ValidationException("No active product bucket $code")
                requireEurOrMwh(command)
            }
            LimitType.GOO_EXPIRY -> {
                val days = command.scope.horizonDays ?: throw ValidationException("An expiry limit has a horizon in days")
                if (days !in 1..MAX_HORIZON_DAYS) throw ValidationException("The horizon is 1 to $MAX_HORIZON_DAYS days")
                if (!command.currency.isNullOrBlank()) throw ValidationException("An expiry limit is in MWh")
            }
            LimitType.GOO_BASIS -> requireEurOrMwh(command)
        }
        command.scope.productionYear?.let { year ->
            if (command.type !in GOO_LIMITS) throw ValidationException("Only GoO limits are set per vintage")
            if (year !in MIN_VINTAGE..MAX_VINTAGE) throw ValidationException("The vintage is a year from $MIN_VINTAGE to $MAX_VINTAGE")
        }
        if (command.type in GOO_LIMITS && command.scope.bookId != null) {
            // Certificates serve obligations across books: the flow nets the whole entity, it cannot be split by book.
            throw ValidationException("GoO limits apply to the whole legal entity, not to a book")
        }
    }

    private fun requireEurOrMwh(command: RequestLimit) {
        if (!command.currency.isNullOrBlank() && command.currency != EUR) throw ValidationException("A GoO limit is in MWh, or in EUR")
    }

    /**
     * One limit against the current figures; group-wide credit limits use the exposures stored per entity. An overload
     * rather than a default date: called through the transactional proxy, a default would read the proxy's own
     * (unset) clock.
     */
    fun evaluate(limit: RiskLimitView): LimitUtilisationView = evaluate(limit, LocalDate.now(clock))

    fun evaluate(limit: RiskLimitView, today: LocalDate): LimitUtilisationView {
        val measure = measureLimit(limit, today, null)
        val last = repository.utilisation(limit.id)?.value ?: BigDecimal.ZERO
        val now = clock.instant()
        val utilisation = when {
            measure.notMeasured -> Utilisation.notMeasured(limit.id, last, limit, measure.detail, now, measure.gaps)
            // Something was left out, so the figure is a floor: the last value measured stays when it was higher.
            measure.gaps.isFloor -> Utilisation.of(limit.id, measure.value.max(last), limit, measure.detail, now, measure.gaps)
            // An assumption stands in for a figure (an option at delta one): the value is the conservative one, marked.
            else -> Utilisation.of(limit.id, measure.value, limit, measure.detail, now, measure.gaps)
        }
        repository.upsertUtilisation(utilisation)
        trackBreach(limit, utilisation)
        return utilisation
    }

    private fun activeLimits(legalEntityId: UUID?, today: LocalDate): List<RiskLimitView> =
        repository.limits(legalEntityId, includeGroupWide = true, statuses = setOf(LimitStatus.ACTIVE)).filter { it.validOn(today) }

    /** One limit's figure; [gaps] names what it left out, [notMeasured] that there is no figure at all (the last one stays). */
    private class Measure(val value: BigDecimal, val detail: String?, val gaps: MeasurementGaps = MeasurementGaps(), val notMeasured: Boolean = false)

    private fun measureLimit(limit: RiskLimitView, today: LocalDate, exclude: UUID?): Measure {
        val gaps = MeasurementGaps()
        return when (limit.type) {
            LimitType.CREDIT_EXPOSURE -> creditUsage(limit, today, gaps).let { (value, detail) -> Measure(value, detail, gaps) }
            LimitType.POSITION_NET -> Measure(positionNet(limit.legalEntityId!!, limit.scope, exclude, gaps).abs(), null, gaps)
            LimitType.TENOR_MAX_MONTHS ->
                Measure(BigDecimal(maxTenor(openTrades(limit.legalEntityId!!, limit.scope, exclude), limit.scope, today)), null)
            LimitType.STOP_LOSS_MTD -> stopLoss(limit, today, gaps).let { (value, detail) -> Measure(value, detail, gaps) }
            LimitType.COMPLIANCE_SHORT -> {
                val year = limit.scope.complianceYear ?: today.year
                Measure(BigDecimal(maxOf(0L, -positions.complianceLadder(limit.legalEntityId!!, year).netPosition)), "compliance year $year")
            }
            LimitType.GOO_CONCENTRATION, LimitType.GOO_EXPIRY, LimitType.GOO_BASIS -> gooMeasure(limit)
            LimitType.MARGIN_LIQUIDITY -> marginLiquidity(limit, today, gaps).let { (value, detail) -> Measure(value, detail, gaps) }
        }
    }

    /** Initial margin held plus open margin calls over the entity's clearing accounts, in the limit's currency. */
    private fun marginLiquidity(limit: RiskLimitView, today: LocalDate, gaps: MeasurementGaps): Pair<BigDecimal, String?> {
        val posted = margin.marginPosted(limit.legalEntityId!!)
        val initial = posted.sumOf { convert(it.initialMargin, it.currency, limit.currency!!, today, gaps) }
        val calls = posted.sumOf { convert(it.openCalls, it.currency, limit.currency!!, today, gaps) }
        return money(initial + calls) to "initial margin ${money(initial)}, open calls ${money(calls)} over ${posted.size} clearing accounts"
    }

    /**
     * GoO measures come from allocation's flow (ADR 0040). Measured in a savepoint: a limit that cannot be measured is
     * NOT_MEASURED (it keeps its last value and counts as a breach) rather than failing the measurement of every other
     * limit, or standing at zero.
     */
    private fun gooMeasure(limit: RiskLimitView): Measure {
        val query = goo.ifAvailable ?: return notMeasured("GoO measures are not available")
        val entity = limit.legalEntityId!!
        val inEur = limit.currency == EUR
        val year = limit.scope.productionYear
        val vintage = year?.let { " (vintage $it)" }.orEmpty()
        return try {
            savepoints.attempt {
                when (limit.type) {
                    LimitType.GOO_CONCENTRATION -> {
                        val code = limit.scope.bucketCode!!
                        val measured = query.concentration(entity, code, year) ?: return@attempt notMeasured("no active bucket $code")
                        if (inEur) {
                            Measure(
                                measured.valueEur ?: BigDecimal.ZERO,
                                "${measured.freeMwh} MWh of $code$vintage free" + (measured.markEur?.let { " at $it EUR" } ?: ", no mark"),
                            )
                        } else {
                            Measure(BigDecimal(measured.freeMwh), "$code$vintage certificates no obligation uses")
                        }
                    }
                    LimitType.GOO_EXPIRY -> {
                        val days = limit.scope.horizonDays!!
                        Measure(BigDecimal(query.unusedExpiringMwh(entity, days, limit.scope.bucketCode, year)), "unused$vintage, expiring within $days days")
                    }
                    else -> {
                        val basis = query.basis(entity, year) ?: return@attempt notMeasured("the allocation cannot be solved here")
                        if (inEur) {
                            Measure(basis.giveAwayEur, "${basis.mwh} MWh$vintage delivered above their product")
                        } else {
                            Measure(BigDecimal(basis.mwh), "give-away$vintage ${basis.giveAwayEur} EUR")
                        }
                    }
                }
            }
        } catch (e: RuntimeException) {
            log.warn("GoO limit {} could not be measured", limit.id, e)
            notMeasured(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun notMeasured(reason: String) = Measure(BigDecimal.ZERO, "not measured: $reason", notMeasured = true)

    private fun trackBreach(limit: RiskLimitView, utilisation: LimitUtilisationView) {
        val open = repository.openBreach(limit.id)
        when {
            utilisation.status.breached && open == null -> {
                val breach = LimitBreachView(Ids.newId(), limit.id, utilisation.measuredAt, utilisation.value, utilisation.value, null, BreachStatus.OPEN)
                repository.insertBreach(breach)
                events.publishEvent(LimitBreached(breach, limit, utilisation))
            }
            utilisation.status.breached && open != null && utilisation.value > open.peakValue ->
                repository.updateBreach(open.copy(peakValue = utilisation.value))
            !utilisation.status.breached && open != null -> {
                val closed = open.copy(closedAt = utilisation.measuredAt, status = BreachStatus.CLOSED)
                repository.updateBreach(closed)
                events.publishEvent(LimitBreachResolved(closed, limit))
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Credit exposure
    // ----------------------------------------------------------------------------------------------------------

    private fun measureExposures(entity: LegalEntityView, today: LocalDate) {
        val base = entity.baseCurrency.currencyCode
        // Margined futures are settled every day: no MtM or PFE to the clearing house, which holds the margin instead.
        val margined = clearing.marginedTradeIds(entity.id)
        val posted = margin.marginPosted(entity.id).groupBy { it.clearingHouseId }
        val counterparties = referenceData.counterparties().associateBy { it.id }
        val run = valuations.official(entity.id, today, orEarlier = true)
        val mtmByCounterparty = run?.let { valuations.results(it.id) }.orEmpty()
            .filter { it.status == ResultStatus.OK && it.mtmBase != null && it.tradeId !in margined }
            .groupBy { it.counterpartyId }
            .mapValues { (_, rows) -> rows.groupBy { it.tradeId }.mapValues { (_, r) -> r.sumOf { it.mtmBase!! } } }
        val balances = settlement.counterpartyBalances(entity.id).groupBy { it.counterpartyId }
        val addOns = repository.pfeAddOns()
        val gapsByCounterparty = mutableMapOf<UUID, MeasurementGaps>()
        val pfeByCounterparty = openTrades(entity.id, LimitScope(), null).filter { it.id !in margined }.groupBy { it.counterpartyId }
            .mapValues { (counterpartyId, list) ->
                val gaps = gapsByCounterparty.getOrPut(counterpartyId) { MeasurementGaps() }
                list.sumOf { pfe(it, addOns, base, today, gaps) }
            }
        val support = repository.creditSupportFor(null).filter { it.validOn(today) && (it.legalEntityId == null || it.legalEntityId == entity.id) }
            .groupBy { it.counterpartyId }
        val known = repository.exposures(entity.id).map { it.counterpartyId }
        (mtmByCounterparty.keys + balances.keys + pfeByCounterparty.keys + support.keys + posted.keys + known).toSet().forEach { counterpartyId ->
            val netting = counterparties[counterpartyId]?.nettingAgreed == true
            val gaps = gapsByCounterparty.getOrPut(counterpartyId) { MeasurementGaps() }
            val mtm = CreditExposure.positiveMtm(mtmByCounterparty[counterpartyId].orEmpty().values, netting)
            val rows = balances[counterpartyId].orEmpty()
            val receivables = rows.filter { it.direction == SettlementDirection.RECEIVABLE }.sumOf { convert(it.total, it.currency, base, today, gaps) }
            val payables = rows.filter { it.direction == SettlementDirection.PAYABLE }.sumOf { convert(it.total, it.currency, base, today, gaps) }
            val collateral = support[counterpartyId].orEmpty().sumOf {
                convert(it.amount, it.currency, base, today, gaps).let { amount -> if (it.kind.received) amount else amount.negate() }
            } - posted[counterpartyId].orEmpty().sumOf {
                convert(it.initialMargin + (it.cashBalance ?: BigDecimal.ZERO).max(BigDecimal.ZERO), it.currency, base, today, gaps)
            }
            val pfe = pfeByCounterparty[counterpartyId] ?: BigDecimal.ZERO
            repository.upsertExposure(
                CounterpartyExposureView(
                    entity.id,
                    counterpartyId,
                    base,
                    money(mtm),
                    money(receivables),
                    money(if (netting) payables else BigDecimal.ZERO),
                    money(pfe),
                    money(collateral),
                    CreditExposure.exposure(mtm, receivables, payables, netting, pfe, collateral),
                    run?.id,
                    clock.instant(),
                    !gaps.isComplete,
                    gaps.currencies,
                    gaps.reasons,
                ),
            )
        }
    }

    /** Exposure to the limit's counterparty and its group, over the limit's entity or all entities, in the limit's currency. */
    private fun creditUsage(limit: RiskLimitView, today: LocalDate, gaps: MeasurementGaps): Pair<BigDecimal, String?> {
        val members = CounterpartyGroups.members(limit.scope.counterpartyId!!, parentMap())
        val exposures = repository.exposures(limit.legalEntityId).filter { it.counterpartyId in members }
        // An exposure that left something out passes that on: the limit is only as complete as the figures under it.
        exposures.filter { it.incomplete }.forEach { gaps.addAll(it.leftOutCurrencies, it.incompleteReasons) }
        val total = exposures.sumOf { convert(it.exposure, it.currency, limit.currency!!, today, gaps) }
        return total to "${exposures.size} exposures over ${members.size} counterparties"
    }

    private fun parentMap(): Map<UUID, UUID?> = referenceData.counterparties().associate { it.id to it.parentCounterpartyId }

    /** Potential future exposure of a trade's remaining deliveries (an option's delta-equivalent), in [currency]. */
    private fun pfe(trade: TradeView, addOns: List<PfeAddOnView>, currency: String, today: LocalDate, gaps: MeasurementGaps): BigDecimal =
        trade.legs.sumOf { leg ->
            leg.option?.let { terms ->
                val greeks = optionValuation(trade, leg, today) ?: run {
                    gaps.omitted("option of trade ${trade.tradeNumber} has no value: its PFE is left out")
                    return@sumOf BigDecimal.ZERO
                }
                val notional = convert(
                    (leg.quantity * greeks.delta * greeks.underlyingPrice).abs(),
                    leg.priceCurrency.currencyCode,
                    currency,
                    today,
                    gaps,
                )
                return@sumOf PotentialFutureExposure.addOn(
                    notional,
                    PotentialFutureExposure.pct(addOns, trade.productFamily, Tenor.months(today, terms.expiryDate)),
                )
            }
            leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.sumOf { schedule ->
                val price = unitPrice(trade, leg, schedule, today, gaps) ?: run {
                    gaps.omitted("no price for trade ${trade.tradeNumber} on ${schedule.deliveryDate}: its PFE is left out")
                    return@sumOf BigDecimal.ZERO
                }
                val notional = convert(schedule.remainingQuantity * price, leg.priceCurrency.currencyCode, currency, today, gaps)
                PotentialFutureExposure.addOn(notional, PotentialFutureExposure.pct(addOns, trade.productFamily, Tenor.months(today, schedule.deliveryDate)))
            }
        }

    /** The leg's price per unit: fixed, or for index legs today's forward price plus the spread. */
    private fun unitPrice(trade: TradeView, leg: TradeLegView, schedule: DeliveryScheduleView, today: LocalDate, gaps: MeasurementGaps): BigDecimal? =
        when (leg.priceType) {
            PriceType.FIXED -> leg.price
            else -> when (trade.productFamily) {
                ProductFamily.EMISSION_ALLOWANCE -> leg.allowanceProductCode?.let { curves.forwardPrice(it, schedule.deliveryDate, today) }
                ProductFamily.GUARANTEE_OF_ORIGIN -> leg.bucketCode?.let { curves.vintagePrice(it, schedule.deliveryDate.year, today) }
            }?.let { convert(it.value, it.currency, leg.priceCurrency.currencyCode, today, gaps) + leg.price }
        }

    // ----------------------------------------------------------------------------------------------------------
    // Positions, tenor, stop loss
    // ----------------------------------------------------------------------------------------------------------

    private fun openTrades(legalEntityId: UUID, scope: LimitScope, exclude: UUID?): List<TradeView> =
        trades.trades(
            TradeFilter(legalEntityId = legalEntityId, bookId = scope.bookId, statuses = OPEN, productFamily = scope.productFamily, limit = Int.MAX_VALUE),
        )
            .filter { it.id != exclude }

    private fun legsInScope(trade: TradeView, scope: LimitScope): List<TradeLegView> =
        trade.legs.filter { scope.allowanceProductCode == null || it.allowanceProductCode == scope.allowanceProductCode }

    /**
     * Signed quantity still to deliver: purchases positive, sales negative. An option leg counts its delta-equivalent
     * (ADR 0047): quantity × delta, the full quantity when it cannot be valued, nothing once decided at expiry.
     */
    private fun netDeliveries(trade: TradeView, scope: LimitScope, gaps: MeasurementGaps): BigDecimal = legsInScope(trade, scope).sumOf { leg ->
        val quantity = when {
            leg.option == null -> leg.deliveries.sumOf { it.remainingQuantity }
            leg.expiry != null -> BigDecimal.ZERO
            else -> deltaEquivalent(trade, leg, gaps)
        }
        if (leg.side == Side.BUY) quantity else quantity.negate()
    }

    /**
     * A call's tonnes times its delta (a put's delta is negative). Without a value the full quantity counts, the
     * conservative figure, and the measurement says that it is incomplete.
     */
    private fun deltaEquivalent(trade: TradeView, leg: TradeLegView, gaps: MeasurementGaps): BigDecimal {
        val delta = optionValuation(trade, leg, LocalDate.now(clock))?.delta
            ?: (if (leg.option!!.optionType == OptionType.CALL) BigDecimal.ONE else BigDecimal.ONE.negate()).also {
                gaps.assumed("option of trade ${trade.tradeNumber} has no value: a delta of $it is assumed")
            }
        return leg.quantity * delta
    }

    /** Valued in a savepoint: a missing price or a role the valuation refuses must not fail the measuring transaction. */
    private fun optionValuation(trade: TradeView, leg: TradeLegView, today: LocalDate): OptionValuation? =
        try {
            savepoints.attempt { valuations.optionValuation(trade.legalEntityId, today, leg) }
        } catch (e: RuntimeException) {
            log.warn("Option leg {} of trade {} could not be valued", leg.id, trade.tradeNumber, e)
            null
        }

    /** Net position: open trades plus inventory on accounts that belong to the book (or to the entity, without a book). */
    private fun positionNet(legalEntityId: UUID, scope: LimitScope, exclude: UUID?, gaps: MeasurementGaps): BigDecimal {
        val open = openTrades(legalEntityId, scope, exclude).sumOf { netDeliveries(it, scope, gaps) }
        val inBook = { accountId: UUID -> scope.bookId == null || referenceData.registryAccount(accountId)?.defaultBookId == scope.bookId }
        val inventory = when (scope.productFamily) {
            ProductFamily.EMISSION_ALLOWANCE -> holdings.holdings(legalEntityId)
                .filter { (scope.allowanceProductCode == null || it.productCode == scope.allowanceProductCode) && inBook(it.registryAccountId) }
                .sumOf { it.quantity }.let(::BigDecimal)
            ProductFamily.GUARANTEE_OF_ORIGIN -> lots.lots(LotFilter(holderEntityId = legalEntityId, statuses = LotStatus.LIVE, limit = Int.MAX_VALUE))
                .filter { inBook(it.registryAccountId) }
                .sumOf { it.quantityMwh }.let(::BigDecimal)
            null -> BigDecimal.ZERO
        }
        return open + inventory
    }

    private fun maxTenor(trades: List<TradeView>, scope: LimitScope, today: LocalDate): Long = trades.flatMap { legsInScope(it, scope) }
        .flatMap { leg ->
            // An option runs to its expiry; exercise creates the future, which has its own delivery.
            leg.option?.takeIf { leg.expiry == null }?.let { listOf(it.expiryDate) }
                ?: leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.map { it.deliveryDate }
        }
        .maxOfOrNull { Tenor.months(today, it) } ?: 0

    /** Loss of MtM since the end of last month (in the book, if the limit names one), in the limit's currency. */
    private fun stopLoss(limit: RiskLimitView, today: LocalDate, gaps: MeasurementGaps): Pair<BigDecimal, String?> {
        val explain = valuations.explain(
            limit.legalEntityId!!,
            today.withDayOfMonth(1).minusDays(1),
            today,
        ) ?: return BigDecimal.ZERO to "no official runs to compare"
        val change = limit.scope.bookId?.let { book -> explain.rows.filter { it.bookId == book }.sumOf { it.amountBase } } ?: (explain.mtmTo - explain.mtmFrom)
        val loss = convert(change.negate().max(BigDecimal.ZERO), explain.baseCurrency, limit.currency!!, today, gaps)
        return loss to "MtM ${explain.fromDate} to ${explain.toDate}: $change ${explain.baseCurrency}"
    }

    // ----------------------------------------------------------------------------------------------------------
    // Pre-trade check
    // ----------------------------------------------------------------------------------------------------------

    /**
     * Findings for a trade about to be submitted: credit headroom after its potential future exposure (against the
     * last measured exposure), position and tenor limits it would exceed, and limits already in breach.
     */
    @Transactional(readOnly = true)
    fun preTradeFindings(trade: TradeView): List<ApprovalFinding> {
        val today = LocalDate.now(clock)
        val limits = activeLimits(trade.legalEntityId, today)
        val counterparty = referenceData.counterparty(trade.counterpartyId)
        return creditFindings(trade, counterparty, limits, today) +
            limits.filter { it.type == LimitType.POSITION_NET && inScope(trade, it.scope) }.mapNotNull { positionFinding(trade, it) } +
            limits.filter { it.type == LimitType.TENOR_MAX_MONTHS && inScope(trade, it.scope) }.mapNotNull { tenorFinding(trade, it, today) } +
            limits.filter { it.type == LimitType.GOO_CONCENTRATION && trade.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN }
                .mapNotNull { concentrationFinding(trade, it) } +
            basisFindings(trade, limits.filter { it.type == LimitType.GOO_BASIS }) +
            limits.filter { (it.type == LimitType.STOP_LOSS_MTD && inScope(trade, it.scope)) || it.type == LimitType.COMPLIANCE_SHORT || it.type in GOO_LIMITS }
                .filter { repository.utilisation(it.id)?.status?.breached == true && relevant(trade, it) }
                .map {
                    if (repository.utilisation(it.id)?.status == UtilisationStatus.NOT_MEASURED) {
                        ApprovalFinding("LIMIT_NOT_MEASURED", FindingSeverity.WARN, "${it.type} limit ${it.id} could not be measured and counts as in breach")
                    } else {
                        ApprovalFinding("LIMIT_IN_BREACH", FindingSeverity.WARN, "${it.type} limit ${it.id} is in breach")
                    }
                }
    }

    /**
     * The pre-trade checks of an existing trade, as they would run on submission (what-if, nothing is stored): this
     * module's limits and credit, and the other modules' checks, such as available to sell (allocation).
     */
    @Transactional(readOnly = true)
    fun recheck(tradeId: UUID): List<ApprovalFinding> {
        val trade = trades.requireTrade(tradeId)
        readable(trade.legalEntityId)
        return checks.orderedStream().toList().takeIf { it.isNotEmpty() }?.flatMap { it.check(trade) } ?: preTradeFindings(trade)
    }

    private fun relevant(trade: TradeView, limit: RiskLimitView): Boolean = when (limit.type) {
        LimitType.COMPLIANCE_SHORT -> trade.productFamily == ProductFamily.EMISSION_ALLOWANCE
        in GOO_LIMITS -> trade.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN
        else -> true
    }

    /**
     * A GoO sale would hand over the premium of the certificates it takes (ADR 0040, increment 10): the deliveries are
     * projected on the entity's free certificates, cheapest first, and the give-away added to each basis limit's last
     * measure (only deliveries of the limit's vintage when it has one). While the figures are being prepared the check
     * says so and never blocks.
     */
    private fun basisFindings(trade: TradeView, limits: List<RiskLimitView>): List<ApprovalFinding> {
        if (limits.isEmpty() || trade.productFamily != ProductFamily.GUARANTEE_OF_ORIGIN || trade.legs.none { it.side == Side.SELL }) return emptyList()
        val projection = projectSale(trade)
            ?: return listOf(ApprovalFinding("GOO_BASIS", FindingSeverity.INFO, "Give-away not checked: the figures for this entity are being prepared"))
        return limits.mapNotNull { basisFinding(it, projection) }
    }

    /** Null while allocation prepares the figures; empty when allocation is not there or the projection fails. */
    private fun projectSale(trade: TradeView): SaleProjection? {
        val query = goo.ifAvailable ?: return SaleProjection(emptyList())
        return try {
            savepoints.attempt { query.projectSale(trade) }
        } catch (e: RuntimeException) {
            log.warn("GoO basis of trade {} could not be projected", trade.tradeNumber, e)
            SaleProjection(emptyList())
        }
    }

    private fun basisFinding(limit: RiskLimitView, projection: SaleProjection): ApprovalFinding? {
        val deliveries = projection.deliveries.filter { limit.scope.productionYear == null || it.vintageYear == limit.scope.productionYear }
        val added = if (limit.currency == EUR) {
            deliveries.mapNotNull { it.giveAwayEur }.fold(BigDecimal.ZERO, BigDecimal::add)
        } else {
            BigDecimal(deliveries.sumOf { it.giveAwayMwh })
        }
        if (added.signum() <= 0) return null
        val projected = (repository.utilisation(limit.id)?.value ?: BigDecimal.ZERO) + added
        val unit = if (limit.currency == EUR) "EUR" else "MWh"
        val vintage = limit.scope.productionYear?.let { " (vintage $it)" }.orEmpty()
        val text = "the sale would hand over ${added.setScale(0, RoundingMode.HALF_UP)} $unit$vintage of premium; give-away would be " +
            "${projected.setScale(0, RoundingMode.HALF_UP)} of ${limit.amount}"
        return when {
            projected > limit.amount -> ApprovalFinding("GOO_BASIS", FindingSeverity.REQUIRES_RISK_APPROVAL, "Over the limit: $text")
            projected * BigDecimal(PERCENT) >= limit.amount * BigDecimal(limit.warningPct) -> ApprovalFinding("GOO_BASIS", FindingSeverity.WARN, text)
            else -> null
        }
    }

    /**
     * A GoO purchase of the limit's bucket adds to its free certificates once delivered; a sale does not add to it.
     * With a vintage, only its deliveries marked at it count. Projected on the last measured value, in MWh (or at the bucket's mark for a limit in EUR).
     */
    private fun concentrationFinding(trade: TradeView, limit: RiskLimitView): ApprovalFinding? = try {
        savepoints.attempt { concentrationProjection(trade, limit) }
    } catch (e: RuntimeException) {
        log.warn("GoO concentration of trade {} against limit {} could not be checked", trade.tradeNumber, limit.id, e)
        concentrationNotChecked(limit, e.message ?: e.javaClass.simpleName)
    }

    /** The check could not run: the trade is not waved through as if it were within the limit. */
    private fun concentrationNotChecked(limit: RiskLimitView, reason: String) = ApprovalFinding(
        "GOO_CONCENTRATION_NOT_CHECKED",
        FindingSeverity.WARN,
        "Concentration of ${limit.scope.bucketCode} not checked against limit ${limit.id}: $reason",
    )

    private fun concentrationProjection(trade: TradeView, limit: RiskLimitView): ApprovalFinding? {
        val year = limit.scope.productionYear
        val query = goo.ifAvailable ?: return concentrationNotChecked(limit, "GoO measures are not available")
        // A limit on one vintage counts only the deliveries marked at it.
        val vintages = if (year == null) emptyMap() else query.vintages(trade)
        val bought = trade.legs.filter { it.side == Side.BUY && it.bucketCode == limit.scope.bucketCode }
            .sumOf { leg -> leg.deliveries.filter { year == null || vintages[it.id] == year }.sumOf { it.quantity - it.deliveredQuantity } }
        if (bought.signum() <= 0) return null
        val current = repository.utilisation(limit.id)?.value ?: BigDecimal.ZERO
        val added = if (limit.currency == EUR) {
            val mark = query.concentration(limit.legalEntityId!!, limit.scope.bucketCode!!, year)?.markEur
                ?: return concentrationNotChecked(limit, "no mark for ${limit.scope.bucketCode}")
            bought * mark
        } else {
            bought
        }
        val projected = current + added
        val text = "${limit.scope.bucketCode} free certificates would be ${projected.setScale(0, RoundingMode.HALF_UP)} of ${limit.amount}"
        return when {
            projected > limit.amount -> ApprovalFinding("GOO_CONCENTRATION", FindingSeverity.REQUIRES_RISK_APPROVAL, "Over the limit: $text")
            projected * BigDecimal(PERCENT) >= limit.amount * BigDecimal(limit.warningPct) -> ApprovalFinding("GOO_CONCENTRATION", FindingSeverity.WARN, text)
            else -> null
        }
    }

    private fun inScope(trade: TradeView, scope: LimitScope): Boolean =
        (scope.bookId == null || scope.bookId == trade.bookId) &&
            (scope.productFamily == null || scope.productFamily == trade.productFamily) &&
            (scope.allowanceProductCode == null || trade.legs.any { it.allowanceProductCode == scope.allowanceProductCode })

    private fun creditFindings(trade: TradeView, counterparty: CounterpartyView?, limits: List<RiskLimitView>, today: LocalDate): List<ApprovalFinding> {
        if (counterparty == null || counterparty.counterpartyType in NO_CREDIT_LIMIT) return emptyList()
        val ancestry = CounterpartyGroups.ancestry(counterparty.id, parentMap()).toSet()
        val credit = limits.filter { it.type == LimitType.CREDIT_EXPOSURE && it.scope.counterpartyId in ancestry }
        if (credit.isEmpty()) return listOf(ApprovalFinding("NO_CREDIT_LIMIT", FindingSeverity.WARN, "No credit limit covers ${counterparty.code}"))
        val addOns = repository.pfeAddOns()
        return credit.mapNotNull { limit ->
            val gaps = MeasurementGaps()
            val current = creditUsage(limit, today, gaps).first
            val added = pfe(trade, addOns, limit.currency!!, today, gaps)
            val projected = current + added
            val incomplete = if (gaps.isComplete) "" else "; the check is incomplete (${gaps.summary()})"
            val text = "credit exposure to ${counterparty.code} would be ${money(
                projected,
            )} of ${limit.amount} ${limit.currency} (PFE of this trade ${money(added)})$incomplete"
            when {
                projected > limit.amount -> ApprovalFinding("CREDIT_LIMIT", FindingSeverity.REQUIRES_RISK_APPROVAL, "Over the limit: $text")
                projected * BigDecimal(
                    PERCENT,
                ) >= limit.amount * BigDecimal(limit.warningPct) -> ApprovalFinding("CREDIT_LIMIT_WARNING", FindingSeverity.WARN, text)
                // Below the limit on figures that left something out: say so rather than pass quietly.
                !gaps.isComplete -> ApprovalFinding(
                    "CREDIT_CHECK_INCOMPLETE",
                    FindingSeverity.WARN,
                    "Credit check for ${counterparty.code} is incomplete: ${gaps.summary()}",
                )
                else -> null
            }
        }
    }

    private fun positionFinding(trade: TradeView, limit: RiskLimitView): ApprovalFinding? {
        val gaps = MeasurementGaps()
        val projected = (positionNet(limit.legalEntityId!!, limit.scope, trade.id, gaps) + netDeliveries(trade, limit.scope, gaps)).abs()
        return if (projected > limit.amount) {
            val incomplete = if (gaps.isComplete) "" else " (incomplete: ${gaps.summary()})"
            ApprovalFinding(
                "POSITION_LIMIT",
                FindingSeverity.REQUIRES_RISK_APPROVAL,
                "Net position would be ${projected.toPlainString()} against a limit of ${limit.amount}$incomplete",
            )
        } else {
            null
        }
    }

    private fun tenorFinding(trade: TradeView, limit: RiskLimitView, today: LocalDate): ApprovalFinding? {
        val months = maxTenor(listOf(trade), limit.scope, today)
        return if (BigDecimal(months) > limit.amount) {
            ApprovalFinding("TENOR_LIMIT", FindingSeverity.REQUIRES_RISK_APPROVAL, "Delivers in $months months; the limit is ${limit.amount.toInt()} months")
        } else {
            null
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    private fun readable(legalEntityId: UUID?) {
        val actor = actorProvider.current()
        actor.requireRole(Role.RISK, Role.MIDDLE_OFFICE, Role.TRADER, Role.AUDITOR)
        legalEntityId?.let(actor::requireEntity)
    }

    @Transactional(readOnly = true)
    override fun limits(legalEntityId: UUID?, activeOnly: Boolean): List<RiskLimitView> {
        readable(legalEntityId)
        return repository.limits(legalEntityId, includeGroupWide = true, statuses = if (activeOnly) setOf(LimitStatus.ACTIVE) else null)
            .filter { actorProvider.current().canAccess(it.legalEntityId) }
    }

    @Transactional(readOnly = true)
    override fun limit(id: UUID): RiskLimitView? = repository.limit(id)?.also { readable(it.legalEntityId) }

    @Transactional(readOnly = true)
    override fun utilisation(limitId: UUID): LimitUtilisationView? {
        readable((repository.limit(limitId) ?: throw NotFoundException("RiskLimit", limitId)).legalEntityId)
        return repository.utilisation(limitId)
    }

    @Transactional(readOnly = true)
    override fun exposures(legalEntityId: UUID): List<CounterpartyExposureView> {
        readable(legalEntityId)
        return repository.exposures(legalEntityId)
    }

    @Transactional(readOnly = true)
    override fun breaches(legalEntityId: UUID?, openOnly: Boolean): List<LimitBreachView> {
        readable(legalEntityId)
        val ids = repository.limits(legalEntityId, includeGroupWide = true, statuses = null).filter {
            actorProvider.current().canAccess(it.legalEntityId)
        }.map { it.id }
        return if (ids.isEmpty()) emptyList() else repository.breaches(ids.toSet(), openOnly)
    }

    @Transactional(readOnly = true)
    override fun creditSupport(counterpartyId: UUID): List<CreditSupportView> {
        readable(null)
        return repository.creditSupportFor(counterpartyId).filter { actorProvider.current().canAccess(it.legalEntityId) }
    }

    @Transactional(readOnly = true)
    override fun pfeAddOns(): List<PfeAddOnView> = repository.pfeAddOns()

    /** Converts at the rate of [date]. With no rate the amount is left out (zero) and [gaps] records the currency, so the figure is marked incomplete. */
    private fun convert(amount: BigDecimal, from: String, to: String, date: LocalDate, gaps: MeasurementGaps): BigDecimal {
        if (from == to || amount.signum() == 0) return amount
        val rate = fxRates.rate(date, from, to)?.rate
        if (rate == null) {
            log.warn("No {}/{} rate on {}; {} {} left out of risk measurement", from, to, date, amount, from)
            gaps.missingRate(from, to, date)
            return BigDecimal.ZERO
        }
        return amount * rate
    }

    private fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_EVEN)

    private companion object {
        const val PERCENT = 100
        const val EUR = "EUR"
        const val MAX_HORIZON_DAYS = 730
        const val MIN_VINTAGE = 2000
        const val MAX_VINTAGE = 2100
        val GOO_LIMITS = setOf(LimitType.GOO_CONCENTRATION, LimitType.GOO_EXPIRY, LimitType.GOO_BASIS)
    }
}
