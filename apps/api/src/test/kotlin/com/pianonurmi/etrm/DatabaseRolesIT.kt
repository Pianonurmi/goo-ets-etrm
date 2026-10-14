package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.MigrationProperties
import com.pianonurmi.etrm.config.ModularMigrations
import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * With a separate schema owner (review finding D1), the role requests run as holds table rights only: it reads and
 * writes, but cannot disable the append-only triggers, drop or truncate a table, or create one. Tables the owner
 * creates later (monthly partitions) reach it through default privileges.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseRolesIT {
    private val role = "etrm_runtime_test"
    private val password = "runtime-test-password"
    private lateinit var url: String

    @BeforeAll
    fun migrateAsOwner() {
        TestDatabase.dataSource.connection.use { owner ->
            url = owner.metaData.url
            owner.createStatement().use {
                it.execute(
                    "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$role') THEN CREATE ROLE $role LOGIN PASSWORD '$password'; END IF; END $$",
                )
            }
        }
        TestDatabase.resetSchemas("ledger", "audit")
        ModularMigrations(
            TestDatabase.dataSource,
            MigrationProperties(modules = listOf("platform", "ledger", "audit")),
            runtimeRole = role,
        ).afterPropertiesSet()
    }

    private fun <T> runtime(block: (Connection) -> T): T = DriverManager.getConnection(url, role, password).use(block)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    @Test
    fun `the runtime role reads and writes the tables`() {
        runtime { it.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM etrm_ledger.journal_entry").next() } } shouldBe true
        runtime {
            it.exec(
                "INSERT INTO etrm_platform.shedlock (name, lock_until, locked_at, locked_by) VALUES ('roles-test', now(), now(), 'test') ON CONFLICT DO NOTHING",
            )
        }
    }

    @Test
    fun `the runtime role cannot disable a trigger, drop, truncate or create a table`() {
        shouldThrow<SQLException> { runtime { it.exec("ALTER TABLE etrm_ledger.journal_entry DISABLE TRIGGER journal_entry_immutable") } }
            .message shouldContain "owner"
        shouldThrow<SQLException> { runtime { it.exec("DROP TABLE etrm_audit.audit_event") } }
        shouldThrow<SQLException> { runtime { it.exec("TRUNCATE etrm_ledger.journal_line") } }
        shouldThrow<SQLException> { runtime { it.exec("CREATE TABLE etrm_ledger.rogue (id int)") } }
    }

    @Test
    fun `tables the owner creates later are open to the runtime role`() {
        TestDatabase.dataSource.connection.use { it.exec("CREATE TABLE IF NOT EXISTS etrm_ledger.roles_test_later (id int)") }
        runtime { it.exec("INSERT INTO etrm_ledger.roles_test_later VALUES (1)") }
    }
}
