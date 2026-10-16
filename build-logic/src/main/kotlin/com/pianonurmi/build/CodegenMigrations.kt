package com.pianonurmi.build

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.exception.FlywayValidateException
import org.gradle.api.GradleException
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
    fun migrate(database: DatabaseSettings, schema: String, migrations: File, logger: Logger) {
        val flyway = Flyway.configure()
            .dataSource(database.codegenUrl, database.user, database.password)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .table("flyway_history")
            .locations("filesystem:${migrations.absolutePath}")
            // Flyway ignores applied migrations it cannot find by default. Here such a migration means the database
            // was migrated from another checkout, and the generated code would describe that checkout's schema (and
            // be stored in the build cache under this one's migrations), so only pending ones are ignored.
            .ignoreMigrationPatterns("*:pending")
            .load()
        val result = try {
            flyway.migrate()
        } catch (e: FlywayValidateException) {
            throw GradleException(
                "The codegen database ${database.codegenUrl} (schema $schema) does not match this checkout's migrations. " +
                    "Point ETRM_CODEGEN_DB_URL at a database of this checkout's own, or drop and recreate it.",
                e,
            )
        }
        logger.info("Flyway migrated schema {}: {} migration(s) applied", schema, result.migrationsExecuted)
    }
}
