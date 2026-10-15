package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.BreakAgeing
import com.pianonurmi.etrm.inventory.api.ReconciliationQuery
import com.pianonurmi.etrm.platform.jobs.JobRun
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.InstructionStatusSource
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Outbox dispatcher for registry instructions on channels with external I/O. Each instruction is claimed with a
 * lease in a short transaction, the registry is called with no transaction open, and the result is recorded in a
 * new transaction. A dispatcher that dies mid-call leaves the lease to expire, which marks the instruction UNKNOWN.
 */
@Component
class RegistryDispatcher(
    private val repository: RegistryInstructionRepository,
    private val gateways: List<RegistryGateway>,
    private val instructions: InstructionOutcomes,
    transactionManager: PlatformTransactionManager,
    private val settings: RegistryDispatchSettings,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(RegistryDispatcher::class.java)
    private val tx = TransactionTemplate(transactionManager)
    private val outboxChannels: Set<String> = gateways.filterNot { it.inline }.map { it.channel }.toSet()

    /** Claims and executes due instructions; returns how many were executed. */
    fun dispatchDue(): Int {
        val now = clock.instant()
        val claimed = tx.execute { repository.claimDue(outboxChannels, now, now.plus(settings.lease), settings.batchSize) }.orEmpty()
        claimed.forEach(::executeClaimed)
        return claimed.size
    }

    /** Marks instructions whose dispatcher lease ran out as UNKNOWN. */
    fun recoverExpiredLeases(): Int {
        val expired = tx.execute { repository.expiredLeases(clock.instant(), RECOVERY_BATCH) }.orEmpty()
        expired.forEach { id -> tx.executeWithoutResult { instructions.leaseExpired(id) } }
        return expired.size
    }

    /** Asks registries that support status queries what happened to UNKNOWN instructions. */
    fun resolveUnknown(): Int {
        val unknown = tx.execute { repository.inStatus(InstructionStatus.UNKNOWN, RECOVERY_BATCH) }.orEmpty()
        var resolved = 0
        unknown.forEach { instruction ->
            val source = gateway(instruction.channel, instruction.registryCode) as? InstructionStatusSource ?: return@forEach
            val answer = safely("status of ${instruction.instructionKey}") { source.status(instruction) }
            if (answer != null && answer !is GatewayResult.Unknown) {
                tx.executeWithoutResult { instructions.resolveUnknown(instruction.id, answer) }
                resolved++
            }
        }
        return resolved
    }

    private fun executeClaimed(id: UUID) {
        val instruction = tx.execute { repository.find(id) } ?: return
        val gateway = gateway(instruction.channel, instruction.registryCode)
        val result = if (gateway == null) {
            GatewayResult.Retry("no gateway for channel ${instruction.channel}")
        } else {
            safely("dispatch of ${instruction.instructionKey}") { gateway.execute(instruction) } ?: GatewayResult.Unknown("gateway call failed unexpectedly")
        }
        tx.executeWithoutResult { instructions.applyDispatchResult(id, result) }
    }

    private fun gateway(channel: String, registryCode: String): RegistryGateway? = gateways.firstOrNull { it.channel == channel && it.supports(registryCode) }

    /** A gateway that throws instead of returning a result may have reached the registry, so the outcome is unknown. */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> safely(what: String, call: () -> T): T? = try {
        call()
    } catch (ex: RuntimeException) {
        log.error("Registry gateway error during {}", what, ex)
        JobRun.subjectFailed()
        null
    }

    companion object {
        const val RECOVERY_BATCH = 100
    }
}

/** Scheduled registry work, each locked across instances. */
@Component
class RegistryJobs(
    private val dispatcher: RegistryDispatcher,
    private val instructions: InstructionOutcomes,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(RegistryJobs::class.java)

    @Scheduled(cron = "\${etrm.registry.dispatch-cron:*/30 * * * * *}")
    @SchedulerLock(name = "registry-dispatch", lockAtMostFor = "PT5M")
    fun dispatch() {
        val dispatched = dispatcher.dispatchDue()
        if (dispatched > 0) log.info("Dispatched {} registry instructions", dispatched)
    }

    @Scheduled(cron = "\${etrm.registry.recovery-cron:0 */5 * * * *}")
    @SchedulerLock(name = "registry-recovery", lockAtMostFor = "PT10M")
    fun recover() {
        val expired = dispatcher.recoverExpiredLeases()
        val resolved = dispatcher.resolveUnknown()
        val overdue = instructions.flagOverdue(clock.instant())
        if (expired + resolved + overdue > 0) log.info("Registry recovery: {} leases expired, {} unknown resolved, {} overdue", expired, resolved, overdue)
    }
}

/** Backlog gauges: open instructions per status and the age of the oldest undispatched one. */
@Component
class RegistryMetrics(
    meters: ObjectProvider<MeterRegistry>,
    private val repository: RegistryInstructionRepository,
    private val breaks: ReconciliationQuery,
    private val clock: Clock,
) {
    init {
        meters.ifAvailable { registry ->
            InstructionStatus.entries.filterNot { it.terminal }.forEach { status ->
                Gauge.builder("etrm.registry.instructions.open") { (repository.openCounts()[status] ?: 0).toDouble() }
                    .tag("status", status.name)
                    .description("Open registry instructions by status")
                    .register(registry)
            }
            mapOf<String, (BreakAgeing) -> Int>(
                "1d" to { it.upToOneDay },
                "7d" to { it.upToOneWeek },
                "30d" to { it.upToOneMonth },
                "older" to { it.older },
            ).forEach { (bucket, count) ->
                Gauge.builder("etrm.reconciliation.breaks.open") { count(breaks.ageing()).toDouble() }
                    .tag("age", bucket)
                    .description("Open reconciliation breaks by days since first seen")
                    .register(registry)
            }
            Gauge.builder("etrm.registry.dispatch.lag.seconds") {
                repository.oldestPendingSince()?.let { Duration.between(it, Instant.now(clock)).seconds.toDouble() } ?: 0.0
            }
                .description("Age of the oldest instruction waiting to be dispatched")
                .register(registry)
        }
    }
}
