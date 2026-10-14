package com.pianonurmi.etrm.config

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import javax.sql.DataSource

/**
 * Runs Flyway per module schema in dependency order. Each module owns `db/migration/<module>` and schema
 * `etrm_<module>`; there are no foreign keys across module schemas (modules reference each other by id only),
 * so each history table is independent and a module can be extracted with its schema intact.
 */
@Configuration
@EnableConfigurationProperties(MigrationProperties::class)
class ModularMigrationsConfiguration {
    @Bean
    fun modularMigrations(owner: SchemaOwner, properties: MigrationProperties, hooks: ObjectProvider<MigrationHook>): ModularMigrations =
        ModularMigrations(owner.dataSource, properties, hooks.orderedStream().toList(), owner.runtimeRole)

    companion object {
        /**
         * Make every database-touching bean wait for migrations, the same way Spring Boot's own Flyway
         * auto-configuration does for `JdbcTemplate` and `DSLContext`.
         */
        @Bean
        @JvmStatic
        fun migrationsDependsOnPostProcessor(): BeanFactoryPostProcessor = BeanFactoryPostProcessor { factory ->
            dependsOnMigrations(factory, "org.jooq.DSLContext")
            dependsOnMigrations(factory, "org.springframework.jdbc.core.JdbcOperations")
            dependsOnMigrations(factory, "org.springframework.jdbc.core.simple.JdbcClient")
            dependsOnMigrations(factory, "org.springframework.modulith.events.core.EventPublicationRepository")
        }

        private fun dependsOnMigrations(factory: ConfigurableListableBeanFactory, typeName: String) {
            val type = runCatching { Class.forName(typeName) }.getOrNull() ?: return
            factory.getBeanNamesForType(type, true, false).forEach { name ->
                val definition: BeanDefinition = factory.getBeanDefinition(name)
                definition.setDependsOn(*((definition.dependsOn ?: emptyArray()) + "modularMigrations"))
            }
        }
    }
}

@ConfigurationProperties(prefix = "etrm.migrations")
data class MigrationProperties(
    val enabled: Boolean = true,
    /** Module order; a module only migrates when its migration folder is present on the classpath. */
    val modules: List<String> = DEFAULT_ORDER,
    /** Schemas outside the business modules, migrated after them: `demo` (the seeder's run log) in the demo profile. */
    val additionalModules: List<String> = emptyList(),
    /** The schema owner's role (`ETRM_DB_MIGRATION_USER`); when set, requests run as the datasource role with table rights only. */
    val user: String? = null,
    val password: String? = null,
) {
    companion object {
        val DEFAULT_ORDER = listOf(
            "platform",
            "document",
            "referencedata",
            "taxonomy",
            "product",
            "marketdata",
            "trade",
            "inventory",
            "allocation",
            "registry",
            "etscompliance",
            "position",
            "settlement",
            "clearing",
            "valuation",
            "ledger",
            "risk",
            "reporting",
            "workflow",
            "audit",
        )
    }
}

class ModularMigrations(
    private val dataSource: DataSource,
    private val properties: MigrationProperties,
    private val hooks: List<MigrationHook> = emptyList(),
    /** The role requests run as when it is not the owner: granted table rights in every migrated schema. */
    private val runtimeRole: String? = null,
) : InitializingBean {
    private val log = LoggerFactory.getLogger(ModularMigrations::class.java)

    override fun afterPropertiesSet() {
        if (!properties.enabled) {
            log.warn("Database migrations are disabled (etrm.migrations.enabled=false)")
            return
        }
        hooks.forEach { it.beforeMigrations(dataSource) }
        val resolver = PathMatchingResourcePatternResolver()
        (properties.modules + properties.additionalModules).forEach { module ->
            val location = "classpath:db/migration/$module"
            val present = resolver.getResources("$location/*.sql").isNotEmpty()
            if (!present) {
                log.debug("No migrations for module {}; skipping", module)
                return@forEach
            }
            val result = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schemaFor(module))
                .defaultSchema(schemaFor(module))
                .createSchemas(true)
                .table("flyway_history")
                .locations(location)
                .validateMigrationNaming(true)
                .load()
                .migrate()
            log.info("Module {}: schema {} at version {} ({} applied)", module, schemaFor(module), result.targetSchemaVersion, result.migrationsExecuted)
            runtimeRole?.let { grantRuntime(schemaFor(module), it) }
        }
    }

    /**
     * Table rights for the runtime role, for existing tables and (through default privileges) for those the owner
     * creates later, such as monthly partitions. No ownership, so no ALTER, DROP or TRUNCATE, and no way to disable a
     * trigger; the append-only triggers then hold against the application's own credentials.
     */
    private fun grantRuntime(schema: String, role: String) {
        val quotedRole = "\"" + role.replace("\"", "\"\"") + "\""
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    "GRANT USAGE ON SCHEMA $schema TO $quotedRole",
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA $schema TO $quotedRole",
                    "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA $schema TO $quotedRole",
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA $schema GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $quotedRole",
                    "ALTER DEFAULT PRIVILEGES IN SCHEMA $schema GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO $quotedRole",
                ).forEach(statement::execute)
            }
        }
    }

    companion object {
        fun schemaFor(module: String): String = "etrm_$module"
    }
}
