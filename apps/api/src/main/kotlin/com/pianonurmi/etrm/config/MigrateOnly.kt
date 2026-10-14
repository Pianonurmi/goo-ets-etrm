package com.pianonurmi.etrm.config

import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.context.annotation.Import

/**
 * The context of `--migrate-only`: the datasource, the schema owner and the module migrations, nothing else (no web
 * server, no jobs, no listeners). Deliberately not a `@Configuration`, so the application's component scan leaves it out.
 */
@ImportAutoConfiguration(DataSourceAutoConfiguration::class)
@Import(SchemaOwnerConfiguration::class, ModularMigrationsConfiguration::class)
class MigrateOnlyApplication

/**
 * Runs the database migrations and exits (review finding O1): the Helm chart runs this in a Job before an install or
 * upgrade, as the schema owner, and the pods then start with migrations off. A failed migration fails the Job and so
 * the upgrade, before any pod of the new version starts. Migration hooks (the showcase wipe) are not part of it: the
 * showcase migrates at start-up.
 */
object MigrateOnly {
    const val FLAG = "--migrate-only"

    private val log = LoggerFactory.getLogger(MigrateOnly::class.java)

    fun requested(args: Array<String>): Boolean = FLAG in args

    /** The process exit code: 0 when every module migrated. */
    fun run(args: Array<String>): Int {
        val rest = args.filterNot { it == FLAG } + "--etrm.migrations.enabled=true"
        return try {
            val context = SpringApplicationBuilder(MigrateOnlyApplication::class.java)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(*rest.toTypedArray())
            log.info("Migrations complete")
            SpringApplication.exit(context)
        } catch (e: Exception) {
            log.error("Migrations failed", e)
            1
        }
    }
}
