package com.pianonurmi.etrm.platform.errors

/**
 * Four eyes: what one person asked for, prepared or recorded is approved by another. Services check it themselves,
 * besides the inbox (a decision can also come through the API), and say what was refused in [reason].
 */
object SecondPerson {
    /** Refuses when [decidedBy] is any of [requesters]; a requester that is not known (null) never matches. */
    fun require(decidedBy: String, vararg requesters: String?, reason: () -> String) {
        if (requesters.any { it != null && it == decidedBy }) throw SegregationOfDutiesException(reason())
    }
}
