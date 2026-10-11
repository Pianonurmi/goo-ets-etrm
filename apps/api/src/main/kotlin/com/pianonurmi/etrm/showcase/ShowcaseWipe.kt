package com.pianonurmi.etrm.showcase

import com.pianonurmi.etrm.config.MigrationHook
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * The showcase starts from an empty database every time (ADR 0033): before the migrations it drops every `etrm_*`
 * schema, so migrations and the demo seeder rebuild the example portfolio. It refuses unless the connected database is
 * the one named in `etrm.showcase.database`, so a misconfigured showcase can never wipe another database.
 */
class ShowcaseWipe(private val expectedDatabase: String) : MigrationHook {
    private val log = LoggerFactory.getLogger(ShowcaseWipe::class.java)

    override fun beforeMigrations(dataSource: DataSource) {
        require(expectedDatabase.isNotBlank()) {
            "The showcase wipes its database on every start: set ETRM_SHOWCASE_DATABASE to the database's name to confirm"
        }
        dataSource.connection.use { connection ->
            val actual = connection.createStatement().use { statement ->
                statement.executeQuery("select current_database()").use { rows ->
                    rows.next()
                    rows.getString(1)
                }
            }
            check(actual == expectedDatabase) {
                "Refusing to wipe database '$actual': the showcase is configured for '$expectedDatabase' (ETRM_SHOWCASE_DATABASE)"
            }
            val schemas = connection.createStatement().use { statement ->
                statement.executeQuery("select nspname from pg_namespace where nspname like 'etrm\\_%' order by nspname").use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
            connection.createStatement().use { statement -> schemas.forEach { statement.execute("drop schema \"$it\" cascade") } }
            log.warn("Showcase reset: dropped {} schemas of database {}; migrations and demo data follow", schemas.size, actual)
        }
    }
}
