package com.pianonurmi.etrm.valuation.domain

import com.pianonurmi.etrm.valuation.api.ExplainRow
import com.pianonurmi.etrm.valuation.api.RunStatus
import com.pianonurmi.etrm.valuation.api.ValuationResultView
import com.pianonurmi.etrm.valuation.api.ValuationRunView
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
}
