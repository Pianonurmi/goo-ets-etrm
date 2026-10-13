package com.pianonurmi.etrm.clearing.api

import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.events.EventMetadata
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class MarginCallStatus {
    OPEN,
    PAID,
    DISPUTED,

    /** A later statement replaced it (a new call, or no shortfall any more). */
    CANCELLED,
}

/**
 * The member asks for cash: on the account's latest statement the cash at the member is below the initial margin
 * required (ADR 0046). At most one call per account is open; a later statement replaces it.
 */
data class MarginCallView(
    val id: UUID,
    val accountId: UUID,
    val legalEntityId: UUID,
    val businessDate: LocalDate,
    val statementId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val status: MarginCallStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val reason: String?,
)

/** Margin held at a clearing house for the entity, as of the account's latest statement. */
data class MarginPostedView(
    val accountId: UUID,
    val clearingHouseId: UUID,
    val currency: String,
    val initialMargin: BigDecimal,
    /** The cash balance at the member on the latest statement; none before the first. */
    val cashBalance: BigDecimal?,
    /** Open margin calls: cash the entity still has to pay in. */
    val openCalls: BigDecimal,
)

interface MarginCallQuery {
    fun marginCalls(legalEntityId: UUID? = null, status: MarginCallStatus? = null): List<MarginCallView>

    fun marginCall(id: UUID): MarginCallView?

    /** Per active account of the entity: what the clearing house holds and what it still asks for. */
    fun marginPosted(legalEntityId: UUID): List<MarginPostedView>
}

data class MarginCallOpened(val call: MarginCallView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata

/** Paid (the ledger moves the cash from the bank to the member), disputed, or cancelled by a later statement. */
data class MarginCallClosed(val call: MarginCallView, val metadata: EventMetadata = EventMetadata()) : DomainEvent by metadata
