package com.pianonurmi.etrm

import com.pianonurmi.etrm.database.PartitionMaintenance
import com.pianonurmi.etrm.database.PartitionedTable
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.YearMonth
import java.time.ZoneOffset

/** Monthly partitions of the append-only tables (ADR 0030): created ahead, found from the catalogue, defaults watched. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PartitionMaintenanceIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @Autowired
    private lateinit var maintenance: PartitionMaintenance

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var meters: MeterRegistry

    private val now = YearMonth.now(ZoneOffset.UTC)

    private fun partitions(schema: String, table: String): List<String> = jdbc.queryForList(
        """
        SELECT c.relname FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname = ? AND p.relname = ?
        """.trimIndent(),
        String::class.java,
        schema,
        table,
    ).filterNotNull()

    @Test
    fun `migrations partition the audit trail and the lot journal by month, three months ahead`() {
        val tables = maintenance.partitionedTables()
        tables shouldContainAll listOf(PartitionedTable("etrm_audit", "audit_event"), PartitionedTable("etrm_inventory", "lot_event"))
        tables.forEach { table ->
            partitions(table.schema, table.table) shouldContainAll (0L..3L).map { table.partitionName(now.plusMonths(it)) } +
                table.defaultPartition
        }
        maintenance.maintain(now).failed.shouldBeEmpty()
    }

    @Test
    fun `the job creates the missing months and is idempotent`() {
        val later = now.plusMonths(12)
        val first = maintenance.maintain(later, ahead = 1)
        first.created shouldContain "etrm_audit.audit_event_p${later.year}${"%02d".format(later.monthValue)}"
        maintenance.maintain(later, ahead = 1).created.shouldBeEmpty()
        partitions("etrm_inventory", "lot_event") shouldContain PartitionedTable("etrm_inventory", "lot_event").partitionName(later.plusMonths(1))
    }

    @Test
    fun `rows in a default partition are counted and block their month, which is reported`() {
        jdbc.execute("DROP SCHEMA IF EXISTS etrm_partition_probe CASCADE")
        jdbc.execute("CREATE SCHEMA etrm_partition_probe")
        try {
            jdbc.execute("CREATE TABLE etrm_partition_probe.probe (at TIMESTAMPTZ NOT NULL) PARTITION BY RANGE (at)")
            jdbc.execute("CREATE TABLE etrm_partition_probe.probe_pdefault PARTITION OF etrm_partition_probe.probe DEFAULT")
            val stray = now.plusMonths(2)
            jdbc.update("INSERT INTO etrm_partition_probe.probe VALUES (?::timestamptz)", "${stray.atDay(15)}T12:00:00Z")

            val report = maintenance.maintain(now, ahead = 3)

            report.created shouldContainAll listOf(0L, 1L, 3L).map { "etrm_partition_probe.probe_p${now.plusMonths(it).format()}" }
            report.failed shouldContainExactly listOf("etrm_partition_probe.probe_p${stray.format()}")
            report.defaultRows["etrm_partition_probe.probe"] shouldBe 1L
            meters.get("etrm.db.partitions.default.rows").tag("table", "etrm_partition_probe.probe").gauge().value() shouldBe 1.0
        } finally {
            jdbc.execute("DROP SCHEMA etrm_partition_probe CASCADE")
        }
    }

    private fun YearMonth.format(): String = "%d%02d".format(year, monthValue)
}
