package com.pianonurmi.etrm.ledger.domain

import com.pianonurmi.etrm.ledger.api.MtmPolicyView
import com.pianonurmi.etrm.ledger.api.MtmTreatment
import com.pianonurmi.etrm.ledger.api.NrvPolicyView
import com.pianonurmi.etrm.ledger.api.PeriodEndRunView
import com.pianonurmi.etrm.ledger.api.PolicyChangeStatus
import com.pianonurmi.etrm.ledger.api.PolicyChangeView
import com.pianonurmi.etrm.referencedata.api.BookType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Emission figures of one installation for a compliance year, as the period end needs them. */
data class EmissionFigures(
    /** Approved verified emissions, if any. */
    val verified: Long?,
    /** Approved monthly estimates by month. */
    val monthly: Map<Int, Long>,
    /** The approved forecast for the year, if any. */
    val forecast: Long?,
)

/** Pure measurements of the period end. Amounts are in the entity's base currency. */
object PeriodEndMath {
    private val ZERO_2 = BigDecimal.ZERO.setScale(2)

    /** The last weekday of the period (bank holidays are not modelled). */
    fun lastBusinessDay(period: YearMonth): LocalDate {
        var day = period.atEndOfMonth()
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        return day
    }

    /** The posting that moves a balance from [booked] to [required]: (increase, decrease), one of them zero. */
    fun movement(required: BigDecimal, booked: BigDecimal): Pair<BigDecimal, BigDecimal> {
        val delta = (required - booked).setScale(2, RoundingMode.HALF_EVEN)
        return if (delta.signum() >= 0) delta to ZERO_2 else ZERO_2 to delta.negate()
    }

    /**
     * Emissions of a compliance year incurred by the end of [through]: all of an earlier year; for the current year
     * the verified figure, else the monthly estimates of the months to date, else the forecast pro rata.
     */
    fun emissionsToDate(figures: EmissionFigures, year: Int, through: YearMonth): Long {
        val annual = annualEmissions(figures)
        return when {
            year < through.year -> annual
            year > through.year -> 0
            figures.verified != null -> figures.verified
            figures.monthly.isNotEmpty() -> figures.monthly.filterKeys { it <= through.monthValue }.values.sum()
            figures.forecast != null -> BigDecimal(figures.forecast * through.monthValue).divide(BigDecimal(MONTHS), 0, RoundingMode.HALF_UP).toLong()
            else -> 0
        }
    }

    /** The year's emissions as best known: verified, else the monthly estimates, else the forecast. */
    fun annualEmissions(
        figures: EmissionFigures,
    ): Long = figures.verified ?: figures.monthly.values.sum().takeIf { figures.monthly.isNotEmpty() } ?: figures.forecast ?: 0

    data class Provision(
        val outstanding: Long,
        val covered: Long,
        val coveredCost: BigDecimal,
        val shortfall: Long,
        val shortfallPrice: BigDecimal?,
        val required: BigDecimal,
    )

    /**
     * IAS 37 net liability approach: emissions not yet surrendered are covered by the allowances held at their
     * carrying amount (consumed by the entity's costing method), and any shortfall at the market price.
     */
    fun provision(outstanding: Long, layers: List<CostLayer>, method: CostingMethod, marketPrice: BigDecimal?): Provision {
        val held = layers.sumOf { it.remainingQuantity }.toLong()
        val covered = minOf(outstanding, held)
        val coveredCost = if (covered > 0) CostingEngine.consume(layers, BigDecimal(covered), method).cost else ZERO_2
        val shortfall = outstanding - covered
        val shortfallCost = (marketPrice ?: BigDecimal.ZERO) * BigDecimal(shortfall)
        return Provision(outstanding, covered, coveredCost, shortfall, marketPrice, (coveredCost + shortfallCost).setScale(2, RoundingMode.HALF_EVEN))
    }

    /** Write-down of one inventory item to net realisable value; never negative (no write-up above cost). */
    fun writeDown(carrying: BigDecimal, quantity: BigDecimal, netPricePerUnit: BigDecimal): BigDecimal {
        val nrv = (quantity * netPricePerUnit.max(BigDecimal.ZERO)).setScale(2, RoundingMode.HALF_EVEN)
        return (carrying - nrv).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
    }

    /** Share of a year's free allocation grant released by the period end, in line with emissions incurred. */
    fun grantReleaseShare(emissionsToDate: Long, annualEmissions: Long, year: Int, through: YearMonth): BigDecimal = when {
        year < through.year -> BigDecimal.ONE
        year > through.year -> BigDecimal.ZERO
        // Without emissions to go by, the grant is released evenly over the year.
        annualEmissions <= 0 -> BigDecimal(through.monthValue).divide(BigDecimal(MONTHS), SHARE_SCALE, RoundingMode.HALF_EVEN)
        else -> BigDecimal(emissionsToDate).divide(BigDecimal(annualEmissions), SHARE_SCALE, RoundingMode.HALF_EVEN).min(BigDecimal.ONE)
    }

    /** The policy that applies to an entity's book type: its own, else the default. */
    fun treatment(policies: List<MtmPolicyView>, legalEntityId: UUID, bookType: BookType): MtmPolicyView? =
        policies.firstOrNull { it.legalEntityId == legalEntityId && it.bookType == bookType }
            ?: policies.firstOrNull { it.legalEntityId == null && it.bookType == bookType }

    fun posts(policy: MtmPolicyView?): Boolean = policy != null && policy.treatment != MtmTreatment.DISCLOSE_ONLY

    private const val MONTHS = 12
    private const val SHARE_SCALE = 6
}

/** Balance of journal lines selected by account, date and filters: amounts in transaction currency and in base currency. */
data class LineBalance(val debit: BigDecimal, val credit: BigDecimal, val baseDebit: BigDecimal, val baseCredit: BigDecimal) {
    val net: BigDecimal get() = debit - credit
    val baseNet: BigDecimal get() = baseDebit - baseCredit

    companion object {
        val ZERO = LineBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
    }
}

interface PeriodEndRepository {
    fun mtmPolicies(): List<MtmPolicyView>

    fun upsertMtmPolicy(policy: MtmPolicyView)

    fun nrvPolicies(): List<NrvPolicyView>

    fun upsertNrvPolicy(policy: NrvPolicyView)

    fun insertChange(change: PolicyChangeView)

    fun change(id: UUID, lock: Boolean = false): PolicyChangeView?

    fun changes(status: PolicyChangeStatus?): List<PolicyChangeView>

    fun decideChange(id: UUID, status: PolicyChangeStatus, by: String, at: java.time.Instant)

    fun nextRunSeq(legalEntityId: UUID, period: String): Int

    fun insertRun(run: PeriodEndRunView)

    fun runs(legalEntityId: UUID, period: String?): List<PeriodEndRunView>
}
