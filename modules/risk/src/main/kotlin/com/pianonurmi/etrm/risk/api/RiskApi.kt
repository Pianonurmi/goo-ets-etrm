package com.pianonurmi.etrm.risk.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.product.api.ProductFamily
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class LimitType {
    /** Credit exposure to a counterparty and its group subsidiaries, in the limit's currency. */
    CREDIT_EXPOSURE,

    /** Net quantity (bought less sold, plus inventory) in a product family, optionally one product and one book. */
    POSITION_NET,

    /** Months from today to the last delivery of open trades in scope. */
    TENOR_MAX_MONTHS,

    /** Loss of MtM since the end of the previous month, in the limit's currency. */
    STOP_LOSS_MTD,

    /** EU ETS allowances short of the compliance obligation for a year, in tonnes. */
    COMPLIANCE_SHORT,

    /**
     * GoO certificates of one product bucket that no obligation uses, after netting through the eligibility flow
     * (ADR 0040): the book's open exposure to that product. In MWh, or in EUR at the bucket's mark.
     */
    GOO_CONCENTRATION,

    /** Held GoO certificates expiring within the scope's horizon that no obligation uses, in MWh. */
    GOO_EXPIRY,

    /**
     * Sales delivered with certificates worth more than their own product on the valuation-grade allocation: MWh, or
     * the give-away in EUR.
     */
    GOO_BASIS,

    /**
     * Cash tied up at clearing houses (ADR 0046): initial margin held plus margin calls still to pay, over the entity's
     * clearing accounts, in the limit's currency.
     */
    MARGIN_LIQUIDITY,
}

/** What a limit applies to; which fields matter depends on the limit type. */
data class LimitScope(
    val counterpartyId: UUID? = null,
    val bookId: UUID? = null,
    val productFamily: ProductFamily? = null,
    val allowanceProductCode: String? = null,
    val complianceYear: Int? = null,
    /** GoO concentration (and optionally expiry): the product bucket. */
    val bucketCode: String? = null,
    /** GoO expiry: certificates expiring within this many days. */
    val horizonDays: Int? = null,
    /** GoO limits: one vintage only (certificates produced in that year, sales marked at it). */
    val productionYear: Int? = null,
)

enum class LimitStatus { PENDING_APPROVAL, ACTIVE, REJECTED, RETIRED }

data class RiskLimitView(
    val id: UUID,
    /** Null for a limit across all legal entities. */
    val legalEntityId: UUID?,
    val type: LimitType,
    val scope: LimitScope,
    val amount: BigDecimal,
    val currency: String?,
    val warningPct: Int,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val status: LimitStatus,
    /** The limit this one replaces once approved. */
    val replaces: UUID?,
    val reason: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
) {
    fun validOn(date: LocalDate): Boolean = !date.isBefore(validFrom) && (validTo == null || date.isBefore(validTo))
}

/** A new limit, or a new version of one ([replaces]); it applies once a second person in risk approves it. */
data class RequestLimit(
    val legalEntityId: UUID?,
    val type: LimitType,
    val scope: LimitScope,
    val amount: BigDecimal,
    val currency: String? = null,
    val warningPct: Int = 80,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val replaces: UUID? = null,
    val reason: String,
)

enum class UtilisationStatus { OK, WARNING, BREACH }

data class LimitUtilisationView(
    val limitId: UUID,
    val value: BigDecimal,
    /** Value as a percentage of the limit. */
    val utilisation: BigDecimal,
    val status: UtilisationStatus,
    val detail: String?,
    val measuredAt: Instant,
)

enum class CreditSupportKind(val received: Boolean) {
    CASH_RECEIVED(true),
    CASH_POSTED(false),
    GUARANTEE_RECEIVED(true),
    LETTER_OF_CREDIT_RECEIVED(true),
}

enum class CreditSupportStatus { PENDING_APPROVAL, ACTIVE, REJECTED }

data class CreditSupportView(
    val id: UUID,
    val legalEntityId: UUID?,
    val counterpartyId: UUID,
    val kind: CreditSupportKind,
    val amount: BigDecimal,
    val currency: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val reference: String?,
    val status: CreditSupportStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
) {
    fun validOn(date: LocalDate): Boolean = status == CreditSupportStatus.ACTIVE && !date.isBefore(validFrom) && (validTo == null || date.isBefore(validTo))
}

data class RequestCreditSupport(
    val legalEntityId: UUID?,
    val counterpartyId: UUID,
    val kind: CreditSupportKind,
    val amount: BigDecimal,
    val currency: String,
    val validFrom: LocalDate,
    val validTo: LocalDate? = null,
    val reference: String? = null,
)

/**
 * A legal entity's current credit exposure to one counterparty, in the entity's base currency:
 * max(0, MtM + receivables − netted payables + PFE − collateral).
 */
data class CounterpartyExposureView(
    val legalEntityId: UUID,
    val counterpartyId: UUID,
    val currency: String,
    /** Positive MtM: per trade, or netted over the counterparty when a netting agreement is in place. */
    val mtm: BigDecimal,
    /** Invoiced and delivered-not-invoiced amounts the counterparty owes. */
    val receivables: BigDecimal,
    /** What we owe the counterparty, set off only under a netting agreement. */
    val payablesNetted: BigDecimal,
    /** Potential future exposure of open trades. */
    val pfe: BigDecimal,
    /** Collateral and guarantees received less cash posted. */
    val collateral: BigDecimal,
    val exposure: BigDecimal,
    val valuationRunId: UUID?,
    val measuredAt: Instant,
)

enum class BreachStatus { OPEN, CLOSED }

data class LimitBreachView(
    val id: UUID,
    val limitId: UUID,
    val openedAt: Instant,
    val firstValue: BigDecimal,
    val peakValue: BigDecimal,
    val closedAt: Instant?,
    val status: BreachStatus,
)

data class PfeAddOnView(val productFamily: ProductFamily, val maxTenorMonths: Int, val pct: BigDecimal, val calibrated: Boolean)

interface RiskQuery {
    /** Limits of an entity, including those across all entities. */
    fun limits(legalEntityId: UUID?, activeOnly: Boolean = true): List<RiskLimitView>

    fun limit(id: UUID): RiskLimitView?

    fun utilisation(limitId: UUID): LimitUtilisationView?

    fun exposures(legalEntityId: UUID): List<CounterpartyExposureView>

    fun breaches(legalEntityId: UUID?, openOnly: Boolean = true): List<LimitBreachView>

    fun creditSupport(counterpartyId: UUID): List<CreditSupportView>

    fun pfeAddOns(): List<PfeAddOnView>
}

interface RiskCommand {
    fun requestLimit(command: RequestLimit): RiskLimitView

    fun requestCreditSupport(command: RequestCreditSupport): CreditSupportView

    /** Measures exposures and every active limit of the entity now; opens and closes breaches. */
    fun measure(legalEntityId: UUID): List<LimitUtilisationView>
}

data class LimitChangeRequested(val limit: RiskLimitView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class LimitActivated(val limit: RiskLimitView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class CreditSupportRequested(val support: CreditSupportView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class LimitBreached(
    val breach: LimitBreachView,
    val limit: RiskLimitView,
    val utilisation: LimitUtilisationView,
    val metadata: EventMetadata = EventMetadata(),
) : DomainEvent by metadata

data class LimitBreachResolved(val breach: LimitBreachView, val limit: RiskLimitView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
