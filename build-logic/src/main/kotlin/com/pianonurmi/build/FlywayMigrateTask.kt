package com.pianonurmi.build

import org.flywaydb.core.Flyway
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Migrates one module schema in the code-generation database so jOOQ can introspect it.
 * Mirrors `ModularMigrations` in `apps/api`: one Flyway history table per module schema.
 */
abstract class FlywayMigrateTask : DefaultTask() {

    @get:Input
    abstract val schema: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val migrations: DirectoryProperty

    @get:Input
    abstract val jdbcUrl: Property<String>

    @get:Input
    abstract val user: Property<String>

    @get:Input
    abstract val password: Property<String>

    init {
        // Always re-run: the target database is external state, not a build output.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun migrate() {
        val flyway = Flyway.configure()
            .dataSource(jdbcUrl.get(), user.get(), password.get())
            .schemas(schema.get())
            .defaultSchema(schema.get())
            .createSchemas(true)
            .table("flyway_history")
            .locations("filesystem:${migrations.get().asFile.absolutePath}")
            .load()
        val result = flyway.migrate()
        logger.lifecycle("Flyway migrated schema ${schema.get()}: ${result.migrationsExecuted} migration(s) applied")
    }
}
