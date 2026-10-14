package com.pianonurmi.etrm.ledger.application

import com.pianonurmi.etrm.ledger.domain.UnpostedEvent
import com.pianonurmi.etrm.ledger.domain.UnpostedEventRepository
import com.pianonurmi.etrm.ledger.domain.UnpostedStatus
import com.pianonurmi.etrm.platform.events.DomainEvent
import com.pianonurmi.etrm.platform.jobs.JobRun
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.workflow.api.TaskStatus
import com.pianonurmi.etrm.workflow.api.WorkflowCommand
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** A posting listener that can be handed a recorded event again. */
interface PostingReplay {
    /** Handles [event] when it is one of this listener's; returns false otherwise. */
    fun replay(event: DomainEvent): Boolean
}

/**
 * Hands recorded unposted events back to their listeners (review finding F4): hourly for every replayable record,
 * and when an accountant asks for a retry in the LEDGER_ATTENTION task. A record is resolved when the retry recorded
 * nothing for it again; its task is then closed.
 */
@Component
class UnpostedReplay(
    private val repository: UnpostedEventRepository,
    private val unposted: UnpostedEvents,
    private val listeners: ObjectProvider<PostingReplay>,
    private val workflow: WorkflowCommand,
    transactionManager: PlatformTransactionManager,
    meters: ObjectProvider<MeterRegistry>,
) {
    private val log = LoggerFactory.getLogger(UnpostedReplay::class.java)
    private val transactions = TransactionTemplate(transactionManager)

    init {
        meters.ifAvailable { registry ->
            Gauge.builder("etrm.ledger.unposted.open") { repository.openCount().toDouble() }
                .description("Events the ledger could not post that are still open")
                .register(registry)
        }
    }

    /** Retries [record] in the caller's transaction; true when it posted. Throws when the record cannot be retried. */
    fun retry(record: UnpostedEvent, by: String): Boolean {
        check(record.replayable && record.eventJson != null && record.eventClass != null) {
            "This event cannot be retried automatically; post a manual journal and dismiss it"
        }
        val event = Json.mapper.readValue(record.eventJson, Class.forName(record.eventClass)) as DomainEvent
        // Every listener of the event gets it again: they post idempotently per event, and which one failed is not kept.
        val (handled, touched) = unposted.collecting { listeners.orderedStream().toList().map { it.replay(event) }.any { it } }
        check(handled) { "No posting listener handles ${record.eventClass}" }
        if (record.id in touched) return false
        repository.resolve(record.id, UnpostedStatus.POSTED, by, "Posted on retry")
        workflow.closeFor(LEDGER_ATTENTION, SUBJECT, record.id, TaskStatus.COMPLETED, "Posted on retry")
        return true
    }

    @Scheduled(cron = "\${etrm.ledger.unposted-replay-cron:0 20 * * * *}")
    @SchedulerLock(name = "ledger-unposted-replay", lockAtMostFor = "PT30M")
    fun replayAll(): Int {
        var posted = 0
        repository.replayable(BATCH).forEach { record ->
            runCatching { transactions.execute { retry(record, SYSTEM_USER) } == true }
                .onSuccess { if (it) posted++ }
                .onFailure {
                    log.error("Replay of unposted event {} failed", record.id, it)
                    JobRun.subjectFailed()
                }
        }
        if (posted > 0) log.info("Posted {} recorded events on retry", posted)
        return posted
    }

    fun find(id: UUID): UnpostedEvent? = repository.find(id)

    companion object {
        const val LEDGER_ATTENTION = "LEDGER_ATTENTION"
        const val SUBJECT = "UNPOSTED_EVENT"
        private const val SYSTEM_USER = "system"
        private const val BATCH = 200
    }
}
