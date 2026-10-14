package com.pianonurmi.etrm.ledger.domain

import com.pianonurmi.etrm.ledger.api.JournalEntryView
import com.pianonurmi.etrm.platform.crypto.HashChain
import com.pianonurmi.etrm.platform.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** A journal entry as the hash chain sees it: the entry and the payload version it was hashed with. */
data class ChainedEntry(val entry: JournalEntryView, val chainVersion: Int)

/**
 * The per-entity hash chain over the journal. Version 1 hashed the number, date, event type and the transaction
 * amounts of each line; it is kept so that entries written before version 2 still verify. Version 2 also covers the
 * header (entity, period, rule, source, event, description, currency, reversal link, author and time) and every
 * line's rate, base amounts, dimensions and memo, so a change to any stored value breaks the chain.
 *
 * The status and `reversed_by` are not hashed: they are the one change the database allows (POSTED to REVERSED), and
 * the reversal entry itself is chained and names the original in `reversal_of`.
 *
 * Payloads are normalised so that a round trip through the NUMERIC columns reproduces them exactly.
 */
object JournalChain {
    const val V1 = 1
    const val V2 = 2
    const val CURRENT = V2

    fun hash(prev: String?, entry: JournalEntryView, version: Int = CURRENT): String = HashChain.next(prev, payload(entry, version))

    /** Ids of the entries whose link or hash does not verify, in chain order. */
    fun verify(chain: List<ChainedEntry>): List<UUID> {
        var prev: String? = null
        val broken = mutableListOf<UUID>()
        chain.forEach { (e, version) ->
            if (e.prevHash != prev || e.hash != hash(prev, e, version)) broken += e.id
            prev = e.hash
        }
        return broken
    }

    fun payload(entry: JournalEntryView, version: Int): String = when (version) {
        V1 -> v1(entry)
        V2 -> v2(entry)
        else -> error("Unknown journal chain version $version")
    }

    private fun v1(e: JournalEntryView): String = Json.canonical(
        mapOf(
            "n" to e.entryNumber,
            "d" to e.postingDate.toString(),
            "e" to e.eventType,
            "lines" to e.lines.map { listOf(it.accountCode, money(it.debit), money(it.credit), it.currency) },
        ),
    )

    private fun v2(e: JournalEntryView): String = Json.canonical(
        mapOf(
            "v" to V2,
            "n" to e.entryNumber,
            "id" to e.id.toString(),
            "entity" to e.legalEntityId.toString(),
            "period" to e.period,
            "d" to e.postingDate.toString(),
            "e" to e.eventType,
            "rule" to e.ruleCode,
            "sourceType" to e.sourceType,
            "sourceRef" to e.sourceRef,
            "event" to e.eventId?.toString(),
            "description" to e.description,
            "currency" to e.currency,
            "reversalOf" to e.reversalOf?.toString(),
            "by" to e.createdBy,
            "at" to e.createdAt.toString(),
            "lines" to e.lines.map {
                mapOf(
                    "no" to it.lineNo,
                    "account" to it.accountCode,
                    "debit" to money(it.debit),
                    "credit" to money(it.credit),
                    "currency" to it.currency,
                    "rate" to it.fxRate.setScale(RATE_SCALE, RoundingMode.HALF_UP).toPlainString(),
                    "baseDebit" to money(it.baseDebit),
                    "baseCredit" to money(it.baseCredit),
                    "dimensions" to it.dimensions,
                    "memo" to it.memo,
                )
            },
        ),
    )

    private fun money(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_EVEN).toPlainString()

    private const val RATE_SCALE = 8
}
