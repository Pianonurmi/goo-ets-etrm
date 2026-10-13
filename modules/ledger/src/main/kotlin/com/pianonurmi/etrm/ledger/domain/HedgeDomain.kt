package com.pianonurmi.etrm.ledger.domain

import com.pianonurmi.etrm.ledger.api.HedgeMeasurementView
import com.pianonurmi.etrm.ledger.api.HedgeRelationshipView
import com.pianonurmi.etrm.ledger.api.HedgeStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

interface HedgeRepository {
    fun nextNumber(): String

    fun insert(hedge: HedgeRelationshipView)

    fun hedge(id: UUID, lock: Boolean = false): HedgeRelationshipView?

    fun hedges(legalEntityId: UUID, statuses: Set<HedgeStatus>? = null): List<HedgeRelationshipView>

    /** Designated proportions per trade over relationships in [statuses] (a trade may be split across hedges). */
    fun designatedProportions(tradeIds: Collection<UUID>, statuses: Set<HedgeStatus>): Map<UUID, BigDecimal>

    fun update(hedge: HedgeRelationshipView)

    /** Stores a measurement unless one exists for the relationship and run; returns the stored one either way. */
    fun insertMeasurement(measurement: HedgeMeasurementView): HedgeMeasurementView

    fun measurement(relationshipId: UUID, valuationRunId: UUID): HedgeMeasurementView?

    fun measurements(relationshipId: UUID): List<HedgeMeasurementView>
}

/**
 * Cash flow hedge effectiveness by the lower of (ADR 0045): the reserve holds the smaller, in absolute terms, of the
 * instruments' change in value since designation and the hypothetical derivative's value, when both move the same way;
 * the rest of the instruments' value goes to profit or loss.
 */
object Effectiveness {
    data class Split(val instrumentChange: BigDecimal, val effective: BigDecimal, val ineffective: BigDecimal, val offsetRatio: BigDecimal?)

    fun split(instrumentValue: BigDecimal, baseline: BigDecimal, hypothetical: BigDecimal): Split {
        val change = (instrumentValue - baseline).setScale(2, RoundingMode.HALF_EVEN)
        val effective = if (change.signum() != 0 && change.signum() == hypothetical.signum()) {
            if (change.abs() <= hypothetical.abs()) change else hypothetical.setScale(2, RoundingMode.HALF_EVEN)
        } else {
            BigDecimal.ZERO.setScale(2)
        }
        val ratio = if (hypothetical.signum() == 0) null else change.multiply(HUNDRED).divide(hypothetical, 1, RoundingMode.HALF_EVEN)
        return Split(change, effective, instrumentValue.setScale(2, RoundingMode.HALF_EVEN) - effective, ratio)
    }

    private val HUNDRED = BigDecimal(100)
}
