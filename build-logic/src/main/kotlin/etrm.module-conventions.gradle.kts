import com.pianonurmi.build.DatabaseSettings
import com.pianonurmi.build.FlywayMigrateTask
import nu.studer.gradle.jooq.JooqEdition
import org.gradle.accessors.dm.LibrariesForLibs
import org.jooq.meta.jaxb.ForcedType
import org.jooq.meta.jaxb.Logging

plugins {
    id("etrm.kotlin-conventions")
    kotlin("plugin.spring")
    id("nu.studer.jooq")
}

val libs = the<LibrariesForLibs>()
val moduleName: String = project.name
val schemaName = DatabaseSettings.schemaFor(moduleName)
val migrationsDir = layout.projectDirectory.dir("src/main/resources/db/migration/$moduleName")

dependencies {
    implementation(platform(libs.spring.modulith.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.events.api)
    implementation(libs.jooq.kotlin)
    implementation(libs.jooq.postgres.extensions)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.micrometer.core)
    implementation(libs.slf4j.api)
    implementation(libs.uuid.generator)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.spring.modulith.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jooq.test)
    testImplementation(libs.spring.boot.starter.flyway.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.postgresql)
    testImplementation(libs.hikari)

    jooqGenerator(libs.postgresql)
    jooqGenerator(libs.jooq.postgres.extensions)
}

// ---------------------------------------------------------------------------------------------------------------------
// jOOQ code generation against the module's own Flyway-migrated schema.
// Only wired when the module ships migrations under src/main/resources/db/migration/<module>.
// ---------------------------------------------------------------------------------------------------------------------
if (migrationsDir.asFile.exists()) {
    val platformMigrate = rootProject.tasks.named("codegenPlatformMigrate")

    val migrateCodegenSchema = tasks.register<FlywayMigrateTask>("migrateCodegenSchema") {
        group = "jooq"
        description = "Migrates schema $schemaName in the code-generation database"
        schema.set(schemaName)
        migrations.set(migrationsDir)
        jdbcUrl.set(DatabaseSettings.codegenUrl)
        user.set(DatabaseSettings.user)
        password.set(DatabaseSettings.password)
        dependsOn(platformMigrate)
    }

    jooq {
        version.set(libs.versions.jooq.asProvider().get())
        edition.set(JooqEdition.OSS)
        configurations {
            create("main") {
                generateSchemaSourceOnCompilation.set(true)
                jooqConfiguration.apply {
                    logging = Logging.WARN
                    jdbc.apply {
                        driver = "org.postgresql.Driver"
                        url = DatabaseSettings.codegenUrl
                        user = DatabaseSettings.user
                        password = DatabaseSettings.password
                    }
                    generator.apply {
                        name = "org.jooq.codegen.JavaGenerator"
                        database.apply {
                            name = "org.jooq.meta.postgres.PostgresDatabase"
                            inputSchema = schemaName
                            includes = ".*"
                            excludes = "flyway_history"
                            isIncludeSystemIndexes = false
                            forcedTypes.addAll(
                                listOf(
                                    ForcedType().apply {
                                        name = "INSTANT"
                                        includeTypes = "timestamp\\ with\\ time\\ zone|timestamptz"
                                    },
                                ),
                            )
                        }
                        generate.apply {
                            isDeprecated = false
                            isRecords = true
                            isPojos = false
                            isDaos = false
                            isFluentSetters = true
                            isJavaTimeTypes = true
                            isGeneratedAnnotation = false
                            isNullableAnnotation = true
                            isNonnullAnnotation = true
                            nullableAnnotationType = "org.jspecify.annotations.Nullable"
                            nonnullAnnotationType = "org.jspecify.annotations.NonNull"
                            isSpatialTypes = false
                            isXmlTypes = false
                            isGlobalObjectReferences = true
                            isIndexes = false
                        }
                        target.apply {
                            packageName = "com.pianonurmi.etrm.$moduleName.adapter.jooq.generated"
                            directory = "build/generated-src/jooq/main"
                        }
                        strategy.name = "org.jooq.codegen.DefaultGeneratorStrategy"
                    }
                }
            }
        }
    }

    tasks.named("generateJooq") {
        dependsOn(migrateCodegenSchema)
        // The codegen database is external state; never cache or skip this task.
        outputs.upToDateWhen { false }
    }
}
