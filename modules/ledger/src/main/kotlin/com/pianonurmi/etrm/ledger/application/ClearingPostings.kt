package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.clearing.api.ClearingStatementImported
import com.pianonurmi.etrm.clearing.api.VariationMarginComputed
import com.pianonurmi.etrm.ledger.api.PostingFacts
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Cleared futures in the books (ADR 0046): the variation margin of a day is a realised result, paid to or from the
 * cash at the clearing member. A new version of a day's run posts only the difference from the version it supersedes.
 * The member's statements move initial margin and charge fees.
 */
@Component
class ClearingPostings(private val ledger: LedgerService) {
    @EventListener
    fun on(event: VariationMarginComputed) {
        val run = event.run
        val change = run.variationMargin - event.previous
        if (change.signum() == 0) return
        ledger.post(
            PostingFacts(
                legalEntityId = run.legalEntityId,
                eventType = EVENT,
                eventId = event.eventId,
                postingDate = run.businessDate,
                currency = run.currency,
                sourceType = "MARGIN_RUN",
                sourceRef = run.id.toString(),
                description = "Variation margin of ${run.businessDate}" + if (run.version > 1) " (version ${run.version})" else "",
                amounts = mapOf("gain" to change.max(BigDecimal.ZERO), "loss" to change.negate().max(BigDecimal.ZERO)),
                dimensions = mapOf("clearingAccount" to run.accountId.toString()),
            ),
        )
    }

    /** Initial margin moves cash at the member into margin posted (and back); fees are charged against that cash. */
    @EventListener
    fun on(event: ClearingStatementImported) {
        val statement = event.statement
        if (event.initialMarginChange.signum() == 0 && event.feesChange.signum() == 0) return
        ledger.post(
            PostingFacts(
                legalEntityId = statement.legalEntityId,
                eventType = STATEMENT_EVENT,
                eventId = event.eventId,
                postingDate = statement.businessDate,
                currency = statement.currency,
                sourceType = "CLEARING_STATEMENT",
                sourceRef = statement.id.toString(),
                description = "Clearing statement of ${statement.businessDate}" + if (statement.version > 1) " (version ${statement.version})" else "",
                amounts = mapOf(
                    "initialMarginIncrease" to event.initialMarginChange.max(BigDecimal.ZERO),
                    "initialMarginDecrease" to event.initialMarginChange.negate().max(BigDecimal.ZERO),
                    "feesIncrease" to event.feesChange.max(BigDecimal.ZERO),
                    "feesDecrease" to event.feesChange.negate().max(BigDecimal.ZERO),
                ),
                dimensions = mapOf("clearingAccount" to statement.accountId.toString()),
            ),
        )
    }

    companion object {
        const val EVENT = "VariationMargin"
        const val STATEMENT_EVENT = "ClearingStatement"
    }
}
