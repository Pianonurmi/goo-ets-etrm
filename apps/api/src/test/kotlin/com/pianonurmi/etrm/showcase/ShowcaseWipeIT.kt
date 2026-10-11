package com.pianonurmi.etrm.showcase

import com.pianonurmi.etrm.SiblingDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/** The showcase wipe drops the ETRM schemas, and only in the database it was told to wipe (its own sibling database). */
class ShowcaseWipeIT {
    private val dataSource: DataSource = SiblingDatabase.dataSource("wipe")

    private fun query(sql: String): List<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }
    }

    private fun schemas() = query("select nspname from pg_namespace where nspname like 'etrm\\_%'")

    private fun createSchemas() = dataSource.connection.use { connection ->
        connection.createStatement().use {
            it.execute("create schema if not exists etrm_trade")
            it.execute("create table if not exists etrm_trade.t (id int)")
            it.execute("create schema if not exists etrm_demo")
        }
    }

    @Test
    fun `refuses another database and an unset name, and drops every etrm schema of the named one`() {
        createSchemas()
        shouldThrow<IllegalStateException> { ShowcaseWipe("not_this_one").beforeMigrations(dataSource) }.message shouldContain "Refusing"
        shouldThrow<IllegalArgumentException> { ShowcaseWipe("").beforeMigrations(dataSource) }
        schemas() shouldContainAll listOf("etrm_trade", "etrm_demo")

        ShowcaseWipe(query("select current_database()").single()).beforeMigrations(dataSource)
        schemas().shouldBeEmpty()
    }
}
