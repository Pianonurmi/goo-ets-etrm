package com.pianonurmi.etrm.database

import com.pianonurmi.etrm.config.SchemaOwner
import com.pianonurmi.etrm.platform.jobs.JobRun
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** A table partitioned by month: `<table>_pYYYYMM` partitions and a `<table>_pdefault` catch-all (ADR 0030). */
data class PartitionedTable(val schema: String, val table: String) {
    val qualified: String get() = "\"$schema\".\"$table\""

    fun partitionName(month: YearMonth): String = "${table}_p${month.format(SUFFIX)}"

    val defaultPartition: String get() = "${table}_pdefault"

    private companion object {
        val SUFFIX: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    }
}

/** What one maintenance run did. */
data class PartitionReport(val created: List<String>, val failed: List<String>, val defaultRows: Map<String, Long>)

/**
 * Keeps monthly partitions ahead of time for every range-partitioned table in the `etrm_*` schemas. The tables are found
 * in the catalogue, so a module that partitions a table needs nothing here. A partition is created for the current
 * month and [monthsAhead] months after it; partitions are never dropped (archiving is a manual detach, see
 * docs/operations/database.md). Rows in a default partition mean partitions were missing when they were written: they
 * are counted in the gauge `etrm.db.partitions.default.rows` and block creating the partition for their month, which is
 * reported as a failure for an operator to resolve.
 */
@Component
class PartitionMaintenance(
    owner: SchemaOwner,
    private val meters: MeterRegistry,
    @Value("\${etrm.partitions.months-ahead:3}") private val monthsAhead: Int,
) {
    // Partitions are created by the schema owner; the runtime role cannot create tables (SchemaOwner).
    private val jdbc = JdbcTemplate(owner.dataSource)
    private val tx = TransactionTemplate(DataSourceTransactionManager(owner.dataSource))

    private val log = LoggerFactory.getLogger(PartitionMaintenance::class.java)
    private val defaultRows = ConcurrentHashMap<String, Long>()
    private val utc: Clock = Clock.systemUTC()

    @Scheduled(cron = "\${etrm.partitions.maintenance-cron:0 7 2 * * *}")
    @SchedulerLock(name = "partition-maintenance", lockAtMostFor = "PT30M")
    fun scheduled() {
        maintain(YearMonth.now(utc))
    }

    fun maintain(current: YearMonth, ahead: Int = monthsAhead): PartitionReport {
        val created = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val tables = partitionedTables()
        tables.forEach { table ->
            for (offset in 0..ahead) {
                val month = current.plusMonths(offset.toLong())
                val name = table.partitionName(month)
                if (!exists(table.schema, name)) {
                    try {
                        create(table, month)
                        created += "${table.schema}.$name"
                    } catch (e: DataAccessException) {
                        log.error("Could not create partition {}.{}: {}", table.schema, name, e.mostSpecificCause.message)
                        failed += "${table.schema}.$name"
                        JobRun.subjectFailed()
                    }
                }
            }
            record(table)
        }
        // A table that is no longer partitioned (or was dropped) has nothing in a default partition.
        (defaultRows.keys - tables.map { "${it.schema}.${it.table}" }.toSet()).forEach { defaultRows[it] = 0 }
        if (created.isNotEmpty()) log.info("Created partitions {}", created)
        return PartitionReport(created, failed, defaultRows.toMap())
    }

    /** Range-partitioned tables in the application's schemas that follow the monthly naming (have a default partition). */
    fun partitionedTables(): List<PartitionedTable> = jdbc.query(
        """
        SELECT n.nspname, c.relname
          FROM pg_partitioned_table p
          JOIN pg_class c ON c.oid = p.partrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE p.partstrat = 'r' AND n.nspname LIKE 'etrm\_%' AND p.partdefid <> 0
         ORDER BY 1, 2
        """.trimIndent(),
    ) { rs, _ -> PartitionedTable(rs.getString(1), rs.getString(2)) }

    private fun exists(schema: String, name: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?)",
        Boolean::class.java,
        schema,
        name,
    ) == true

    private fun create(table: PartitionedTable, month: YearMonth) {
        val from = month.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC)
        val to = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC)
        tx.executeWithoutResult {
            // Creating a partition locks the parent and scans the default partition; do not queue behind long readers.
            jdbc.execute("SET LOCAL lock_timeout = '10s'")
            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS \"${table.schema}\".\"${table.partitionName(month)}\" PARTITION OF ${table.qualified} " +
                    "FOR VALUES FROM ('$from') TO ('$to')",
            )
        }
    }

    private fun record(table: PartitionedTable) {
        val key = "${table.schema}.${table.table}"
        val rows = jdbc.queryForObject("SELECT count(*) FROM \"${table.schema}\".\"${table.defaultPartition}\"", Long::class.java) ?: 0
        if (defaultRows.put(key, rows) == null) {
            Gauge.builder("etrm.db.partitions.default.rows") { defaultRows[key]?.toDouble() ?: 0.0 }
                .description("Rows in a table's default partition (written outside the monthly partitions)")
                .tag("table", key)
                .register(meters)
        }
        if (rows > 0) log.warn("{} has {} rows in its default partition", key, rows)
    }
}
