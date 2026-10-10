package com.pianonurmi.etrm.platform.bitemporal

import java.time.Instant
import java.time.LocalDate

/**
 * Business validity of a versioned record: the period in which the facts are true in the real world.
 * `validTo` is exclusive; `null` means open-ended.
 */
data class ValidPeriod(val validFrom: LocalDate, val validTo: LocalDate? = null) {
    init {
        require(validTo == null || validFrom < validTo) { "validFrom $validFrom must precede validTo $validTo" }
    }

    fun isValidOn(date: LocalDate): Boolean = !date.isBefore(validFrom) && (validTo == null || date.isBefore(validTo))

    fun endingAt(newValidTo: LocalDate): ValidPeriod = copy(validTo = newValidTo)
}

/**
 * Transaction time of a versioned record: when the system learned the fact and when it was superseded.
 * `supersededAt == null` marks the version the system currently believes.
 */
data class TransactionPeriod(val recordedAt: Instant, val supersededAt: Instant? = null) {
    fun isCurrent(): Boolean = supersededAt == null

    fun wasKnownAt(instant: Instant): Boolean = !instant.isBefore(recordedAt) && (supersededAt == null || instant.isBefore(supersededAt))
}

/** Query coordinates for as-of reporting: "what did we believe at [knownAt] about the state on [effectiveOn]". */
data class AsOf(val effectiveOn: LocalDate, val knownAt: Instant) {
    companion object {
        fun now(effectiveOn: LocalDate): AsOf = AsOf(effectiveOn, Instant.now())
    }
}

/** Common contract for versioned aggregates stored bitemporally. */
interface BitemporalVersion {
    val version: Int
    val validPeriod: ValidPeriod
    val transactionPeriod: TransactionPeriod
}
