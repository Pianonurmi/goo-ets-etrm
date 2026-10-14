package com.pianonurmi.etrm.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import javax.sql.DataSource

/**
 * The database role that owns the schemas: it runs the migrations, creates partitions and (in the showcase) wipes the
 * database. In production it is a different role from the one the application serves requests with, so the runtime
 * role holds table rights only and cannot disable the append-only triggers, drop a table or rewrite a hash chain
 * behind the triggers' back (review finding D1). Without `etrm.migrations.user` both are the application's role, as in
 * development and tests.
 */
class SchemaOwner(
    val dataSource: DataSource,
    /** The role requests run as, granted table rights after each migration; null when the owner serves requests too. */
    val runtimeRole: String?,
) : DisposableBean {
    override fun destroy() {
        (dataSource as? HikariDataSource)?.takeIf { runtimeRole != null }?.close()
    }
}

@Configuration
class SchemaOwnerConfiguration {
    private val log = LoggerFactory.getLogger(SchemaOwnerConfiguration::class.java)

    @Bean
    fun schemaOwner(
        dataSource: DataSource,
        datasourceProperties: DataSourceProperties,
        migrations: MigrationProperties,
        environment: Environment,
    ): SchemaOwner {
        val owner = migrations.user?.takeIf { it.isNotBlank() }
        val runtime = datasourceProperties.username
        if (owner == null || owner == runtime) {
            if (environment.matchesProfiles("kubernetes")) {
                log.warn(
                    "The application runs as the schema owner ({}): set ETRM_DB_MIGRATION_USER to a separate owner role so the " +
                        "runtime role cannot disable the append-only triggers (docs/operations/database.md)",
                    runtime,
                )
            }
            return SchemaOwner(dataSource, null)
        }
        val pool = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = datasourceProperties.determineUrl()
                username = owner
                password = migrations.password
                maximumPoolSize = 2
                minimumIdle = 0
                poolName = "etrm-owner"
            },
        )
        log.info("Schemas are owned by {}; requests run as {}", owner, runtime)
        return SchemaOwner(pool, runtime)
    }
}
