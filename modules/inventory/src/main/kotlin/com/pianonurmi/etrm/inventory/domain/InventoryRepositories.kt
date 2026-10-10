package com.pianonurmi.etrm.inventory.domain

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.AllowanceStatementLine
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotEventView
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.ReconciliationBreakView
import com.pianonurmi.etrm.inventory.api.StatementLine
import com.pianonurmi.etrm.inventory.api.StatementStatus
import com.pianonurmi.etrm.inventory.api.StatementView
import org.jooq.Condition
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface LotRepository {
    fun find(id: UUID): LotView?

    /** Locks the row for update within the current transaction. */
    fun lock(id: UUID): LotView?

    fun insert(lot: LotView, change: LotChange)

    /** Optimistic update: the stored version must equal `lot.version - 1`. */
    fun update(lot: LotView, change: LotChange): Boolean

    fun list(filter: LotFilter): List<LotView>

    fun search(holderEntityId: UUID, condition: Condition, statuses: Set<LotStatus>, limit: Int): List<LotView>

    fun live(holderEntityId: UUID): List<LotView>

    fun dueForExpiry(asOf: LocalDate): List<UUID>

    fun liveInAccount(registryAccountId: UUID): List<LotView>

    fun asOf(lotId: UUID, effectiveAt: Instant, knownAt: Instant): Map<String, Any?>?

    fun expiryLadder(holderEntityId: UUID, from: LocalDate, toExclusive: LocalDate): Map<LocalDate, Long>
}

/** Who made a change, when it is effective in valid time, and the journal position that records it. */
data class LotChange(val seq: Long, val actor: String, val effectiveAt: Instant)

data class NewLotEvent(
    val lotId: UUID,
    val eventType: String,
    val effectiveAt: Instant,
    val actor: String,
    val correlationId: String?,
    val payload: Map<String, Any?>,
)

interface LotEventJournal {
    /** Appends with hash chaining on the lot's previous event; returns the new sequence number. */
    fun append(event: NewLotEvent): Long

    fun history(lotId: UUID): List<LotEventView>

    fun lastSeq(): Long

    fun brokenChains(): List<UUID>
}

data class NewAllowanceMovement(
    val holdingId: UUID,
    val type: String,
    val signedQuantity: Long,
    val effectiveDate: LocalDate,
    val reference: String?,
    val costPerUnit: java.math.BigDecimal?,
    val costCurrency: String?,
    val complianceYear: Int?,
    val actor: String,
    val correlationId: String?,
)

interface AllowanceHoldingRepository {
    fun find(registryAccountId: UUID, productCode: String): AllowanceHoldingView?

    fun findById(id: UUID): AllowanceHoldingView?

    fun lockOrCreate(holderEntityId: UUID, registryAccountId: UUID, productCode: String): AllowanceHoldingView

    fun update(holding: AllowanceHoldingView): Boolean

    fun byEntity(holderEntityId: UUID): List<AllowanceHoldingView>

    fun appendMovement(movement: NewAllowanceMovement): AllowanceMovementView

    fun movements(holdingId: UUID): List<AllowanceMovementView>
}

interface ReconciliationRepository {
    fun insertStatement(view: StatementView, importedBy: String, lines: List<StatementLine>)

    fun statement(id: UUID): StatementView?

    fun lines(statementId: UUID): List<StatementLine>

    fun insertAllowanceStatement(view: StatementView, importedBy: String, lines: List<AllowanceStatementLine>)

    fun allowanceLines(statementId: UUID): List<AllowanceStatementLine>

    fun insertBreak(view: ReconciliationBreakView)

    fun updateStatementStatus(id: UUID, status: StatementStatus)

    fun findBreak(id: UUID): ReconciliationBreakView?

    fun updateBreak(view: ReconciliationBreakView, resolvedBy: String)

    fun openBreaks(registryAccountId: UUID?): List<ReconciliationBreakView>
}

/** Range arithmetic for reconciliation: subtracts covered ranges from a target range. */
object RangeArithmetic {
    fun subtract(target: CertificateRange, covered: Collection<CertificateRange>): List<CertificateRange> {
        var remaining = listOf(target)
        covered.sortedBy { it.start }.forEach { c ->
            remaining = remaining.flatMap { r ->
                if (!r.overlaps(c)) {
                    listOf(r)
                } else {
                    buildList {
                        if (r.start < c.start) add(CertificateRange(r.start, c.start - java.math.BigInteger.ONE))
                        if (c.end < r.end) add(CertificateRange(c.end + java.math.BigInteger.ONE, r.end))
                    }
                }
            }
        }
        return remaining
    }
}
