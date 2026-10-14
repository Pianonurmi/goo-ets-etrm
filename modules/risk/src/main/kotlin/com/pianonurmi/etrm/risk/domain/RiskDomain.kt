package com.pianonurmi.etrm.risk.domain

import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.risk.api.CounterpartyExposureView
import com.pianonurmi.etrm.risk.api.CreditSupportStatus
import com.pianonurmi.etrm.risk.api.CreditSupportView
import com.pianonurmi.etrm.risk.api.LimitBreachView
import com.pianonurmi.etrm.risk.api.LimitStatus
import com.pianonurmi.etrm.risk.api.LimitUtilisationView
import com.pianonurmi.etrm.risk.api.PfeAddOnView
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.api.UtilisationStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Credit exposure of one entity to one counterparty, all amounts in one currency. */
object CreditExposure {
    /** Positive MtM: netted over the counterparty under a netting agreement, else trade by trade. */
    fun positiveMtm(mtmByTrade: Collection<BigDecimal>, netting: Boolean): BigDecimal = if (netting) {
        mtmByTrade.fold(BigDecimal.ZERO, BigDecimal::add).max(BigDecimal.ZERO)
    } else {
        mtmByTrade.filter { it.signum() > 0 }.fold(BigDecimal.ZERO, BigDecimal::add)
    }

    /** max(0, MtM + receivables − netted payables + PFE − collateral); payables are set off only under netting. */
    fun exposure(mtm: BigDecimal, receivables: BigDecimal, payables: BigDecimal, netting: Boolean, pfe: BigDecimal, collateral: BigDecimal): BigDecimal =
        (mtm + receivables - (if (netting) payables else BigDecimal.ZERO) + pfe - collateral).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN)
}

object PotentialFutureExposure {
    /** The add-on percentage of the first band whose tenor reaches the delivery; zero for a family without bands. */
    fun pct(addOns: List<PfeAddOnView>, family: ProductFamily, tenorMonths: Long): BigDecimal =
        addOns.filter { it.productFamily == family }.sortedBy { it.maxTenorMonths }.firstOrNull { tenorMonths <= it.maxTenorMonths }?.pct ?: BigDecimal.ZERO

    fun addOn(notional: BigDecimal, pct: BigDecimal): BigDecimal = (notional.abs() * pct).divide(BigDecimal(PERCENT), 2, RoundingMode.HALF_EVEN)

    private const val PERCENT = 100
}

object Tenor {
    /** Whole months from [from] to [to], rounded up; zero for a date already reached. */
    fun months(from: LocalDate, to: LocalDate): Long {
        if (!to.isAfter(from)) return 0
        val whole = ChronoUnit.MONTHS.between(from, to)
        return if (from.plusMonths(whole).isBefore(to)) whole + 1 else whole
    }
}

object Utilisation {
    /** The latest measure of a limit that could not be measured: the last value stays, the status says so. */
    fun notMeasured(limitId: UUID, lastValue: BigDecimal, limit: RiskLimitView, detail: String?, at: Instant, gaps: MeasurementGaps? = null) =
        of(limitId, lastValue, limit, detail, at, gaps).copy(status = UtilisationStatus.NOT_MEASURED)

    fun of(limitId: UUID, value: BigDecimal, limit: RiskLimitView, detail: String?, at: Instant, gaps: MeasurementGaps? = null): LimitUtilisationView {
        val pct = if (limit.amount.signum() == 0) {
            if (value.signum() > 0) BigDecimal(FULL * 10) else BigDecimal.ZERO
        } else {
            (value * BigDecimal(FULL)).divide(limit.amount, 2, RoundingMode.HALF_EVEN)
        }
        val status = when {
            value > limit.amount -> UtilisationStatus.BREACH
            pct >= BigDecimal(limit.warningPct) -> UtilisationStatus.WARNING
            else -> UtilisationStatus.OK
        }
        return LimitUtilisationView(
            limitId,
            value.setScale(2, RoundingMode.HALF_EVEN),
            pct,
            status,
            detail,
            at,
            gaps?.isComplete == false,
            gaps?.currencies.orEmpty(),
            gaps?.reasons.orEmpty(),
        )
    }

    private const val FULL = 100
}

/**
 * What a measurement left out: the currencies without an exchange rate, and other reasons such as an option without a
 * value. A measurement with gaps is stored as incomplete, so a figure that looks low is not read as safe.
 */
class MeasurementGaps {
    private val currencySet = sortedSetOf<String>()
    private val reasonSet = linkedSetOf<String>()

    val currencies: List<String> get() = currencySet.toList()
    val reasons: List<String> get() = reasonSet.toList()
    val isComplete: Boolean get() = currencySet.isEmpty() && reasonSet.isEmpty()

    /** Something was left out, so the figure is a floor: a last measure above it is kept. An assumption leaves it as it is. */
    var isFloor: Boolean = false
        private set

    fun missingRate(from: String, to: String, date: LocalDate) {
        currencySet += from
        reasonSet += "no $from/$to rate on $date"
        isFloor = true
    }

    /** An amount or exposure that could not be measured and counts as nothing. */
    fun omitted(reason: String) {
        reasonSet += reason
        isFloor = true
    }

    /** A figure that could not be measured and was replaced by a conservative assumption. */
    fun assumed(reason: String) {
        reasonSet += reason
    }

    fun addAll(currencies: Collection<String>, reasons: Collection<String>) {
        currencySet += currencies
        reasonSet += reasons
        if (currencies.isNotEmpty()) isFloor = true
    }

    /** The reasons in one sentence for a detail text or a finding. */
    fun summary(): String = reasons.joinToString("; ")
}

/** Counterparty groups: a limit on a parent covers the parent and every counterparty below it. */
object CounterpartyGroups {
    fun members(root: UUID, parentOf: Map<UUID, UUID?>): Set<UUID> {
        val children = parentOf.entries.filter { it.value != null }.groupBy({ it.value!! }, { it.key })
        val members = mutableSetOf(root)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            children[queue.removeFirst()].orEmpty().filter { members.add(it) }.forEach(queue::addLast)
        }
        return members
    }

    /** The counterparty and its parents up to the top of the group. */
    fun ancestry(counterpartyId: UUID, parentOf: Map<UUID, UUID?>): List<UUID> = generateSequence(counterpartyId) { parentOf[it] }.take(MAX_DEPTH).toList()

    private const val MAX_DEPTH = 32
}

interface RiskRepository {
    fun insertLimit(limit: RiskLimitView)

    fun limit(id: UUID, lock: Boolean = false): RiskLimitView?

    fun limits(legalEntityId: UUID?, includeGroupWide: Boolean, statuses: Set<LimitStatus>?): List<RiskLimitView>

    fun setLimitStatus(id: UUID, status: LimitStatus, approvedBy: String?, at: Instant?)

    fun insertCreditSupport(support: CreditSupportView)

    fun creditSupport(id: UUID, lock: Boolean = false): CreditSupportView?

    fun creditSupportFor(counterpartyId: UUID?): List<CreditSupportView>

    fun setCreditSupportStatus(id: UUID, status: CreditSupportStatus, approvedBy: String?, at: Instant?)

    fun pfeAddOns(): List<PfeAddOnView>

    fun upsertExposure(exposure: CounterpartyExposureView)

    fun exposures(legalEntityId: UUID?): List<CounterpartyExposureView>

    fun upsertUtilisation(utilisation: LimitUtilisationView)

    fun utilisation(limitId: UUID): LimitUtilisationView?

    fun openBreach(limitId: UUID): LimitBreachView?

    fun insertBreach(breach: LimitBreachView)

    fun updateBreach(breach: LimitBreachView)

    fun breaches(limitIds: Set<UUID>, openOnly: Boolean): List<LimitBreachView>

    fun seeded(name: String): Boolean

    fun markSeeded(name: String)
}
