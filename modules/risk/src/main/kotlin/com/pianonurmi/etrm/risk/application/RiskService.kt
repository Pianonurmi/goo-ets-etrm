package com.pianonurmi.etrm.risk.application

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
import com.pianonurmi.etrm.position.api.PositionQuery
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
import com.pianonurmi.etrm.risk.domain.PotentialFutureExposure
import com.pianonurmi.etrm.risk.domain.RiskRepository
import com.pianonurmi.etrm.risk.domain.Tenor
import com.pianonurmi.etrm.risk.domain.Utilisation
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementReports
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.DeliveryScheduleView
import com.pianonurmi.etrm.trade.api.FindingSeverity
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeFilter
import com.pianonurmi.etrm.trade.api.TradeLegView
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.slf4j.LoggerFactory
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
        when (command.type) {
            LimitType.CREDIT_EXPOSURE -> {
                val counterparty = command.scope.counterpartyId ?: throw ValidationException("A credit limit names its counterparty (or group parent)")
                referenceData.requireCounterparty(counterparty)
                if (command.currency.isNullOrBlank()) throw ValidationException("A credit limit has a currency")
            }
            LimitType.STOP_LOSS_MTD -> if (command.currency.isNullOrBlank()) throw ValidationException("A stop loss has a currency")
            LimitType.POSITION_NET -> if (command.scope.productFamily == null) throw ValidationException("A position limit names a product family")
            LimitType.TENOR_MAX_MONTHS, LimitType.COMPLIANCE_SHORT -> Unit
        }
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

    /** One limit against the current figures; group-wide credit limits use the exposures stored per entity. */
    fun evaluate(limit: RiskLimitView, today: LocalDate = LocalDate.now(clock)): LimitUtilisationView {
        val (value, detail) = measureLimit(limit, today, null)
        val utilisation = Utilisation.of(limit.id, value, limit, detail, clock.instant())
        repository.upsertUtilisation(utilisation)
        trackBreach(limit, utilisation)
        return utilisation
    }

    private fun activeLimits(legalEntityId: UUID?, today: LocalDate): List<RiskLimitView> =
        repository.limits(legalEntityId, includeGroupWide = true, statuses = setOf(LimitStatus.ACTIVE)).filter { it.validOn(today) }

    private fun measureLimit(limit: RiskLimitView, today: LocalDate, exclude: UUID?): Pair<BigDecimal, String?> = when (limit.type) {
        LimitType.CREDIT_EXPOSURE -> creditUsage(limit, today)
        LimitType.POSITION_NET -> positionNet(limit.legalEntityId!!, limit.scope, exclude).abs() to null
        LimitType.TENOR_MAX_MONTHS -> BigDecimal(maxTenor(openTrades(limit.legalEntityId!!, limit.scope, exclude), limit.scope, today)) to null
        LimitType.STOP_LOSS_MTD -> stopLoss(limit, today)
        LimitType.COMPLIANCE_SHORT -> {
            val year = limit.scope.complianceYear ?: today.year
            BigDecimal(maxOf(0L, -positions.complianceLadder(limit.legalEntityId!!, year).netPosition)) to "compliance year $year"
        }
    }

    private fun trackBreach(limit: RiskLimitView, utilisation: LimitUtilisationView) {
        val open = repository.openBreach(limit.id)
        when {
            utilisation.status == UtilisationStatus.BREACH && open == null -> {
                val breach = LimitBreachView(Ids.newId(), limit.id, utilisation.measuredAt, utilisation.value, utilisation.value, null, BreachStatus.OPEN)
                repository.insertBreach(breach)
                events.publishEvent(LimitBreached(breach, limit, utilisation))
            }
            utilisation.status == UtilisationStatus.BREACH && open != null && utilisation.value > open.peakValue ->
                repository.updateBreach(open.copy(peakValue = utilisation.value))
            utilisation.status != UtilisationStatus.BREACH && open != null -> {
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
        val counterparties = referenceData.counterparties().associateBy { it.id }
        val run = valuations.official(entity.id, today, orEarlier = true)
        val mtmByCounterparty = run?.let { valuations.results(it.id) }.orEmpty()
            .filter { it.status == ResultStatus.OK && it.mtmBase != null }
            .groupBy { it.counterpartyId }
            .mapValues { (_, rows) -> rows.groupBy { it.tradeId }.mapValues { (_, r) -> r.sumOf { it.mtmBase!! } } }
        val balances = settlement.counterpartyBalances(entity.id).groupBy { it.counterpartyId }
        val addOns = repository.pfeAddOns()
        val pfeByCounterparty = openTrades(entity.id, LimitScope(), null).groupBy { it.counterpartyId }
            .mapValues { (_, list) -> list.sumOf { pfe(it, addOns, base, today) } }
        val support = repository.creditSupportFor(null).filter { it.validOn(today) && (it.legalEntityId == null || it.legalEntityId == entity.id) }
            .groupBy { it.counterpartyId }
        val known = repository.exposures(entity.id).map { it.counterpartyId }
        (mtmByCounterparty.keys + balances.keys + pfeByCounterparty.keys + support.keys + known).toSet().forEach { counterpartyId ->
            val netting = counterparties[counterpartyId]?.nettingAgreed == true
            val mtm = CreditExposure.positiveMtm(mtmByCounterparty[counterpartyId].orEmpty().values, netting)
            val rows = balances[counterpartyId].orEmpty()
            val receivables = rows.filter { it.direction == SettlementDirection.RECEIVABLE }.sumOf { convert(it.total, it.currency, base, today) }
            val payables = rows.filter { it.direction == SettlementDirection.PAYABLE }.sumOf { convert(it.total, it.currency, base, today) }
            val collateral = support[counterpartyId].orEmpty().sumOf {
                convert(it.amount, it.currency, base, today).let { amount -> if (it.kind.received) amount else amount.negate() }
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
                ),
            )
        }
    }

    /** Exposure to the limit's counterparty and its group, over the limit's entity or all entities, in the limit's currency. */
    private fun creditUsage(limit: RiskLimitView, today: LocalDate): Pair<BigDecimal, String?> {
        val members = CounterpartyGroups.members(limit.scope.counterpartyId!!, parentMap())
        val exposures = repository.exposures(limit.legalEntityId).filter { it.counterpartyId in members }
        val total = exposures.sumOf { convert(it.exposure, it.currency, limit.currency!!, today) }
        return total to "${exposures.size} exposures over ${members.size} counterparties"
    }

    private fun parentMap(): Map<UUID, UUID?> = referenceData.counterparties().associate { it.id to it.parentCounterpartyId }

    /** Potential future exposure of a trade's remaining deliveries, in [currency]. */
    private fun pfe(trade: TradeView, addOns: List<PfeAddOnView>, currency: String, today: LocalDate): BigDecimal = trade.legs.sumOf { leg ->
        leg.deliveries.filter { it.remainingQuantity.signum() > 0 }.sumOf { schedule ->
            val price = unitPrice(trade, leg, schedule, today) ?: return@sumOf BigDecimal.ZERO
            val notional = convert(schedule.remainingQuantity * price, leg.priceCurrency.currencyCode, currency, today)
            PotentialFutureExposure.addOn(notional, PotentialFutureExposure.pct(addOns, trade.productFamily, Tenor.months(today, schedule.deliveryDate)))
        }
    }

    /** The leg's price per unit: fixed, or for index legs today's forward price plus the spread. */
    private fun unitPrice(trade: TradeView, leg: TradeLegView, schedule: DeliveryScheduleView, today: LocalDate): BigDecimal? = when (leg.priceType) {
        PriceType.FIXED -> leg.price
        else -> when (trade.productFamily) {
            ProductFamily.EMISSION_ALLOWANCE -> leg.allowanceProductCode?.let { curves.forwardPrice(it, schedule.deliveryDate, today) }
            ProductFamily.GUARANTEE_OF_ORIGIN -> leg.bucketCode?.let { curves.vintagePrice(it, schedule.deliveryDate.year, today) }
        }?.let { convert(it.value, it.currency, leg.priceCurrency.currencyCode, today) + leg.price }
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

    /** Signed quantity still to deliver: purchases positive, sales negative. */
    private fun netDeliveries(trade: TradeView, scope: LimitScope): BigDecimal = legsInScope(trade, scope).sumOf { leg ->
        leg.deliveries.sumOf { it.remainingQuantity }.let { if (leg.side == Side.BUY) it else it.negate() }
    }

    /** Net position: open trades plus inventory on accounts that belong to the book (or to the entity, without a book). */
    private fun positionNet(legalEntityId: UUID, scope: LimitScope, exclude: UUID?): BigDecimal {
        val open = openTrades(legalEntityId, scope, exclude).sumOf { netDeliveries(it, scope) }
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
        .flatMap { leg -> leg.deliveries.filter { it.remainingQuantity.signum() > 0 } }
        .maxOfOrNull { Tenor.months(today, it.deliveryDate) } ?: 0

    /** Loss of MtM since the end of last month (in the book, if the limit names one), in the limit's currency. */
    private fun stopLoss(limit: RiskLimitView, today: LocalDate): Pair<BigDecimal, String?> {
        val explain = valuations.explain(
            limit.legalEntityId!!,
            today.withDayOfMonth(1).minusDays(1),
            today,
        ) ?: return BigDecimal.ZERO to "no official runs to compare"
        val change = limit.scope.bookId?.let { book -> explain.rows.filter { it.bookId == book }.sumOf { it.amountBase } } ?: (explain.mtmTo - explain.mtmFrom)
        val loss = convert(change.negate().max(BigDecimal.ZERO), explain.baseCurrency, limit.currency!!, today)
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
            limits.filter { (it.type == LimitType.STOP_LOSS_MTD && inScope(trade, it.scope)) || it.type == LimitType.COMPLIANCE_SHORT }
                .filter { repository.utilisation(it.id)?.status == UtilisationStatus.BREACH && relevant(trade, it) }
                .map { ApprovalFinding("LIMIT_IN_BREACH", FindingSeverity.WARN, "${it.type} limit ${it.id} is in breach") }
    }

    /** The pre-trade check of an existing trade against current limits (what-if, nothing is stored). */
    @Transactional(readOnly = true)
    fun recheck(tradeId: UUID): List<ApprovalFinding> {
        val trade = trades.requireTrade(tradeId)
        readable(trade.legalEntityId)
        return preTradeFindings(trade)
    }

    private fun relevant(trade: TradeView, limit: RiskLimitView): Boolean =
        limit.type != LimitType.COMPLIANCE_SHORT || trade.productFamily == ProductFamily.EMISSION_ALLOWANCE

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
            val current = creditUsage(limit, today).first
            val added = pfe(trade, addOns, limit.currency!!, today)
            val projected = current + added
            val text = "credit exposure to ${counterparty.code} would be ${money(
                projected,
            )} of ${limit.amount} ${limit.currency} (PFE of this trade ${money(added)})"
            when {
                projected > limit.amount -> ApprovalFinding("CREDIT_LIMIT", FindingSeverity.REQUIRES_RISK_APPROVAL, "Over the limit: $text")
                projected * BigDecimal(
                    PERCENT,
                ) >= limit.amount * BigDecimal(limit.warningPct) -> ApprovalFinding("CREDIT_LIMIT_WARNING", FindingSeverity.WARN, text)
                else -> null
            }
        }
    }

    private fun positionFinding(trade: TradeView, limit: RiskLimitView): ApprovalFinding? {
        val projected = (positionNet(limit.legalEntityId!!, limit.scope, trade.id) + netDeliveries(trade, limit.scope)).abs()
        return if (projected > limit.amount) {
            ApprovalFinding(
                "POSITION_LIMIT",
                FindingSeverity.REQUIRES_RISK_APPROVAL,
                "Net position would be ${projected.toPlainString()} against a limit of ${limit.amount}",
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

    private fun convert(amount: BigDecimal, from: String, to: String, date: LocalDate): BigDecimal {
        if (from == to || amount.signum() == 0) return amount
        val rate = fxRates.rate(date, from, to)?.rate
        if (rate == null) {
            log.warn("No {}/{} rate on {}; {} {} left out of risk measurement", from, to, date, amount, from)
            return BigDecimal.ZERO
        }
        return amount * rate
    }

    private fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_EVEN)

    private companion object {
        const val PERCENT = 100
    }
}
