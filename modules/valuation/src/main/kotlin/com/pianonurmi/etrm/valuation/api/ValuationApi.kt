package com.pianonurmi.etrm.valuation.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.referencedata.api.BookType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class RunKind { EOD, PERIOD_END, ADHOC }

enum class RunStatus { DRAFT, PENDING_SIGN_OFF, OFFICIAL, SUPERSEDED, REJECTED }

enum class ResultStatus {
    OK,

    /** No official curve price for the product or bucket. */
    MISSING_PRICE,

    /** No rate to convert the market price or the result. */
    MISSING_FX,

    /** A GoO leg without a product bucket cannot be marked. */
    MISSING_BUCKET,

    /** Not valued (e.g. formula-priced legs). */
    EXCLUDED,
}

/**
 * Why MtM moved between two runs (ADR 0019, ADR 0044). TIME is the old discount curve a day closer to payment;
 * RATES the move of the discount curve itself.
 */
enum class ExplainComponent { NEW, CANCELLED, DELIVERED, AMENDED, TIME, PRICE, RATES, FX, UNEXPLAINED }

/** One delivery schedule valued: quantity signed (+ bought, − sold), prices per unit, MtM in contract and base currency. */
data class ValuationResultView(
    val runId: UUID,
    val scheduleId: UUID,
    val tradeId: UUID,
    val tradeNumber: String,
    val tradeVersion: Int,
    val legNo: Int,
    val bookId: UUID,
    val bookType: BookType,
    val counterpartyId: UUID,
    val productFamily: ProductFamily,
    val productKey: String?,
    val productionYear: Int?,
    val deliveryDate: LocalDate,
    val quantity: BigDecimal,
    val contractPrice: BigDecimal?,
    val contractCurrency: String,
    val marketPrice: BigDecimal?,
    val marketCurrency: String?,
    val priceOrigin: String?,
    val curveCode: String?,
    val snapshotId: UUID?,
    val curveDate: LocalDate?,
    val marketPriceContract: BigDecimal?,
    val fxToBase: BigDecimal?,
    val mtm: BigDecimal?,
    val mtmBase: BigDecimal?,
    val status: ResultStatus,
    val note: String?,
    /**
     * Cheapest to deliver (ADR 0039, shown only): for a GoO sale, the average value per MWh in EUR of the certificates
     * that deliver it on the valuation-grade allocation, and its MtM against that instead of the bucket curve.
     */
    val ctdPriceEur: BigDecimal? = null,
    val ctdMtmBase: BigDecimal? = null,
    /**
     * Discounting (ADR 0044): the schedule's payment date, the factor applied to its value (1 when the currency has
     * no official discount curve, for futures, which are margined daily, and in runs before discounting), the
     * discount curve snapshot, and the value before discounting in the contract currency.
     */
    val payDate: LocalDate? = null,
    val discountFactor: BigDecimal? = null,
    val discountCurveCode: String? = null,
    val discountSnapshotId: UUID? = null,
    val mtmUndiscounted: BigDecimal? = null,
)

data class ValuationRunView(
    val id: UUID,
    val legalEntityId: UUID,
    val valuationDate: LocalDate,
    val kind: RunKind,
    val status: RunStatus,
    val baseCurrency: String,
    val totalMtmBase: BigDecimal,
    val exceptions: Int,
    /** Curve snapshot ids used, by curve code. */
    val inputs: Map<String, String>,
    val note: String?,
    val createdBy: String,
    val createdAt: Instant,
    val signedOffBy: String?,
    val signedOffAt: Instant?,
    val signOffNote: String?,
    val supersededAt: Instant?,
)

data class ExplainRow(val scheduleId: UUID, val tradeId: UUID, val bookId: UUID, val component: ExplainComponent, val amountBase: BigDecimal)

/** P&L between two official runs, by component; the components add up to the change in MtM. */
data class PnlExplainView(
    val legalEntityId: UUID,
    val fromRunId: UUID,
    val toRunId: UUID,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val baseCurrency: String,
    val mtmFrom: BigDecimal,
    val mtmTo: BigDecimal,
    val components: Map<ExplainComponent, BigDecimal>,
    val rows: List<ExplainRow>,
)

/** What moved a run's portfolio value from the prior official run's (ADR 0039). */
enum class PortfolioCause { TRADE_MTM, INVENTORY_PRICE, INVENTORY_IN_OUT, GIVE_AWAY, REALLOCATION, EXPIRY, UNEXPLAINED }

/** Held certificates by bucket and production year at the run's prices (EUR). */
data class PortfolioBucketRow(val bucketCode: String?, val productionYear: Int, val heldMwh: Long, val valueEur: BigDecimal, val allocatedMwh: Long)

/** One obligation on the valuation-grade allocation: the certificates' value against its own mark. */
data class PortfolioObligationRow(
    val obligationId: UUID,
    val scheduleId: UUID?,
    val tradeRef: String?,
    val bucketCode: String?,
    val vintageYear: Int?,
    val demandMwh: Long,
    val allocatedMwh: Long,
    val cheapestToDeliverEur: BigDecimal?,
    val saleMarkEur: BigDecimal?,
    val giveAwayEur: BigDecimal?,
    val transferFeesEur: BigDecimal,
)

/**
 * A run's portfolio value in EUR (ADR 0039, management view; never booked):
 * [portfolioValue] = [inventoryValue] + [tradeMtm] − [giveAway] − [transferFees] − [expiryHaircut]. [tradeMtm] is
 * the run's official MtM of its GoO trades. Holdings are those when the run was made, at the run date's prices.
 * [explain] is against the prior official run, once this one is official.
 */
data class PortfolioBridgeView(
    val runId: UUID,
    val legalEntityId: UUID,
    val valuationDate: LocalDate,
    val currency: String,
    val heldMwh: Long,
    val inventoryValue: BigDecimal,
    val tradeMtm: BigDecimal,
    val giveAway: BigDecimal,
    val transferFees: BigDecimal,
    val expiryHaircut: BigDecimal,
    val portfolioValue: BigDecimal,
    val allocatedMwh: Long,
    val shortfallMwh: Long,
    val unpricedMwh: Long,
    val stamp: String,
    val computedAt: Instant,
    val buckets: List<PortfolioBucketRow>,
    val obligations: List<PortfolioObligationRow>,
    val priorRunId: UUID?,
    val explain: Map<PortfolioCause, BigDecimal>,
)

/** One official run's move from the run before it. */
data class PortfolioExplainStep(
    val runId: UUID,
    val priorRunId: UUID,
    val valuationDate: LocalDate,
    val portfolioValue: BigDecimal,
    val causes: Map<PortfolioCause, BigDecimal>,
)

/**
 * The portfolio value explained over official runs dated after [from] up to [to] (EUR). [realisedGiveAway] is what
 * plans approved in the period handed over; null when allocation is not available.
 */
data class PortfolioExplainView(
    val legalEntityId: UUID,
    val from: LocalDate,
    val to: LocalDate,
    val currency: String,
    val steps: List<PortfolioExplainStep>,
    val totals: Map<PortfolioCause, BigDecimal>,
    val realisedGiveAway: BigDecimal?,
)

/**
 * A forward valued the way a trade schedule is (ADR 0045): the hypothetical derivative of a hedged forecast. [quantity]
 * is signed (+ bought, − sold); [price] is per unit in [currency].
 */
data class ForwardTerms(
    val productFamily: ProductFamily,
    val allowanceProductCode: String?,
    val bucketCode: String?,
    val productionYear: Int?,
    val deliveryDate: LocalDate,
    val payDate: LocalDate,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val currency: String,
)

/** The forward's market price in its currency, the discount factor, and its value in its currency and in base. */
data class ForwardValue(val marketPrice: BigDecimal, val discountFactor: BigDecimal, val value: BigDecimal, val valueBase: BigDecimal, val baseCurrency: String)

interface ValuationQuery {
    fun run(id: UUID): ValuationRunView?

    fun runs(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<ValuationRunView>

    fun results(runId: UUID, bookId: UUID? = null): List<ValuationResultView>

    /** The official run of [date], or of the latest date before it when [orEarlier]. */
    fun official(legalEntityId: UUID, date: LocalDate, orEarlier: Boolean = false): ValuationRunView?

    /** Explain from the official run on or before [from] to the official run on or before [to]. */
    fun explain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PnlExplainView?

    /** MtM of one trade on [date] without storing anything (what-if). */
    fun tradeMtm(tradeId: UUID, date: LocalDate): List<ValuationResultView>

    /** The run's portfolio value; null when it has none (allocation not available, or no rate to EUR). */
    fun portfolio(runId: UUID): PortfolioBridgeView?

    fun portfolioExplain(legalEntityId: UUID, from: LocalDate, to: LocalDate): PortfolioExplainView

    /** [terms] valued on [date] with the official curves, rates and discount factors a run would use; null when unpriced. */
    fun forwardValue(legalEntityId: UUID, date: LocalDate, terms: ForwardTerms): ForwardValue?
}

interface ValuationCommand {
    /** Values every open trade of the entity on [date] into a draft run. */
    fun run(legalEntityId: UUID, date: LocalDate, kind: RunKind = RunKind.ADHOC, note: String? = null): ValuationRunView

    /** Sends a draft for sign-off by a second person (workflow task `VALUATION_SIGN_OFF`). */
    fun submit(runId: UUID): ValuationRunView
}

data class ValuationRunSubmitted(val run: ValuationRunView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/** A run became official; MtM-based limits, reports and the period end read it. */
data class ValuationRunSignedOff(val run: ValuationRunView, val superseded: UUID?, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class ValuationRunRejected(val run: ValuationRunView, val reason: String?, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
