package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.async.AsyncRunProblem
import com.pianonurmi.etrm.platform.async.AsyncRunStatus
import com.pianonurmi.etrm.platform.async.AsyncRunView
import com.pianonurmi.etrm.platform.async.AsyncRuns
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorBinding
import com.pianonurmi.etrm.platform.security.ActorProvider
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@ConfigurationProperties(prefix = "etrm.async-runs")
data class AsyncRunProperties(
    /** Runs working at once on this instance; more wait for a slot, so a burst cannot drain the connection pool. */
    val maxConcurrent: Int = 4,
    /** How often this instance marks its runs as alive. */
    val heartbeat: Duration = Duration.ofSeconds(30),
    /** A running run without a heartbeat for this long is failed: the instance that ran it went away. */
    val abandonAfter: Duration = Duration.ofMinutes(3),
    /** How long finished runs stay readable. */
    val retention: Duration = Duration.ofDays(7),
    /**
     * On shutdown, how long running work may still finish. It follows the requests' 30 s drain and the 10 s `preStop`
     * sleep, inside the chart's 60 s grace period; what is cut off is failed by another instance's sweep.
     */
    val shutdownWait: Duration = Duration.ofSeconds(15),
) {
    init {
        require(maxConcurrent >= 1) { "etrm.async-runs.max-concurrent must be at least 1" }
        require(abandonAfter > heartbeat.multipliedBy(2)) { "etrm.async-runs.abandon-after must exceed two heartbeats" }
    }
}

/**
 * [AsyncRuns] on `etrm_platform.async_run`. The run row is written before the work starts, so a client can poll at
 * once; the work runs on a virtual thread as the requesting actor ([ActorBinding]) in a child span of the request,
 * and its outcome is recorded with the problem a request would have answered. Runs belong to the instance that started
 * them: it beats their heartbeat, and [sweep] fails the runs of an instance that went away.
 */
@Component
@EnableConfigurationProperties(AsyncRunProperties::class)
class AsyncRunner(
    private val jdbc: JdbcTemplate,
    private val actors: ActorProvider,
    private val problems: ProblemDetailsHandler,
    private val tracer: ObjectProvider<Tracer>,
    private val properties: AsyncRunProperties,
    private val clock: Clock,
) : AsyncRuns, DisposableBean {
    private val log = LoggerFactory.getLogger(AsyncRunner::class.java)
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("async-run-", 0).factory())
    private val slots = Semaphore(properties.maxConcurrent)
    private val running = ConcurrentHashMap.newKeySet<UUID>()

    override fun start(kind: String, legalEntityId: UUID?, work: () -> UUID): AsyncRunView {
        val actor = actors.current()
        val now = Timestamp.from(clock.instant())
        val id = Ids.newId()
        jdbc.update(
            """INSERT INTO etrm_platform.async_run (id, kind, legal_entity_id, status, requested_by, requested_at, heartbeat_at)
               VALUES (?, ?, ?, 'RUNNING', ?, ?, ?)""",
            id,
            kind,
            legalEntityId,
            actor.userId,
            now,
            now,
        )
        running += id
        val parent = tracer.ifAvailable?.currentSpan()
        try {
            submit(id, kind, actor, parent, work)
        } catch (e: RejectedExecutionException) {
            running -= id
            fail(id, kind, ConflictException("The application is shutting down; start the run again"))
            log.debug("Async run {} rejected", id, e)
        }
        return find(id) ?: error("Async run $id vanished")
    }

    private fun submit(id: UUID, kind: String, actor: Actor, parent: Span?, work: () -> UUID) {
        executor.execute {
            val outcome = runCatching {
                slots.acquire()
                try {
                    ActorBinding.runAs(actor) { traced(kind, parent, work) }
                } finally {
                    slots.release()
                }
            }
            try {
                outcome.fold({ succeed(id, it) }, { fail(id, kind, it) })
            } finally {
                running -= id
            }
        }
    }

    fun find(id: UUID): AsyncRunView? = jdbc.query("SELECT * FROM etrm_platform.async_run WHERE id = ?", { rs, _ -> view(rs) }, id).firstOrNull()

    /** Marks this instance's runs as alive. Every instance beats its own runs, so no cluster lock. */
    @Scheduled(fixedDelayString = "\${etrm.async-runs.heartbeat:PT30S}", initialDelayString = "\${etrm.async-runs.heartbeat:PT30S}")
    fun beat() {
        val ids = running.toTypedArray()
        if (ids.isEmpty()) return
        jdbc.update(
            "UPDATE etrm_platform.async_run SET heartbeat_at = ? WHERE status = 'RUNNING' AND id = ANY(?)",
            Timestamp.from(clock.instant()),
            ids,
        )
    }

    /** Fails runs whose instance went away and deletes finished runs past the retention. */
    @Scheduled(fixedDelayString = "\${etrm.async-runs.sweep:PT1M}", initialDelayString = "\${etrm.async-runs.sweep:PT1M}")
    @SchedulerLock(name = "async-run-sweep", lockAtMostFor = "PT5M")
    fun sweep() {
        val now = clock.instant()
        val abandoned = AsyncRunProblem(
            500,
            "abandoned",
            "The instance running this run stopped before it finished; start the run again",
        )
        val failed = jdbc.update(
            """UPDATE etrm_platform.async_run SET status = 'FAILED', problem = ?::jsonb, finished_at = ?
               WHERE status = 'RUNNING' AND heartbeat_at < ?""",
            Json.mapper.writeValueAsString(abandoned),
            Timestamp.from(now),
            Timestamp.from(now.minus(properties.abandonAfter)),
        )
        if (failed > 0) log.warn("Failed {} async run(s) whose instance stopped beating", failed)
        jdbc.update(
            "DELETE FROM etrm_platform.async_run WHERE status <> 'RUNNING' AND finished_at < ?",
            Timestamp.from(now.minus(properties.retention)),
        )
    }

    /** Lets running work finish within [AsyncRunProperties.shutdownWait]; what is left is failed by another instance's sweep. */
    override fun destroy() {
        executor.shutdown()
        if (!executor.awaitTermination(properties.shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("{} async run(s) still running at shutdown: {}", running.size, running)
        }
    }

    private fun traced(kind: String, parent: Span?, work: () -> UUID): UUID {
        val tracer = tracer.ifAvailable ?: return work()
        // A child of the request's span: the run's work shares the request's trace id (and `etrm.correlation`).
        val span = (if (parent != null) tracer.nextSpan(parent) else tracer.nextSpan())?.name("async-run $kind")?.start() ?: return work()
        return try {
            tracer.withSpan(span).use { work() }
        } catch (e: Throwable) {
            span.error(e)
            throw e
        } finally {
            span.end()
        }
    }

    private fun succeed(id: UUID, resultId: UUID) {
        jdbc.update(
            "UPDATE etrm_platform.async_run SET status = 'SUCCEEDED', result_id = ?, finished_at = ? WHERE id = ? AND status = 'RUNNING'",
            resultId,
            Timestamp.from(clock.instant()),
            id,
        )
    }

    private fun fail(id: UUID, kind: String, error: Throwable) {
        val detail = problems.problemFor(error)
        val problem = AsyncRunProblem(
            detail.status,
            detail.title ?: "internal",
            detail.detail,
            (error as? ValidationException)?.violations.orEmpty(),
        )
        if (detail.status < 500) log.info("Async run {} ({}) failed: {}", id, kind, detail.detail)
        jdbc.update(
            "UPDATE etrm_platform.async_run SET status = 'FAILED', problem = ?::jsonb, finished_at = ? WHERE id = ? AND status = 'RUNNING'",
            Json.mapper.writeValueAsString(problem),
            Timestamp.from(clock.instant()),
            id,
        )
    }

    private fun view(rs: ResultSet) = AsyncRunView(
        id = rs.getObject("id", UUID::class.java),
        kind = rs.getString("kind"),
        legalEntityId = rs.getObject("legal_entity_id", UUID::class.java),
        status = AsyncRunStatus.valueOf(rs.getString("status")),
        resultId = rs.getObject("result_id", UUID::class.java),
        problem = rs.getString("problem")?.let { Json.mapper.readValue(it, AsyncRunProblem::class.java) },
        requestedBy = rs.getString("requested_by"),
        requestedAt = rs.getTimestamp("requested_at").toInstant(),
        finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
    )
}
