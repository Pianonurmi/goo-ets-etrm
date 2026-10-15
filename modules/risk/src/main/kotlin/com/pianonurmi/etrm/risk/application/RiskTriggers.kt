package com.pianonurmi.etrm.risk.application

import com.pianonurmi.etrm.platform.jobs.JobRun
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.risk.api.LimitActivated
import com.pianonurmi.etrm.trade.api.ApprovalFinding
import com.pianonurmi.etrm.trade.api.DeliveryRecorded
import com.pianonurmi.etrm.trade.api.TradeApprovalCheck
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeCancelled
import com.pianonurmi.etrm.trade.api.TradeConfirmed
import com.pianonurmi.etrm.trade.api.TradeView
import com.pianonurmi.etrm.valuation.api.ValuationRunSignedOff
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/** Risk's pre-trade check on every captured or amended trade. */
@Component
class RiskApprovalCheck(
    @Lazy private val checks: PreTradeChecks,
) : TradeApprovalCheck {
    override fun check(trade: TradeView): List<ApprovalFinding> = checks.preTradeFindings(trade)
}

/**
 * Re-measures an entity when its exposures or positions change: a signed-off valuation, an approved, confirmed or
 * cancelled trade, a delivery. Measuring must never fail the transaction that published the event, so it runs in a
 * savepoint and a failure is logged; the daily job measures everything again.
 */
@Component
class RiskTriggers(private val risk: LimitMeasurement, private val savepoints: Savepoints, private val referenceData: ReferenceDataQuery) {
    private val log = LoggerFactory.getLogger(RiskTriggers::class.java)

    @EventListener
    fun on(event: ValuationRunSignedOff) = remeasure(event.run.legalEntityId)

    @EventListener
    fun on(event: TradeApproved) = remeasure(event.trade.legalEntityId)

    @EventListener
    fun on(event: TradeConfirmed) = remeasure(event.trade.legalEntityId)

    @EventListener
    fun on(event: TradeCancelled) = remeasure(event.trade.legalEntityId)

    @EventListener
    fun on(event: DeliveryRecorded) = remeasure(event.trade.legalEntityId)

    @EventListener
    fun on(event: LimitActivated) {
        runCatching { savepoints.attempt { risk.evaluate(event.limit) } }.onFailure { log.warn("Measuring limit {} failed: {}", event.limit.id, it.message) }
    }

    private fun remeasure(legalEntityId: UUID) {
        runCatching { savepoints.attempt { risk.measureEntity(legalEntityId) } }
            .onFailure { log.warn("Risk measurement of {} failed: {}", legalEntityId, it.message) }
    }

    /** Measures every active entity on weekday mornings and evenings. */
    @Scheduled(cron = "\${etrm.risk.measure-cron:0 30 7,20 * * MON-FRI}", zone = "Europe/Berlin")
    @SchedulerLock(name = "risk-measure", lockAtMostFor = "PT30M")
    fun measureAll() {
        referenceData.legalEntities().filter { it.active }.forEach { entity ->
            runCatching { risk.measureEntity(entity.id) }.onFailure {
                JobRun.subjectFailed()
                log.warn("Risk measurement of {} failed: {}", entity.code, it.message, it)
            }
        }
    }
}
