package com.pianonurmi.build

import org.flywaydb.core.Flyway
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Migrates schemas in the code-generation database so jOOQ can introspect them. Mirrors `ModularMigrations` in
 * `apps/api`: one Flyway history table per module schema.
 *
 * It runs inside `generateJooq`, which declares the migration folders as its inputs: an unchanged build leaves the
 * generated sources up to date (or takes them from the build cache) and never needs the database.
 */
object CodegenMigrations {
    /** Parallel `generateJooq` tasks all migrate the platform schema first; one migration at a time in this build. */
    @Synchronized
    fun migrate(schema: String, migrations: File, logger: Logger) {
        val result = Flyway.configure()
            .dataSource(DatabaseSettings.codegenUrl, DatabaseSettings.user, DatabaseSettings.password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .table("flyway_history")
            .locations("filesystem:${migrations.absolutePath}")
            .load()
            .migrate()
        logger.info("Flyway migrated schema {}: {} migration(s) applied", schema, result.migrationsExecuted)
    }
}
