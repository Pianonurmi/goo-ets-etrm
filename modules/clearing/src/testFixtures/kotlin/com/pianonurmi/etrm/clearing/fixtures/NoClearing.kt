package com.pianonurmi.etrm.clearing.fixtures

import com.pianonurmi.etrm.clearing.api.ClearingAccountView
import com.pianonurmi.etrm.clearing.api.ClearingQuery
import com.pianonurmi.etrm.clearing.api.FinalSettlement
import com.pianonurmi.etrm.clearing.api.MarginCallQuery
import com.pianonurmi.etrm.clearing.api.MarginCallStatus
import com.pianonurmi.etrm.clearing.api.MarginCallView
import com.pianonurmi.etrm.clearing.api.MarginPostedView
import com.pianonurmi.etrm.clearing.api.MarginRunView
import java.time.LocalDate
import java.util.UUID

/** A book without clearing accounts: no margined futures, no runs, no final settlements. For tests of other modules. */
object NoClearing : ClearingQuery {
    override fun accounts(legalEntityId: UUID?): List<ClearingAccountView> = emptyList()

    override fun account(id: UUID): ClearingAccountView? = null

    override fun runs(accountId: UUID, from: LocalDate, to: LocalDate): List<MarginRunView> = emptyList()

    override fun run(id: UUID): MarginRunView? = null

    override fun marginedTradeIds(legalEntityId: UUID): Set<UUID> = emptySet()

    override fun finalSettlement(tradeId: UUID): FinalSettlement? = null
}

/** No margin calls and no margin posted. */
object NoMarginCalls : MarginCallQuery {
    override fun marginCalls(legalEntityId: UUID?, status: MarginCallStatus?): List<MarginCallView> = emptyList()

    override fun marginCall(id: UUID): MarginCallView? = null

    override fun marginPosted(legalEntityId: UUID): List<MarginPostedView> = emptyList()
}
