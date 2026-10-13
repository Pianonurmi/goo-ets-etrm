package com.pianonurmi.etrm.ledger.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import com.pianonurmi.etrm.referencedata.api.BookType
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/** How unrealised MtM of a book type is accounted for at period end. */
enum class MtmTreatment {
    /** Derivative asset or liability against unrealised gains or losses, reversed on the first day of the next period. */
    POST_PL,

    /** As POST_PL, but against the hedging reserve in equity. */
    POST_OCI,

    /** Measured and disclosed, not posted (own-use contracts, compliance purchases). */
    DISCLOSE_ONLY,
}

data class MtmPolicyView(
    val id: UUID,
    /** Null for the default that applies to every entity without its own policy. */
    val legalEntityId: UUID?,
    val bookType: BookType,
    val treatment: MtmTreatment,
    val signedOffBy: String?,
    val signedOffAt: Instant?,
) {
    val signedOff: Boolean get() = signedOffBy != null
}

data class NrvPolicyView(
    val id: UUID,
    val legalEntityId: UUID?,
    val gooCostToSellPerMwh: BigDecimal,
    val allowanceCostToSell: BigDecimal,
    /** Unreserved certificates expiring within this many days of the period end are valued at nil. */
    val expiryDays: Int,
    val currency: String,
    val signedOffBy: String?,
    val signedOffAt: Instant?,
) {
    val signedOff: Boolean get() = signedOffBy != null
}

enum class PolicyKind { MTM, NRV }

enum class PolicyChangeStatus { PENDING, APPLIED, REJECTED }

/** A change to an accounting policy; it applies once a second person approves it (`ACCOUNTING_POLICY_CHANGE`). */
data class RequestPolicyChange(
    val legalEntityId: UUID?,
    val kind: PolicyKind,
    val reason: String,
    /** For MTM. */
    val bookType: BookType? = null,
    val treatment: MtmTreatment? = null,
    /** For NRV. */
    val gooCostToSellPerMwh: BigDecimal? = null,
    val allowanceCostToSell: BigDecimal? = null,
    val expiryDays: Int? = null,
    val currency: String? = null,
)

data class PolicyChangeView(
    val id: UUID,
    val legalEntityId: UUID?,
    val kind: PolicyKind,
    val change: RequestPolicyChange,
    val status: PolicyChangeStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
)

enum class PeriodEndComponentKind { MTM, HEDGE, NRV, EMISSION_PROVISION, GRANT_RELEASE, FX_REVALUATION }

/**
 * One measured balance: what it should be at the period end ([required]), what was booked before the run
 * ([booked]), and the difference posted. Disclosed components are measured and not posted.
 */
data class PeriodEndComponent(
    val kind: PeriodEndComponentKind,
    /** Book type, asset class, compliance year, or account and currency. */
    val key: String,
    val required: BigDecimal,
    val booked: BigDecimal,
    val posted: BigDecimal,
    val disclosedOnly: Boolean = false,
    val detail: Map<String, String> = emptyMap(),
)

data class PeriodEndRunView(
    val id: UUID,
    val legalEntityId: UUID,
    val period: String,
    val seq: Int,
    val valuationRunId: UUID?,
    val components: List<PeriodEndComponent>,
    val entryIds: List<UUID>,
    /** Unsigned policies, unpriced lots, missing rates: the run went ahead and says what it could not do. */
    val warnings: List<String>,
    val runBy: String,
    val runAt: Instant,
)

interface PeriodEndQuery {
    fun mtmPolicies(legalEntityId: UUID?): List<MtmPolicyView>

    fun nrvPolicy(legalEntityId: UUID?): NrvPolicyView

    fun policyChanges(status: PolicyChangeStatus?): List<PolicyChangeView>

    fun runs(legalEntityId: UUID, period: YearMonth?): List<PeriodEndRunView>
}

interface PeriodEndCommand {
    /**
     * Measures MtM, NRV, the emission provision, the free allocation grant release and FX on open items for the
     * period, and posts the differences to what is booked. Runs again post only what changed.
     */
    fun run(legalEntityId: UUID, period: YearMonth): PeriodEndRunView

    fun requestPolicyChange(command: RequestPolicyChange): PolicyChangeView
}

data class PeriodEndCompleted(val run: PeriodEndRunView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

data class PolicyChangeRequested(val change: PolicyChangeView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
