package com.pianonurmi.etrm.ledger.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.product.api.ProductFamily
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What the hedged forecast is: a purchase (hedged by bought forwards) or a sale (by sold forwards). */
enum class HedgeDirection { PURCHASE, SALE }

enum class HedgeStatus {
    /** Waiting for a second accountant's approval of the designation. */
    PENDING,
    ACTIVE,
    REJECTED,

    /** Hedge accounting stopped; the reserve stays frozen while the forecast is still expected, else it was recycled. */
    DISCONTINUED,
}

/** The highly probable forecast transaction a cash flow hedge protects (ADR 0045). */
data class HedgedItem(
    val direction: HedgeDirection,
    val productFamily: ProductFamily,
    val allowanceProductCode: String? = null,
    val bucketCode: String? = null,
    val productionYear: Int? = null,
    val quantity: BigDecimal,
    val expectedDate: LocalDate,
    val currency: String,
)

/** A trade designated as hedging instrument, wholly or for a proportion (0 < p ≤ 1) of it. */
data class HedgeInstrument(val tradeId: UUID, val proportion: BigDecimal = BigDecimal.ONE)

data class HedgeInstrumentView(val tradeId: UUID, val tradeNumber: String, val proportion: BigDecimal, val quantity: BigDecimal)

/** One prospective check shown on the designation task: critical terms of instrument and hedged item compared. */
data class HedgeCheck(val code: String, val passed: Boolean, val detail: String)

data class RequestHedgeDesignation(
    val legalEntityId: UUID,
    val item: HedgedItem,
    val instruments: List<HedgeInstrument>,
    /** The risk management objective and strategy, for the hedge documentation. */
    val objective: String,
)

data class RequestHedgeDiscontinuation(val reason: String, val stillExpected: Boolean)

data class HedgeRelationshipView(
    val id: UUID,
    val number: String,
    val legalEntityId: UUID,
    val item: HedgedItem,
    val instruments: List<HedgeInstrumentView>,
    val objective: String,
    /** Instrument quantity designated over hedged quantity. */
    val hedgeRatio: BigDecimal,
    val checks: List<HedgeCheck>,
    val status: HedgeStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    /** Locked at designation: the forward price of the hedged item, the instruments' MtM then (base currency). */
    val designationDate: LocalDate?,
    val designationPrice: BigDecimal?,
    val instrumentBaseline: BigDecimal?,
    val documentId: UUID?,
    val discontinuation: HedgeDiscontinuation?,
)

data class HedgeDiscontinuation(
    val reason: String,
    val stillExpected: Boolean,
    val requestedBy: String,
    val requestedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    /** The reserve kept in equity until the forecast occurs (zero when it is no longer expected). */
    val frozenReserve: BigDecimal?,
)

/**
 * Effectiveness on one official valuation run, in base currency: the instruments' value and its change since
 * designation, the hypothetical derivative's value, the lower-of split into the reserve (effective) and profit or
 * loss (ineffective, including the instruments' value at designation), and the dollar-offset ratio in percent.
 */
data class HedgeMeasurementView(
    val relationshipId: UUID,
    val valuationRunId: UUID,
    val valuationDate: LocalDate,
    val instrumentValue: BigDecimal,
    val instrumentChange: BigDecimal,
    val hypotheticalValue: BigDecimal,
    val effective: BigDecimal,
    val ineffective: BigDecimal,
    val offsetRatio: BigDecimal?,
    val measuredAt: Instant,
)

data class HedgeDetail(val relationship: HedgeRelationshipView, val measurements: List<HedgeMeasurementView>)

interface HedgeQuery {
    fun hedges(legalEntityId: UUID, status: HedgeStatus? = null): List<HedgeRelationshipView>

    fun hedge(id: UUID): HedgeDetail
}

interface HedgeCommand {
    /** Proposes a cash flow hedge; a second accountant approves it in the inbox (`HEDGE_DESIGNATION`). */
    fun requestDesignation(command: RequestHedgeDesignation): HedgeRelationshipView

    /** Proposes to stop hedge accounting; a second accountant approves it (`HEDGE_DISCONTINUATION`). */
    fun requestDiscontinuation(id: UUID, command: RequestHedgeDiscontinuation): HedgeRelationshipView
}

data class HedgeDesignationRequested(val hedge: HedgeRelationshipView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class HedgeDiscontinuationRequested(val hedge: HedgeRelationshipView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
