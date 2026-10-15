package com.pianonurmi.etrm.demo

import com.pianonurmi.etrm.web.DemoSeedState
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SeedRun(
    val id: UUID,
    val seedDate: LocalDate,
    val status: DemoSeedState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val stepCount: Int,
    val currentStep: String?,
    val error: String?,
)

data class SeedStepRecord(val name: String, val status: DemoSeedState, val outputs: String?)

/** The demo seeder's own log in schema `etrm_demo` (demo profile only): one row per run and per step. */
class DemoRunLog(private val jdbc: JdbcClient, private val clock: Clock) {
    fun latest(): SeedRun? = jdbc.sql(
        """select id, seed_date, status, started_at, finished_at, step_count, current_step, error
           from etrm_demo.seed_run order by started_at desc limit 1""",
    ).query { rs, _ ->
        SeedRun(
            id = rs.getObject("id", UUID::class.java),
            seedDate = rs.getObject("seed_date", LocalDate::class.java),
            status = DemoSeedState.valueOf(rs.getString("status")),
            startedAt = rs.getTimestamp("started_at").toInstant(),
            finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
            stepCount = rs.getInt("step_count"),
            currentStep = rs.getString("current_step"),
            error = rs.getString("error"),
        )
    }.optional().orElse(null)

    fun start(seedDate: LocalDate, stepCount: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("insert into etrm_demo.seed_run (id, seed_date, status, started_at, step_count) values (?, ?, 'RUNNING', ?, ?)")
            .params(id, seedDate, now(), stepCount).update()
        return id
    }

    fun resume(run: UUID, stepCount: Int) {
        jdbc.sql("update etrm_demo.seed_run set status = 'RUNNING', error = null, finished_at = null, step_count = ? where id = ?")
            .params(stepCount, run).update()
    }

    fun steps(run: UUID): List<SeedStepRecord> = jdbc.sql("select name, status, outputs from etrm_demo.seed_step where run_id = ? order by ordinal")
        .param(run)
        .query { rs, _ -> SeedStepRecord(rs.getString("name"), DemoSeedState.valueOf(rs.getString("status")), rs.getString("outputs")) }
        .list()

    fun completedSteps(run: UUID): Int =
        jdbc.sql("select count(*) from etrm_demo.seed_step where run_id = ? and status = 'COMPLETED'").param(run).query(Int::class.java).single()

    fun stepStarted(run: UUID, ordinal: Int, name: String) {
        jdbc.sql(
            """insert into etrm_demo.seed_step (run_id, ordinal, name, status, started_at) values (?, ?, ?, 'RUNNING', ?)
               on conflict (run_id, name) do update set status = 'RUNNING', ordinal = excluded.ordinal, started_at = excluded.started_at,
               finished_at = null, duration_ms = null, error = null""",
        ).params(run, ordinal, name, now()).update()
        jdbc.sql("update etrm_demo.seed_run set current_step = ? where id = ?").params(name, run).update()
    }

    fun stepFinished(run: UUID, name: String, durationMs: Long, outputs: String) {
        jdbc.sql("update etrm_demo.seed_step set status = 'COMPLETED', finished_at = ?, duration_ms = ?, outputs = ?::jsonb where run_id = ? and name = ?")
            .params(now(), durationMs, outputs, run, name).update()
    }

    fun stepFailed(run: UUID, name: String, durationMs: Long, error: String) {
        jdbc.sql("update etrm_demo.seed_step set status = 'FAILED', finished_at = ?, duration_ms = ?, error = ? where run_id = ? and name = ?")
            .params(now(), durationMs, error, run, name).update()
        jdbc.sql("update etrm_demo.seed_run set status = 'FAILED', finished_at = ?, error = ? where id = ?")
            .params(now(), "$name: $error".take(ERROR_LIMIT), run).update()
    }

    fun completed(run: UUID) {
        jdbc.sql("update etrm_demo.seed_run set status = 'COMPLETED', finished_at = ?, current_step = null where id = ?").params(now(), run).update()
    }

    private fun now() = java.sql.Timestamp.from(clock.instant())

    companion object {
        const val ERROR_LIMIT = 4000
    }
}
