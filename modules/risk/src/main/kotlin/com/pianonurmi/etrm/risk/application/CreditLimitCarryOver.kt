package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.LimitScope
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.domain.RiskRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * One-off: counterparty credit limits kept in reference data before ADR 0022 become active group-wide CREDIT_EXPOSURE
 * limits in the counterparty's settlement currency. Runs once per database (`risk_seed`).
 */
@Component
class CreditLimitCarryOver(
    private val repository: RiskRepository,
    private val referenceData: ReferenceDataQuery,
    private val clock: Clock,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(CreditLimitCarryOver::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        carryOver()
    }

    @Transactional
    fun carryOver(): Int {
        if (repository.seeded(SEED)) return 0
        val now = clock.instant()
        val carried = referenceData.counterparties().filter { it.creditLimit != null }.onEach { counterparty ->
            repository.insertLimit(
                RiskLimitView(
                    Ids.newId(),
                    null,
                    LimitType.CREDIT_EXPOSURE,
                    LimitScope(counterpartyId = counterparty.id),
                    counterparty.creditLimit!!,
                    counterparty.settlementCurrency.currencyCode,
                    DEFAULT_WARNING_PCT,
                    LocalDate.now(clock),
                    null,
                    LimitStatus.ACTIVE,
                    null,
                    "Carried over from the counterparty's credit limit in reference data",
                    "referencedata",
                    now,
                    "credit-limit-carry-over",
                    now,
                ),
            )
        }.size
        repository.markSeeded(SEED)
        if (carried > 0) log.info("Carried over {} counterparty credit limits into risk limits", carried)
        return carried
    }

    private companion object {
        const val SEED = "credit-limits-from-referencedata"
        const val DEFAULT_WARNING_PCT = 80
    }
}
