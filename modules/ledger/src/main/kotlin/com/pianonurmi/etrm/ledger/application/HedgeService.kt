package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.document.api.DocumentCommand
import com.pianonurmi.etrm.document.api.RenderPdf
import com.pianonurmi.etrm.ledger.api.HedgeCheck
import com.pianonurmi.etrm.ledger.api.HedgeCommand
import com.pianonurmi.etrm.ledger.api.HedgeDesignationRequested
import com.pianonurmi.etrm.ledger.api.HedgeDetail
import com.pianonurmi.etrm.ledger.api.HedgeDirection
import com.pianonurmi.etrm.ledger.api.HedgeDiscontinuation
import com.pianonurmi.etrm.ledger.api.HedgeDiscontinuationRequested
import com.pianonurmi.etrm.ledger.api.HedgeInstrumentView
import com.pianonurmi.etrm.ledger.api.HedgeMeasurementView
import com.pianonurmi.etrm.ledger.api.HedgeQuery
import com.pianonurmi.etrm.ledger.api.HedgeRelationshipView
import com.pianonurmi.etrm.ledger.api.HedgeStatus
import com.pianonurmi.etrm.ledger.api.HedgedItem
import com.pianonurmi.etrm.ledger.api.RequestHedgeDesignation
import com.pianonurmi.etrm.ledger.api.RequestHedgeDiscontinuation
import com.pianonurmi.etrm.ledger.domain.Effectiveness
import com.pianonurmi.etrm.ledger.domain.HedgeRepository
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.trade.api.PriceType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeQuery
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ForwardTerms
import com.pianonurmi.etrm.valuation.api.ResultStatus
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.UUID

/** What period end needs of the cash flow hedges: the reserve each one requires on a valuation run (ADR 0045). */
fun interface HedgePositions {
    fun positions(legalEntityId: UUID, run: ValuationRunView, warnings: MutableList<String>): List<HedgeService.HedgePosition>
}

/**
 * Cash flow hedge accounting (ADR 0045): relationships between hedging trades and a forecast purchase or sale,
 * designated and discontinued by one accountant and approved by another, measured on every official valuation run by
 * the lower of the instruments' change and a hypothetical derivative, and split at period end into the hedging reserve
 * and profit or loss ([PeriodEndService]).
 */

@Service
@Transactional
class HedgeService(
    private val repository: HedgeRepository,
    private val trades: TradeQuery,
    private val valuations: ValuationQuery,
    private val documents: DocumentCommand,
    private val referenceData: ReferenceDataQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    private val savepoints: Savepoints,
) : HedgeCommand, HedgeQuery, HedgePositions {
    private val log = LoggerFactory.getLogger(HedgeService::class.java)

    override fun requestDesignation(command: RequestHedgeDesignation): HedgeRelationshipView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        actor.requireEntity(command.legalEntityId)
        referenceData.requireLegalEntity(command.legalEntityId)
        val item = validItem(command.item)
        if (command.objective.isBlank()) throw ValidationException("A hedge is documented with its risk management objective")
        if (command.instruments.isEmpty()) throw ValidationException("A hedge needs at least one hedging trade")
        if (command.instruments.map { it.tradeId }.toSet().size != command.instruments.size) throw ValidationException("A trade is designated once per hedge")
        val designated = repository.designatedProportions(command.instruments.map { it.tradeId }, OPEN)
        val instruments = command.instruments.map { i ->
            if (i.proportion.signum() <= 0 || i.proportion > BigDecimal.ONE) throw ValidationException("A proportion is more than 0 and at most 1")
            val trade = trades.trade(i.tradeId) ?: throw NotFoundException("Trade", i.tradeId)
            validInstrument(trade, item, command.legalEntityId)
            if ((designated[trade.id] ?: BigDecimal.ZERO) + i.proportion > BigDecimal.ONE) {
                throw ValidationException("${trade.tradeNumber} is already designated in another hedge; at most all of it can be")
            }
            HedgeInstrumentView(trade.id, trade.tradeNumber, i.proportion, (remaining(trade) * i.proportion).setScale(3, RoundingMode.HALF_EVEN))
        }
        val ratio = instruments.sumOf { it.quantity }.divide(item.quantity, 6, RoundingMode.HALF_EVEN)
        val hedge = HedgeRelationshipView(
            id = Ids.newId(),
            number = repository.nextNumber(),
            legalEntityId = command.legalEntityId,
            item = item,
            instruments = instruments,
            objective = command.objective.trim(),
            hedgeRatio = ratio,
            checks = checks(item, command.instruments.map { trades.trade(it.tradeId)!! }, ratio),
            status = HedgeStatus.PENDING,
            requestedBy = actor.userId,
            requestedAt = clock.instant(),
            decidedBy = null,
            decidedAt = null,
            designationDate = null,
            designationPrice = null,
            instrumentBaseline = null,
            documentId = null,
            discontinuation = null,
        )
        repository.insert(hedge)
        events.publishEvent(HedgeDesignationRequested(hedge))
        return hedge
    }

    private fun validItem(item: HedgedItem): HedgedItem {
        if (item.quantity.signum() <= 0) throw ValidationException("The hedged quantity must be positive")
        val currency = Currency.getInstance(item.currency.uppercase()).currencyCode
        when (item.productFamily) {
            ProductFamily.EMISSION_ALLOWANCE -> if (item.allowanceProductCode.isNullOrBlank()) {
                throw ValidationException("A forecast allowance transaction names its allowance product")
            }
            ProductFamily.GUARANTEE_OF_ORIGIN -> if (item.bucketCode.isNullOrBlank()) {
                throw ValidationException("A forecast GoO transaction names its product bucket")
            }
        }
        return item.copy(
            currency = currency,
            allowanceProductCode = item.allowanceProductCode?.uppercase().takeIf { item.productFamily == ProductFamily.EMISSION_ALLOWANCE },
            bucketCode = item.bucketCode?.uppercase().takeIf { item.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN },
            // Certificates have production years; allowances have none.
            productionYear = item.productionYear.takeIf { item.productFamily == ProductFamily.GUARANTEE_OF_ORIGIN },
            quantity = item.quantity.setScale(3, RoundingMode.HALF_EVEN),
        )
    }

    /**
     * Hedging instruments are open fixed-price forwards of the entity, bought for a forecast purchase and sold for a sale.
     * Futures are not: their result is realised every day by the variation margin (ADR 0046).
     */
    private fun validInstrument(trade: TradeView, item: HedgedItem, legalEntityId: UUID) {
        if (trade.legalEntityId != legalEntityId) throw ValidationException("${trade.tradeNumber} belongs to another legal entity")
        if (trade.status !in DESIGNABLE) throw ValidationException("${trade.tradeNumber} is ${trade.status}; only open trades hedge")
        if (trade.productFamily != item.productFamily) throw ValidationException("${trade.tradeNumber} is not a ${item.productFamily} trade")
        val side = if (item.direction == HedgeDirection.PURCHASE) Side.BUY else Side.SELL
        trade.legs.forEach { leg ->
            if (leg.option != null) throw ValidationException("${trade.tradeNumber} is an option; designate forwards")
            if (leg.futuresContractCode != null) {
                throw ValidationException("${trade.tradeNumber} is a future, settled daily by variation margin; designate forwards")
            }
            if (leg.priceType != PriceType.FIXED) {
                throw ValidationException(
                    "${trade.tradeNumber} is not fixed-price; only fixed-price trades hedge a forecast price",
                )
            }
            if (leg.side != side) throw ValidationException("A forecast ${item.direction.name.lowercase()} is hedged by ${side.name.lowercase()}ing")
        }
        if (remaining(trade).signum() <= 0) throw ValidationException("${trade.tradeNumber} has nothing left to deliver")
    }

    private fun remaining(trade: TradeView): BigDecimal = trade.legs.flatMap { it.deliveries }.sumOf { it.remainingQuantity }

    /** Critical terms compared, prospectively: product, currency, timing within a month, and a ratio of 80 % to 125 %. */
    private fun checks(item: HedgedItem, instruments: List<TradeView>, ratio: BigDecimal): List<HedgeCheck> {
        val legs = instruments.flatMap { it.legs }
        val product = legs.all { (it.allowanceProductCode ?: it.bucketCode) == (item.allowanceProductCode ?: item.bucketCode) }
        val currency = legs.all { it.priceCurrency.currencyCode == item.currency }
        val dates = legs.flatMap { it.deliveries }.filter { it.remainingQuantity.signum() > 0 }.map { it.deliveryDate }
        val gap = dates.maxOfOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(item.expectedDate, it)) } ?: 0
        val ratioOk = ratio >= RATIO_MIN && ratio <= RATIO_MAX
        return listOf(
            HedgeCheck("PRODUCT", product, if (product) "Instruments deliver the hedged product" else "An instrument delivers another product (basis risk)"),
            HedgeCheck("CURRENCY", currency, if (currency) "Instruments are priced in ${item.currency}" else "An instrument is priced in another currency"),
            HedgeCheck("TIMING", gap <= TIMING_DAYS, "Deliveries within $gap days of the expected date (at most $TIMING_DAYS)"),
            HedgeCheck("RATIO", ratioOk, "Hedge ratio ${ratio.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_EVEN)} % (80 % to 125 % expected)"),
        )
    }

    /** From the designation task: locks the forward price and the instruments' value, and issues the documentation. */
    fun approveDesignation(id: UUID, by: String): HedgeRelationshipView {
        val hedge = pending(id)
        SecondPerson.require(by, hedge.requestedBy) { "A hedge is approved by someone other than who proposed it" }
        val date = LocalDate.now(clock)
        val forward = valuations.forwardValue(hedge.legalEntityId, date, terms(hedge.item, BigDecimal.ONE, BigDecimal.ZERO))
            ?: throw ValidationException("No official forward price of the hedged item on $date; sign off the curve first")
        val baseline = valuations.official(hedge.legalEntityId, date, orEarlier = true)?.let { instrumentValue(hedge, it) } ?: BigDecimal.ZERO.setScale(2)
        val designated = hedge.copy(
            status = HedgeStatus.ACTIVE,
            decidedBy = by,
            decidedAt = clock.instant(),
            designationDate = date,
            designationPrice = forward.marketPrice,
            instrumentBaseline = baseline,
        )
        val document = documents.renderPdf(
            RenderPdf(
                kind = "HEDGE_DOCUMENTATION",
                ownerType = "HEDGE",
                ownerRef = hedge.id,
                legalEntityId = hedge.legalEntityId,
                title = "Hedge documentation ${hedge.number}",
                fileName = "hedge-${hedge.number}.pdf",
                template = "hedge-documentation",
                model = documentation(designated),
            ),
        )
        val active = designated.copy(documentId = document.id)
        repository.update(active)
        return active
    }

    fun rejectDesignation(id: UUID, by: String): HedgeRelationshipView {
        val rejected = pending(id).copy(status = HedgeStatus.REJECTED, decidedBy = by, decidedAt = clock.instant())
        repository.update(rejected)
        return rejected
    }

    private fun pending(id: UUID): HedgeRelationshipView {
        val hedge = repository.hedge(id, lock = true) ?: throw NotFoundException("Hedge", id)
        if (hedge.status != HedgeStatus.PENDING) throw ValidationException("Hedge ${hedge.number} is ${hedge.status}")
        return hedge
    }

    override fun requestDiscontinuation(id: UUID, command: RequestHedgeDiscontinuation): HedgeRelationshipView {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT)
        val hedge = repository.hedge(id, lock = true) ?: throw NotFoundException("Hedge", id)
        actor.requireEntity(hedge.legalEntityId)
        if (hedge.status != HedgeStatus.ACTIVE) throw ValidationException("Hedge ${hedge.number} is ${hedge.status}")
        if (hedge.discontinuation != null) throw ValidationException("Hedge ${hedge.number} already waits for a discontinuation decision")
        if (command.reason.isBlank()) throw ValidationException("A discontinuation needs a reason")
        val requested = hedge.copy(
            discontinuation = HedgeDiscontinuation(command.reason.trim(), command.stillExpected, actor.userId, clock.instant(), null, null, null),
        )
        repository.update(requested)
        events.publishEvent(HedgeDiscontinuationRequested(requested))
        return requested
    }

    /** From the discontinuation task: the reserve is frozen at its last measured amount, or recycled when not expected. */
    fun approveDiscontinuation(id: UUID, by: String): HedgeRelationshipView {
        val hedge = discontinuing(id)
        val request = hedge.discontinuation!!
        SecondPerson.require(by, request.requestedBy) { "A discontinuation is approved by someone other than who requested it" }
        val frozen = if (request.stillExpected) {
            repository.measurements(
                id,
            ).lastOrNull()?.effective ?: BigDecimal.ZERO.setScale(2)
        } else {
            BigDecimal.ZERO.setScale(2)
        }
        val discontinued = hedge.copy(
            status = HedgeStatus.DISCONTINUED,
            discontinuation = request.copy(decidedBy = by, decidedAt = clock.instant(), frozenReserve = frozen),
        )
        repository.update(discontinued)
        return discontinued
    }

    fun rejectDiscontinuation(id: UUID): HedgeRelationshipView {
        val kept = discontinuing(id).copy(discontinuation = null)
        repository.update(kept)
        return kept
    }

    private fun discontinuing(id: UUID): HedgeRelationshipView {
        val hedge = repository.hedge(id, lock = true) ?: throw NotFoundException("Hedge", id)
        if (hedge.status != HedgeStatus.ACTIVE || hedge.discontinuation == null) {
            throw ValidationException(
                "Hedge ${hedge.number} has no discontinuation to decide",
            )
        }
        return hedge
    }

    // ----------------------------------------------------------------------------------------------------------
    // Measurement
    // ----------------------------------------------------------------------------------------------------------

    /** Every official run measures the entity's active hedges; a hedge that cannot be measured is logged, never failing the sign-off. */
    @EventListener
    fun on(event: ValuationRunSignedOff) {
        repository.hedges(event.run.legalEntityId, setOf(HedgeStatus.ACTIVE)).forEach { hedge ->
            try {
                savepoints.attempt { measure(hedge, event.run) }
            } catch (e: RuntimeException) {
                log.warn("Hedge {} could not be measured on run {}", hedge.number, event.run.id, e)
            }
        }
    }

    /** The hedge's measurement on [run], computed once and kept; null when the hypothetical derivative has no price. */
    fun measure(hedge: HedgeRelationshipView, run: ValuationRunView): HedgeMeasurementView? {
        // A hedge applies from its designation on; an earlier valuation is not measured (no backdating).
        if (hedge.designationDate == null || run.valuationDate.isBefore(hedge.designationDate)) return null
        repository.measurement(hedge.id, run.id)?.let { return it }
        val sign = if (hedge.item.direction == HedgeDirection.PURCHASE) BigDecimal.ONE else BigDecimal.ONE.negate()
        val hypothetical = valuations.forwardValue(
            hedge.legalEntityId,
            run.valuationDate,
            terms(hedge.item, hedge.item.quantity * sign, hedge.designationPrice!!),
        )
            ?: return null
        val value = instrumentValue(hedge, run)
        val split = Effectiveness.split(value, hedge.instrumentBaseline ?: BigDecimal.ZERO, hypothetical.valueBase)
        return repository.insertMeasurement(
            HedgeMeasurementView(
                hedge.id,
                run.id,
                run.valuationDate,
                value,
                split.instrumentChange,
                hypothetical.valueBase,
                split.effective,
                split.ineffective,
                split.offsetRatio,
                clock.instant(),
            ),
        )
    }

    /** The designated proportions of the instruments' official MtM in base currency, over schedules valued without exception. */
    private fun instrumentValue(hedge: HedgeRelationshipView, run: ValuationRunView): BigDecimal {
        val proportions = hedge.instruments.associate { it.tradeId to it.proportion }
        return valuations.results(run.id)
            .filter { it.tradeId in proportions && it.status == ResultStatus.OK && it.mtmBase != null }
            .fold(BigDecimal.ZERO) { sum, r -> sum + r.mtmBase!! * proportions.getValue(r.tradeId) }
            .setScale(2, RoundingMode.HALF_EVEN)
    }

    private fun terms(item: HedgedItem, quantity: BigDecimal, price: BigDecimal) = ForwardTerms(
        productFamily = item.productFamily,
        allowanceProductCode = item.allowanceProductCode,
        bucketCode = item.bucketCode,
        productionYear = item.productionYear,
        deliveryDate = item.expectedDate,
        payDate = item.expectedDate,
        quantity = quantity,
        price = price,
        currency = item.currency,
    )

    /**
     * What period end posts for each hedge on [run] (ADR 0045): the instruments' carrying value and the reserve it
     * requires. Active hedges reserve their effective portion; a discontinued one keeps its frozen reserve while its
     * instruments are open. A hedge without a price for its hypothetical derivative reserves nothing and is reported.
     */
    override fun positions(legalEntityId: UUID, run: ValuationRunView, warnings: MutableList<String>): List<HedgePosition> =
        repository.hedges(legalEntityId, setOf(HedgeStatus.ACTIVE, HedgeStatus.DISCONTINUED))
            .filter { it.designationDate != null && !run.valuationDate.isBefore(it.designationDate) }
            .mapNotNull { hedge ->
                val value = instrumentValue(hedge, run)
                when (hedge.status) {
                    HedgeStatus.ACTIVE -> {
                        val measured = measure(hedge, run)
                        if (measured == null) {
                            warnings += "Hedge ${hedge.number} has no price for its hypothetical derivative on ${run.valuationDate}; nothing reserved"
                        }
                        HedgePosition(hedge, value, measured?.effective ?: BigDecimal.ZERO.setScale(2))
                    }
                    else -> {
                        val frozen = hedge.discontinuation?.frozenReserve ?: BigDecimal.ZERO
                        if (frozen.signum() == 0) null else HedgePosition(hedge, value, if (value.signum() == 0) BigDecimal.ZERO.setScale(2) else frozen)
                    }
                }
            }

    data class HedgePosition(val hedge: HedgeRelationshipView, val instrumentValue: BigDecimal, val reserve: BigDecimal)

    private fun documentation(hedge: HedgeRelationshipView): Map<String, Any?> {
        val entity = referenceData.requireLegalEntity(hedge.legalEntityId)
        return mapOf(
            "number" to hedge.number,
            "entity" to entity.name,
            "objective" to hedge.objective,
            "direction" to if (hedge.item.direction == HedgeDirection.PURCHASE) "Forecast purchase" else "Forecast sale",
            "product" to (hedge.item.allowanceProductCode ?: hedge.item.bucketCode),
            "productionYear" to hedge.item.productionYear?.toString(),
            "quantity" to hedge.item.quantity.toPlainString(),
            "expectedDate" to hedge.item.expectedDate.toString(),
            "currency" to hedge.item.currency,
            "instruments" to hedge.instruments.map {
                mapOf("trade" to it.tradeNumber, "proportion" to it.proportion.stripTrailingZeros().toPlainString(), "quantity" to it.quantity.toPlainString())
            },
            "ratio" to hedge.hedgeRatio.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_EVEN).toPlainString(),
            "checks" to hedge.checks.map { mapOf("code" to it.code, "result" to if (it.passed) "met" else "not met", "detail" to it.detail) },
            "designationDate" to hedge.designationDate.toString(),
            "designationPrice" to hedge.designationPrice?.toPlainString(),
            "baseline" to hedge.instrumentBaseline?.toPlainString(),
            "baseCurrency" to entity.baseCurrency.currencyCode,
            "requestedBy" to hedge.requestedBy,
            "approvedBy" to hedge.decidedBy,
        )
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    private fun readable(legalEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.ACCOUNTANT, Role.AUDITOR, Role.RISK, Role.MIDDLE_OFFICE, Role.SYSTEM)
        actor.requireEntity(legalEntityId)
    }

    @Transactional(readOnly = true)
    override fun hedges(legalEntityId: UUID, status: HedgeStatus?): List<HedgeRelationshipView> {
        readable(legalEntityId)
        return repository.hedges(legalEntityId, status?.let { setOf(it) })
    }

    @Transactional(readOnly = true)
    override fun hedge(id: UUID): HedgeDetail {
        val hedge = repository.hedge(id) ?: throw NotFoundException("Hedge", id)
        readable(hedge.legalEntityId)
        return HedgeDetail(hedge, repository.measurements(id))
    }

    private companion object {
        val DESIGNABLE = setOf(TradeStatus.APPROVED, TradeStatus.CONFIRMED, TradeStatus.PARTIALLY_DELIVERED)
        val OPEN = setOf(HedgeStatus.PENDING, HedgeStatus.ACTIVE)
        val RATIO_MIN = BigDecimal("0.8")
        val RATIO_MAX = BigDecimal("1.25")
        const val TIMING_DAYS = 31L
    }
}
