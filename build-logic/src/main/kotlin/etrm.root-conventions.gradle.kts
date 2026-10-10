import com.pianonurmi.build.DatabaseSettings
import com.pianonurmi.build.FlywayMigrateTask

plugins {
    base
    id("org.jetbrains.kotlinx.kover")
}

/**
 * Migrates the shared platform schema (extensions, event publication table) in the code-generation database.
 * Every module's jOOQ code generation depends on this so extensions such as ltree exist before module schemas.
 */
tasks.register<FlywayMigrateTask>("codegenPlatformMigrate") {
    group = "jooq"
    description = "Migrates the platform schema in the code-generation database"
    schema.set(DatabaseSettings.schemaFor("platform"))
    migrations.set(layout.projectDirectory.dir("platform/src/main/resources/db/migration/platform"))
    jdbcUrl.set(DatabaseSettings.codegenUrl)
    user.set(DatabaseSettings.user)
    password.set(DatabaseSettings.password)
}

kover {
    reports {
        total {
            filters {
                excludes {
                    packages("*.adapter.jooq.generated*")
                    classes("*Application*", "*Kt")
                }
            }
            verify {
                rule("domain-coverage") {
                    minBound(70)
                }
            }
        }
    }
}
