package com.pianonurmi.etrm.platform.test

import com.pianonurmi.etrm.platform.db.ModuleOrder
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import java.time.Instant
import javax.sql.DataSource

/**
 * Integration-test database. Uses an externally provided PostgreSQL when `ETRM_TEST_DB_URL` is set
 * (CI service container, local docker-compose, or the developer's own instance); otherwise starts a
 * Testcontainers PostgreSQL. Each test class gets its module schemas migrated with `cleanOnStart`, so tests
 * are isolated per schema but share the server.
 */
object TestDatabase {
    private val log = LoggerFactory.getLogger(TestDatabase::class.java)

    private val jdbcUrl: String
    private val user: String
    private val password: String

    init {
        val external = System.getenv("ETRM_TEST_DB_URL")
        if (external != null) {
            jdbcUrl = external
            user = System.getenv("ETRM_DB_USER") ?: "etrm"
            password = System.getenv("ETRM_DB_PASSWORD") ?: "etrm"
            log.info("Using external test database {}", jdbcUrl)
        } else {
            val container = org.testcontainers.postgresql.PostgreSQLContainer(
                org.testcontainers.utility.DockerImageName.parse("postgres:17-alpine"),
            )
                .withDatabaseName("etrm_test")
                .withUsername("etrm")
                .withPassword("etrm")
            container.start()
            jdbcUrl = container.jdbcUrl
            user = container.username
            password = container.password
            log.info("Started Testcontainers PostgreSQL {}", jdbcUrl)
        }
    }

    val dataSource: DataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                this.jdbcUrl = this@TestDatabase.jdbcUrl
                username = user
                this.password = this@TestDatabase.password
                maximumPoolSize = 8
                poolName = "etrm-test"
            },
        )
    }

    /** Joins a Spring transaction when a test runs one (e.g. with DataSourceTransactionManager); autocommit otherwise. */
    val dsl: DSLContext by lazy { DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES) }

    /** The database's current transaction time: what it stamps on rows it records (history, journals). */
    fun transactionTime(): Instant = dsl.select(DSL.currentInstant()).fetchSingle().value1()

    /** Properties for Spring Boot slices / full-context tests. */
    fun springProperties(): Map<String, String> = mapOf(
        "spring.datasource.url" to jdbcUrl,
        "spring.datasource.username" to user,
        "spring.datasource.password" to password,
    )

    /**
     * Migrates the platform schema plus the given module schemas from the classpath, dropping module schemas first
     * so each test class starts from a known state. Platform is never cleaned (extensions are shared). The modules
     * migrate in the application's order ([ModuleOrder]) whatever order they are named in.
     */
    fun migrate(vararg modules: String) {
        synchronized(this) {
            flywayFor("platform", clean = false).migrate()
            ModuleOrder.sorted(modules.toList()).filter { it != "platform" }.forEach { module ->
                val flyway = flywayFor(module, clean = true)
                flyway.clean()
                flyway.migrate()
            }
        }
    }

    /** Drops the given module schemas so a full-application test starts from empty schemas and migrates itself. */
    fun resetSchemas(vararg modules: String) {
        synchronized(this) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    modules.filter { it != "platform" }.forEach { statement.execute("DROP SCHEMA IF EXISTS etrm_$it CASCADE") }
                }
            }
        }
    }

    private fun flywayFor(module: String, clean: Boolean): Flyway = Flyway.configure()
        .dataSource(dataSource)
        .schemas("etrm_$module")
        .defaultSchema("etrm_$module")
        .createSchemas(true)
        .table("flyway_history")
        .cleanDisabled(!clean)
        .locations("classpath:db/migration/$module")
        .load()
}
