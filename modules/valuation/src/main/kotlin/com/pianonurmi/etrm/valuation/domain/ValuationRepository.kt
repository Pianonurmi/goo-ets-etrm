package com.pianonurmi.etrm.valuation.domain

import com.pianonurmi.etrm.valuation.api.ExplainRow
import com.pianonurmi.etrm.valuation.api.PortfolioBridgeView
import com.pianonurmi.etrm.valuation.api.PortfolioCause
import com.pianonurmi.etrm.valuation.api.PortfolioExplainStep
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunView
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ValuationRepository {
    fun insert(run: ValuationRunView, results: List<ValuationResultView>)

    fun run(id: UUID, lock: Boolean = false): ValuationRunView?

    fun runs(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<ValuationRunView>

    fun results(runId: UUID, bookId: UUID?): List<ValuationResultView>

    fun official(legalEntityId: UUID, date: LocalDate, orEarlier: Boolean, before: Boolean = false): ValuationRunView?

    fun setStatus(id: UUID, status: RunStatus, signedOffBy: String? = null, at: Instant? = null, note: String? = null)

    fun supersede(id: UUID, at: Instant)

    fun insertExplain(runId: UUID, priorRunId: UUID?, rows: List<ExplainRow>)

    /** Stores the run's portfolio value (without explain). */
    fun insertPortfolio(portfolio: PortfolioBridgeView)

    fun portfolio(runId: UUID): PortfolioBridgeView?

    fun insertPortfolioExplain(runId: UUID, priorRunId: UUID, causes: Map<PortfolioCause, BigDecimal>)

    /** Explained portfolio moves of official runs dated after [from] up to [to], oldest first. */
    fun portfolioExplains(legalEntityId: UUID, from: LocalDate, to: LocalDate): List<PortfolioExplainStep>
}
