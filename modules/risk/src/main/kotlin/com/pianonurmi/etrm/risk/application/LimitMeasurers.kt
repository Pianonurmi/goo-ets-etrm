package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.allocation.api.GooRiskQuery
import com.pianonurmi.etrm.clearing.api.MarginCallQuery
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.position.api.PositionQuery
import com.pianonurmi.etrm.risk.api.LimitType
import com.pianonurmi.etrm.risk.api.RiskLimitView
import com.pianonurmi.etrm.risk.domain.MeasurementGaps
import com.pianonurmi.etrm.valuation.api.ValuationQuery
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/** One limit's figure; [gaps] names what it left out, [notMeasured] that there is no figure at all (the last one stays). */
class LimitMeasure(val value: BigDecimal, val detail: String?, val gaps: MeasurementGaps = MeasurementGaps(), val notMeasured: Boolean = false) {
    companion object {
        fun notMeasured(reason: String) = LimitMeasure(BigDecimal.ZERO, "not measured: $reason", notMeasured = true)
    }
}

/** Measures the limits of its [types]; [LimitMeasurement] needs one measurer for every [LimitType]. */
interface LimitMeasurer {
    val types: Set<LimitType>

    fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure
}

/** Exposure to the counterparty's group, from the exposures stored per entity. */
@Component
class CreditLimitMeasurer(private val credit: CreditFigures) : LimitMeasurer {
    override val types = setOf(LimitType.CREDIT_EXPOSURE)

    override fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure {
        val gaps = MeasurementGaps()
        val (value, detail) = credit.creditUsage(limit, today, gaps)
        return LimitMeasure(value, detail, gaps)
    }
}

/** The absolute net position of open trades and inventory in the limit's scope. */
@Component
class PositionLimitMeasurer(private val positions: PositionFigures) : LimitMeasurer {
    override val types = setOf(LimitType.POSITION_NET)

    override fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure {
        val gaps = MeasurementGaps()
        return LimitMeasure(positions.positionNet(limit.legalEntityId!!, limit.scope, null, gaps).abs(), null, gaps)
    }
}

/** The longest remaining delivery, in months. */
@Component
class TenorLimitMeasurer(private val positions: PositionFigures) : LimitMeasurer {
    override val types = setOf(LimitType.TENOR_MAX_MONTHS)

    override fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure =
        LimitMeasure(BigDecimal(positions.maxTenor(positions.openTrades(limit.legalEntityId!!, limit.scope, null), limit.scope, today)), null)
}

/** Loss of MtM since the end of last month (in the book, if the limit names one), in the limit's currency. */
@Component
class StopLossMeasurer(private val valuations: ValuationQuery, private val fx: RiskFx) : LimitMeasurer {
    override val types = setOf(LimitType.STOP_LOSS_MTD)

    override fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure {
        val gaps = MeasurementGaps()
        val explain = valuations.explain(limit.legalEntityId!!, today.withDayOfMonth(1).minusDays(1), today)
            ?: return LimitMeasure(BigDecimal.ZERO, "no official runs to compare", gaps)
        val change = limit.scope.bookId?.let { book -> explain.rows.filter { it.bookId == book }.sumOf { it.amountBase } } ?: (explain.mtmTo - explain.mtmFrom)
        val loss = fx.convert(change.negate().max(BigDecimal.ZERO), explain.baseCurrency, limit.currency!!, today, gaps)
        return LimitMeasure(loss, "MtM ${explain.fromDate} to ${explain.toDate}: $change ${explain.baseCurrency}", gaps)
    }
}

/** Allowances short of the compliance year's obligation. */
@Component
class ComplianceShortMeasurer(private val positions: PositionQuery) : LimitMeasurer {
    override val types = setOf(LimitType.COMPLIANCE_SHORT)

    override fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure {
        val year = limit.scope.complianceYear ?: today.year
        return LimitMeasure(BigDecimal(maxOf(0L, -positions.complianceLadder(limit.legalEntityId!!, year).netPosition)), "compliance year $year")
    }
}

/** Initial margin held plus open margin calls over the entity's clearing accounts, in the limit's currency. */
@Component
class MarginLiquidityMeasurer(private val margin: MarginCallQuery, private val fx: RiskFx) : LimitMeasurer {
    override val types = setOf(LimitType.MARGIN_LIQUIDITY)

    override fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure {
        val gaps = MeasurementGaps()
        val posted = margin.marginPosted(limit.legalEntityId!!)
        val initial = posted.sumOf { fx.convert(it.initialMargin, it.currency, limit.currency!!, today, gaps) }
        val calls = posted.sumOf { fx.convert(it.openCalls, it.currency, limit.currency!!, today, gaps) }
        return LimitMeasure(money(initial + calls), "initial margin ${money(initial)}, open calls ${money(calls)} over ${posted.size} clearing accounts", gaps)
    }
}

/**
 * GoO measures come from allocation's flow (ADR 0040). Measured in a savepoint: a limit that cannot be measured is
 * NOT_MEASURED (it keeps its last value and counts as a breach) rather than failing the measurement of every other
 * limit, or standing at zero.
 */
@Component
class GooLimitMeasurer(
    /** GoO measures from allocation; resolved when used. */
    private val goo: ObjectProvider<GooRiskQuery>,
    private val savepoints: Savepoints,
) : LimitMeasurer {
    private val log = LoggerFactory.getLogger(GooLimitMeasurer::class.java)

    override val types = GOO_LIMITS

    override fun measure(limit: RiskLimitView, today: LocalDate): LimitMeasure {
        val query = goo.ifAvailable ?: return LimitMeasure.notMeasured("GoO measures are not available")
        return try {
            savepoints.attempt { measure(query, limit) }
        } catch (e: RuntimeException) {
            log.warn("GoO limit {} could not be measured", limit.id, e)
            LimitMeasure.notMeasured(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun measure(query: GooRiskQuery, limit: RiskLimitView): LimitMeasure {
        val entity = limit.legalEntityId!!
        val inEur = limit.currency == EUR
        val year = limit.scope.productionYear
        val vintage = year?.let { " (vintage $it)" }.orEmpty()
        return when (limit.type) {
            LimitType.GOO_CONCENTRATION -> {
                val code = limit.scope.bucketCode!!
                val measured = query.concentration(entity, code, year) ?: return LimitMeasure.notMeasured("no active bucket $code")
                if (inEur) {
                    LimitMeasure(
                        measured.valueEur ?: BigDecimal.ZERO,
                        "${measured.freeMwh} MWh of $code$vintage free" + (measured.markEur?.let { " at $it EUR" } ?: ", no mark"),
                    )
                } else {
                    LimitMeasure(BigDecimal(measured.freeMwh), "$code$vintage certificates no obligation uses")
                }
            }
            LimitType.GOO_EXPIRY -> {
                val days = limit.scope.horizonDays!!
                LimitMeasure(BigDecimal(query.unusedExpiringMwh(entity, days, limit.scope.bucketCode, year)), "unused$vintage, expiring within $days days")
            }
            else -> {
                val basis = query.basis(entity, year) ?: return LimitMeasure.notMeasured("the allocation cannot be solved here")
                if (inEur) {
                    LimitMeasure(basis.giveAwayEur, "${basis.mwh} MWh$vintage delivered above their product")
                } else {
                    LimitMeasure(BigDecimal(basis.mwh), "give-away$vintage ${basis.giveAwayEur} EUR")
                }
            }
        }
    }
}
