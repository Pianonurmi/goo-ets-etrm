package com.pianonurmi.etrm.allocation.fixtures

import com.pianonurmi.etrm.allocation.api.GiveAwayLine
import com.pianonurmi.etrm.allocation.api.PortfolioValue
import com.pianonurmi.etrm.allocation.api.PortfolioValueQuery
import com.pianonurmi.etrm.allocation.api.RealisedGiveAway
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** A GoO book with nothing to value: no portfolio value, no give-away. For tests of other modules. */
object NoPortfolioValues : PortfolioValueQuery {
    override fun value(holderEntityId: UUID, asOf: LocalDate): PortfolioValue? = null

    override fun realisedGiveAway(holderEntityId: UUID, from: LocalDate, to: LocalDate): RealisedGiveAway =
        RealisedGiveAway(holderEntityId, from, to, BigDecimal.ZERO, emptyList())

    override fun giveAwayLines(holderEntityId: UUID, from: LocalDate, to: LocalDate): List<GiveAwayLine> = emptyList()
}
