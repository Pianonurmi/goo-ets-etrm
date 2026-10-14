package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.MigrationProperties
import com.pianonurmi.etrm.config.ModularMigrations
import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Every append-only table (review findings D2, D3, D9 and the partitioned journals of ADR 0030) carries an enabled
 * row trigger that fires before UPDATE and DELETE, on the table and on each of its partitions. The behaviour of the
 * triggers is tested where the tables live (`JournalIntegrityIT`, `InventoryServiceIT`, the audit and lot journal
 * tests); this test keeps a table from silently losing its protection, e.g. by a migration that recreates it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppendOnlyTablesIT {
    private val protected = listOf(
        "etrm_audit.audit_event",
        "etrm_inventory.lot_event",
        "etrm_inventory.allowance_movement",
        "etrm_inventory.certificate_lot_version",
        "etrm_ledger.journal_entry",
        "etrm_ledger.journal_line",
        "etrm_ledger.commitment_memo_movement",
        "etrm_ledger.period_end_run",
        "etrm_ledger.hedge_measurement",
        "etrm_ledger.posting_rule_history",
        "etrm_referencedata.reference_history",
        "etrm_registry.registry_instruction_event",
        "etrm_trade.trade_version",
        "etrm_workflow.task_event",
    )

    @BeforeAll
    fun migrate() {
        // Migrates what is missing without dropping anything: other test classes keep application contexts on these schemas.
        ModularMigrations(TestDatabase.dataSource, MigrationProperties()).afterPropertiesSet()
    }

    @Test
    fun `each append-only table and partition has an enabled trigger before update and delete`() {
        val unprotected = TestDatabase.dataSource.connection.use { c ->
            protected.flatMap { table ->
                val relations = c.prepareStatement(
                    """
                    SELECT c.oid::regclass::text FROM pg_class c WHERE c.oid = ?::regclass
                    UNION ALL
                    SELECT i.inhrelid::regclass::text FROM pg_inherits i WHERE i.inhparent = ?::regclass
                    """.trimIndent(),
                ).use { s ->
                    s.setString(1, table)
                    s.setString(2, table)
                    s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
                withClue(table) { relations.isNotEmpty() shouldBe true }
                relations.filterNot { relation ->
                    // tgtype bits: 1 row, 2 before, 8 delete, 16 update; tgenabled 'O' fires in normal sessions.
                    c.prepareStatement(
                        """
                        SELECT count(*) FROM pg_trigger t
                        WHERE t.tgrelid = ?::regclass AND NOT t.tgisinternal AND t.tgenabled IN ('O', 'A')
                          AND (t.tgtype & 1) <> 0 AND (t.tgtype & 2) <> 0 AND (t.tgtype & 8) <> 0 AND (t.tgtype & 16) <> 0
                        """.trimIndent(),
                    ).use { s ->
                        s.setString(1, relation)
                        s.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
                    }
                }
            }
        }
        unprotected.shouldBeEmpty()
    }
}
