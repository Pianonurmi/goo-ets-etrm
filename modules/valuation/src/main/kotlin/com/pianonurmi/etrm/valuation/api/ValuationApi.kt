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

enum class ExplainComponent { NEW, CANCELLED, DELIVERED, AMENDED, PRICE, FX, UNEXPLAINED }

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
